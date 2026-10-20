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
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A tiny, self-contained adb client that speaks to this TV's <em>own</em> adb daemon on
 * 127.0.0.1:5555, for two jobs only: handing the Home button to atvOS by turning off the TV's
 * own locked home screens, and turning them back on to undo it. It can run nothing else — the only commands it ever sends are the fixed templates in
 * {@link Job}, each filled with a package name the caller vouched for and re-checked here against
 * {@link #PKG}. There is no method that runs an arbitrary command.
 *
 * <p>How it gets in: the user turns on network (wireless) debugging in Developer options and
 * approves the on-screen "Allow debugging" prompt the first time. The RSA key this signs the adb
 * handshake with is generated in the AndroidKeyStore and never leaves it; signing happens inside
 * the keystore. No root. Where the daemon isn't reachable on 5555 — some TVs only expose the newer
 * TLS pairing — {@link #run} returns {@link Result#UNREACHABLE} and the sheet says so.
 *
 * <p>Everything here blocks on socket I/O, so callers run it off the UI thread.
 */
final class SelfAdb {

    /** Loopback only: this talks to the daemon on the same TV, never another device. */
    static final String HOST = "127.0.0.1";
    static final int PORT = 5555;

    /**
     * Printed once the command has worked. adb's old shell protocol has no exit status, and a failing
     * {@code pm} still closes the stream cleanly, so success is this line, echoed only if {@code pm}
     * exited 0. {@code pm}'s own output names the package, but {@link #PKG} allows no '-', so that
     * can never contain it.
     */
    private static final String DONE = "atvos-ok";

    /** What a run does to each package: a fixed command, {@code %s} its package name. */
    enum Job {
        /** Make atvOS the default home, then disable one locked home app. */
        HOME("cmd package set-home-activity app.atvos.launcher/.HomeActivity ; pm disable-user --user 0 %s && echo " + DONE),
        /** Undo: re-enable one home app. */
        RESTORE("pm enable %s && echo " + DONE);

        final String command;

        Job(String command) {
            this.command = command;
        }
    }
    /** A conservative package-name shape; the command string is only ever a template plus one of these. */
    private static final Pattern PKG = Pattern.compile("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+");

    private static final String KEY_ALIAS = "atvos-adb-auth";
    // adb's auth: the signature is RSA_sign(NID_sha1, token), i.e. PKCS#1 over the SHA-1 DigestInfo
    // header followed by the 20-byte token. AndroidKeyStore signs the raw bytes with NONEwithRSA.
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    // adb wire protocol: 24-byte header (6 little-endian ints) then an optional payload.
    private static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_WRTE = 0x45545257, A_CLSE = 0x45534c43;
    private static final int A_VERSION = 0x01000001, A_MAXDATA = 256 * 1024;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int TOKEN_SIZE = 20; // what adbd sends to be signed
    private static final int MAX_OUTPUT = 64 << 10;
    /**
     * Packets a handshake or one command may take: adbd needs a handful. A read only times out
     * after silence, so this is what stops something else on the port keeping us talking forever.
     */
    private static final int MAX_HANDSHAKE_PACKETS = 32, MAX_COMMAND_PACKETS = 512;
    /**
     * How long a read may wait: adbd answers at once, except while the TV's prompt is up, and
     * people can take a while over that. A declined prompt doesn't wait this out (see declined()).
     */
    private static final int READ_TIMEOUT_MS = 30_000, APPROVE_TIMEOUT_MS = 5 * 60_000;

    // Set while the TV shows its "Allow debugging" prompt for our key. adbd never answers a declined
    // prompt, so the caller ends the wait with declined() once the prompt has gone.
    private volatile boolean approving;
    private volatile Socket socket;

    /** The outcome of a {@link #run} call, with a message resource for the sheet. */
    enum Result {
        OK(R.string.setup_tv_done),
        UNREACHABLE(R.string.setup_tv_unreachable),
        DENIED(R.string.setup_tv_denied),
        FAILED(R.string.setup_tv_failed);

        final int messageRes;
        Result(int messageRes) { this.messageRes = messageRes; }
    }

    /** Progress, called on the caller's background thread so the UI can show where it's up to. */
    interface Listener {
        int CONNECTING = 0, APPROVE = 1, WORKING = 2;
        void step(int step);
    }

    /**
     * Runs {@code job} on each package over self-adb, adding each one it worked for to {@code done}.
     * Returns as soon as one step fails. A package not matching {@link #PKG} fails the run before
     * anything is sent, so a bad name can never widen the command.
     */
    Result run(List<String> packages, Job job, Listener listener, List<String> done) {
        for (String pkg : packages) {
            if (!PKG.matcher(pkg).matches()) return Result.FAILED; // never send a command we can't vouch for
        }
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
            int stream = 0; // a fresh id per stream, so nothing left from the last can be taken for the next
            for (String pkg : packages) {
                String cmd = String.format(job.command, pkg);
                if (!shell(in, out, ++stream, cmd)) return Result.FAILED;
                done.add(pkg); // so a later failure doesn't lose track of what this one changed
            }
            return Result.OK;
        } catch (IOException e) {
            // Silence after offering our key (the read timing out, or declined() closing the
            // socket) means the prompt was declined or left unanswered.
            return approving ? Result.DENIED : Result.UNREACHABLE;
        } catch (GeneralSecurityException | RuntimeException e) {
            return Result.FAILED; // the keystore, or a daemon we don't understand: never a crash
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /**
     * The TV's prompt has closed without adbd letting us in, so it was declined: ends the wait now
     * instead of at the timeout. Does nothing once adbd has let us in. Any thread.
     */
    void declined() {
        Socket s = socket;
        if (!approving || s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    // ---- adb handshake -----------------------------------------------------------------------

    /**
     * CNXN, then sign the daemon's token; on an unknown key, offer the public key (the TV prompts).
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
            if (m.payload == null || m.payload.length != TOKEN_SIZE) return false; // not adbd: sign nothing else
            if (!signed) {
                send(out, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, m.payload));
                signed = true;
            } else if (!offeredKey) {
                approving = true;
                socket.setSoTimeout(APPROVE_TIMEOUT_MS);
                listener.step(Listener.APPROVE); // the daemon didn't know the key: the TV now asks
                send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKey(keys));
                offeredKey = true;
            } else {
                return false; // offered the key and still refused
            }
        }
        return false;
    }

    /**
     * Opens a {@code shell:} stream, reads its output to the end, and closes it. True only if the
     * daemon opened the stream and the command printed {@link #DONE}.
     */
    private boolean shell(DataInputStream in, OutputStream out, int local, String command) throws IOException {
        send(out, A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        int remote = 0;
        StringBuilder output = new StringBuilder();
        for (int packets = 0; packets < MAX_COMMAND_PACKETS; packets++) {
            Message m = read(in);
            if (m == null) return false;
            if (m.arg1 != 0 && m.arg1 != local) continue; // not our stream
            switch (m.command) {
                case A_OKAY:
                    remote = m.arg0;
                    break;
                case A_WRTE:
                    // Kept whole, as DONE may arrive split across two writes; a few lines at most.
                    if (m.payload != null && output.length() < MAX_OUTPUT) output.append(new String(m.payload, StandardCharsets.UTF_8));
                    send(out, A_OKAY, local, m.arg0, null);
                    break;
                case A_CLSE:
                    if (remote == 0) return false; // closed without ever opening: the daemon refused it
                    send(out, A_CLSE, local, remote, null);
                    return output.indexOf(DONE) >= 0;
                default:
                    break;
            }
        }
        return false;
    }

    // ---- framing -----------------------------------------------------------------------------

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
        h.getInt(); // checksum, unused
        if (m.command != (h.getInt() ^ 0xffffffff)) throw new IOException("bad adb magic");
        if (len < 0 || len > A_MAXDATA) throw new IOException("bad adb length");
        if (len > 0) {
            m.payload = new byte[len];
            in.readFully(m.payload);
        }
        return m;
    }

    // ---- keys --------------------------------------------------------------------------------

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
     * The public key in adb's own format: a 524-byte little-endian {@code RSAPublicKey} struct
     * (word count, n0inv, the modulus and R² mod n as 64 words each, then the exponent), base64'd
     * with a trailing comment, as {@code adb} writes {@code ~/.android/adbkey.pub}.
     */
    private static byte[] publicKey(KeyPair keys) {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
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

        String encoded = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " atvos@launcher\0";
        return encoded.getBytes(StandardCharsets.UTF_8);
    }
}
