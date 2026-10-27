package app.atvos.launcher;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An adb client for the adb daemon of this TV on 127.0.0.1:5555. It does two jobs: it disables the locked home apps of
 * the TV so that the Home button opens atvOS, and it enables them again. It sends only the fixed commands in
 * {@link Job}, each with a package name that matches {@link #PKG}. No method runs a different command. It never turns
 * on an accessibility service: the user does that in Accessibility, after the disclosure in setup.
 * <p>
 * The user turns on network debugging in Developer options and accepts the "Allow debugging" prompt one time. The RSA
 * key for the adb handshake is made in the AndroidKeyStore and does not leave it. If the daemon is not on port 5555,
 * {@link #run} returns {@link Result#UNREACHABLE}.
 * <p>
 * All methods block on socket I/O. Callers run them on a background thread.
 */
final class SelfAdb {

    /** The loopback address. The client connects only to the daemon on this TV. */
    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;

    /**
     * The command prints this line only if {@code pm} exits with 0, because the shell protocol of adb has no exit
     * status. {@link #PKG} does not allow '-', so the output of {@code pm} cannot contain this line.
     */
    private static final String DONE = "atvos-ok";
    /**
     * The command for each package. {@code %1$s} is the package name, {@code %2$d} the Android user that runs atvOS and
     * {@code %3$s} the package of atvOS. Without {@code --user}, the commands change user 0, which is a different user
     * on a TV with profiles.
     */
    enum Job {
        /**
         * Makes atvOS the default home app of this user, then disables one locked home app for this user. The disable
         * runs also if set-home-activity fails (some TVs have no home role): with the locked home app off, atvOS is the
         * home app, or Android asks which home app to use. It is never without one, because atvOS is a home app.
         * <p>
         * First it allows TCL's AUTO_START app-op for atvOS: TCL TVs start an app's services (Remote Buttons, when the
         * user turns it on in Accessibility) only with it. Other TVs don't have the op, and its error is ignored.
         */
        HOME("appops set --user %2$d %3$s AUTO_START allow >/dev/null 2>&1 ; "
                + "cmd package set-home-activity --user %2$d %3$s/.HomeActivity ; pm disable-user --user %2$d %1$s && echo " + DONE),
        /** Enables one home app again for this user. */
        RESTORE("pm enable --user %2$d %1$s && echo " + DONE);

        final String command;

        Job(String command) {
            this.command = command;
        }
    }
    /**
     * The accepted package name format. A command is always a template, one name in this format and the user number.
     */
    private static final Pattern PKG = Pattern.compile("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+");
    /** The number of uids for each Android user (UserHandle.PER_USER_RANGE): uid / range is the user number. */
    private static final int PER_USER_RANGE = 100_000;

    private static final String KEY_ALIAS = "atvos-adb-auth";
    // adb authentication: the signature is RSA_sign(NID_sha1, token), PKCS#1 over the SHA-1 DigestInfo header and the
    // 20-byte token. AndroidKeyStore signs the raw bytes with NONEwithRSA.
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    // adb wire protocol: a 24-byte header (6 little-endian ints), then an optional payload.
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43;
    private static final int A_VERSION = 0x01000001, A_MAXDATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int TOKEN_SIZE = 20; // the size of the token that adbd sends
    private static final int MAX_OUTPUT = 64 << 10;
    /**
     * The maximum number of packets for the handshake and for one command. A read times out only after silence, so this
     * limit stops a connection that does not end.
     */
    private static final int MAX_HANDSHAKE_PACKETS = 32, MAX_COMMAND_PACKETS = 512;
    /**
     * The read timeout, and the longer timeout while the TV shows its prompt. declined() ends the wait for a declined
     * prompt.
     */
    private static final int READ_TIMEOUT_MS = 30_000, APPROVE_TIMEOUT_MS = 5 * 60_000;

    // True while the TV shows the "Allow debugging" prompt for the key. adbd does not answer a declined prompt, so the
    // caller calls declined() when the prompt closes.
    private volatile boolean approving;
    private volatile Socket socket;
    private volatile String fingerprint; // the key fingerprint that the prompt shows, once it shows

    /**
     * Returns the fingerprint that the "Allow USB debugging" prompt shows for the key of atvOS, or null before the prompt
     * shows. The setup shows it too, so that the user allows this key and not a prompt from a different app. Any thread.
     */
    String fingerprint() {
        return fingerprint;
    }

    /** The result of {@link #run}, with a message resource for the sheet. */
    enum Result {
        OK(R.string.setup_tv_done),
        UNREACHABLE(R.string.setup_tv_unreachable),
        DENIED(R.string.setup_tv_denied),
        FAILED(R.string.setup_tv_failed);

        final int messageRes;
        Result(int messageRes) { this.messageRes = messageRes; }
    }

    /** Progress steps, called on the background thread of the caller. */
    interface Listener {
        int CONNECTING = 0, APPROVE = 1, WORKING = 2;
        void step(int step);
    }

    /**
     * Runs {@code job} on each package over self-adb and adds each package that succeeds to {@code done}. It returns at
     * the first failure. {@code self} is the package of atvOS. A name that does not match {@link #PKG} fails the run
     * before anything is sent.
     */
    Result run(List<String> packages, Job job, String self, Listener listener, List<String> done) {
        for (String pkg : packages) {
            if (!PKG.matcher(pkg).matches()) return Result.FAILED; // sends only names that match PKG
        }
        if (!PKG.matcher(self).matches()) return Result.FAILED;
        listener.step(Listener.CONNECTING);
        Socket socket = new Socket();
        this.socket = socket;
        try {
            socket.connect(new InetSocketAddress(HOST, PORT), 2000);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            if (!handshake(in, out, listener)) return Result.DENIED;
            listener.step(Listener.WORKING);
            int stream = 0; // a new id for each stream
            int user = android.os.Process.myUid() / PER_USER_RANGE; // 0, or the user of a TV profile
            for (String pkg : packages) {
                // ROOT: some locales format numbers with other digits, which the shell does not read.
                String cmd = String.format(Locale.ROOT, job.command, pkg, user, self);
                if (!shell(in, out, ++stream, cmd)) return Result.FAILED;
                done.add(pkg); // records each change before the next step
            }
            return Result.OK;
        } catch (IOException e) {
            // Silence after the key is offered (a read timeout, or declined() closes the socket): the prompt was
            // declined or not answered.
            return approving ? Result.DENIED : Result.UNREACHABLE;
        } catch (GeneralSecurityException | RuntimeException e) {
            return Result.FAILED; // a keystore error or an unknown daemon
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * Ends the wait when the TV prompt closes and adbd did not accept the key. It does nothing after adbd accepts the
     * key. Any thread.
     */
    void declined() {
        Socket s = socket;
        if (!approving || s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    // adb handshake

    /**
     * Sends CNXN, then signs the token of the daemon. If the daemon does not know the key, it sends the public key, and
     * the TV shows its prompt.
     */
    private boolean handshake(DataInputStream in, OutputStream out, Listener listener)
            throws IOException, GeneralSecurityException {
        KeyPair keys = keyPair();
        send(out, A_CNXN, A_VERSION, A_MAXDATA, "host::features=cmd\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false, offeredKey = false;
        for (int packets = 0; packets < MAX_HANDSHAKE_PACKETS; packets++) {
            Message m = read(in);
            if (m == null) return false;
            if (m.command == A_CNXN) { // authenticated
                approving = false;
                socket.setSoTimeout(READ_TIMEOUT_MS);
                return true;
            }
            if (m.command != A_AUTH || m.arg0 != AUTH_TOKEN) continue;
            if (m.payload == null || m.payload.length != TOKEN_SIZE) return false; // not adbd: signs nothing
            if (!signed) {
                send(out, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, m.payload));
                signed = true;
            } else if (!offeredKey) {
                approving = true;
                socket.setSoTimeout(APPROVE_TIMEOUT_MS);
                byte[] blob = keyBlob((RSAPublicKey) keys.getPublic());
                fingerprint = fingerprint(blob); // before the listener step, which reads it
                listener.step(Listener.APPROVE); // the daemon does not know the key: the TV shows its prompt
                send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(blob));
                offeredKey = true;
            } else {
                return false; // the key was refused
            }
        }
        return false;
    }

    /**
     * Opens a {@code shell:} stream, reads all of its output and closes it. Returns true only if the daemon opened the
     * stream and the command printed {@link #DONE}.
     */
    private boolean shell(DataInputStream in, OutputStream out, int local, String command) throws IOException {
        send(out, A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        StringBuilder output = new StringBuilder();
        for (int packets = 0; packets < MAX_COMMAND_PACKETS; packets++) {
            Message m = read(in);
            if (m == null) return false;
            if (m.arg1 != 0 && m.arg1 != local) continue; // a different stream
            switch (m.command) {
                case A_OKAY:
                    remote = m.arg0;
                    break;
                case A_WRTE:
                    // Keeps all of the output, because DONE can arrive in two writes. The output is short.
                    if (m.payload != null && output.length() < MAX_OUTPUT) output.append(new String(m.payload, StandardCharsets.UTF_8));
                    send(out, A_OKAY, local, m.arg0, null);
                    break;
                case A_CLSE:
                    if (remote == 0) return false; // closed before it opened: the daemon refused it
                    send(out, A_CLSE, local, remote, null);
                    return output.indexOf(DONE) >= 0;
                default:
                    break;
            }
        }
        return false;
    }

    // Framing

    private static final class Message {
        int command, arg0, arg1;
        byte[] payload;
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] payload) throws IOException {
        int len = payload != null ? payload.length : 0;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += payload[i] & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(command ^ 0xffffffff);
        out.write(h.array());
        if (len > 0) out.write(payload);
        out.flush();
    }

    private static Message read(DataInputStream in) throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.command = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        h.getInt(); // checksum, not used
        if (m.command != (h.getInt() ^ 0xffffffff)) throw new IOException("bad adb magic");
        if (len < 0 || len > A_MAXDATA) throw new IOException("bad adb length");
        if (len > 0) {
            m.payload = new byte[len];
            in.readFully(m.payload);
        }
        return m;
    }

    // Keys

    private static KeyPair keyPair() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            KeyStore.PrivateKeyEntry e = (KeyStore.PrivateKeyEntry) ks.getEntry(KEY_ALIAS, null);
            return new KeyPair(e.getCertificate().getPublicKey(), e.getPrivateKey());
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
        gen.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_NONE)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build());
        return gen.generateKeyPair();
    }

    private static byte[] sign(KeyPair keys, byte[] token) throws GeneralSecurityException {
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(keys.getPrivate());
        s.update(SHA1_DIGEST_INFO);
        s.update(token);
        return s.sign();
    }

    /**
     * Returns the public key in the adb format: the key struct base64-encoded and followed by a comment, as in
     * {@code ~/.android/adbkey.pub}.
     */
    private static byte[] publicKey(byte[] blob) {
        String encoded = Base64.encodeToString(blob, Base64.NO_WRAP) + " atvos@launcher\0";
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the fingerprint of a key struct as the prompt shows it: the MD5 of the struct in upper-case hex pairs
     * with colons ("A1:B2:…", 16 pairs), as AdbDebuggingManager.getFingerprints makes it.
     */
    static String fingerprint(byte[] blob) throws GeneralSecurityException {
        byte[] md5 = MessageDigest.getInstance("MD5").digest(blob);
        String hex = "0123456789ABCDEF";
        StringBuilder sb = new StringBuilder(3 * md5.length);
        for (int i = 0; i < md5.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(hex.charAt((md5[i] >> 4) & 15)).append(hex.charAt(md5[i] & 15));
        }
        return sb.toString();
    }

    /**
     * Returns the key in the adb struct: a 524-byte little-endian {@code RSAPublicKey} with the word count, n0inv, the
     * modulus and R² mod n as 64 words each, then the exponent.
     */
    private static byte[] keyBlob(RSAPublicKey pub) {
        BigInteger n = pub.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32)); // -1 / n[0] mod 2^32
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);    // R^2 mod n, R = 2^2048
        BigInteger mask = r32.subtract(BigInteger.ONE);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).and(mask).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).and(mask).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        return b.array();
    }
}
