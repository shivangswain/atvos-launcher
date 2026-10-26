package app.atvos.launcher;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * The profile picture in the clock pill. Settings &gt; Profile Picture selects the photo of the Google account (else
 * its letter avatar), the letter only, or nothing. The user selects the account in the account chooser of Android.
 * <p>
 * The photo needs an Android OAuth client in a Google Cloud project (see README). Without it, the token request fails
 * and the letter shows. The token goes only to Google over HTTPS and is not stored. The photo is cached in the app
 * storage and updated not more than once a day.
 */
final class Profile {

    static final int REQUEST_ACCOUNT = 7;
    /** Settings > Profile Picture: the photo (else the letter), the letter, or none. */
    static final String PHOTO = "photo", INITIAL = "initial", NONE = "none";
    private static final String TAG = "atvOS";
    private static final String GOOGLE = "com.google";
    private static final String KEY_ACCOUNT = "account", KEY_PHOTO_TRIED = "photo_tried", KEY_PICTURE = "picture";
    private static final String SCOPE = "oauth2:https://www.googleapis.com/auth/userinfo.profile";
    private static final String USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final long REFRESH_MS = 24 * 60 * 60_000L;
    /** After a network or server error, the next try is after this time, not after a day. */
    private static final long RETRY_MS = 60 * 60_000L;
    /** The size suffix of a Google photo address, for example "=s96-c". */
    private static final Pattern SIZE_SUFFIX = Pattern.compile("=s\\d+(-c)?$");
    private static final Typeface MONOGRAM = Typeface.create(Typeface.DEFAULT, 500, false);
    private static final int MAX_PHOTO = 4 << 20, MAX_FILE = 16 << 20; // the maximum download and the maximum file to decode

    private final Activity activity;
    private final Executor io, net;
    private final SharedPreferences prefs;
    private final File photo;
    private final int size;

    Profile(Activity activity, Executor io, Executor net, int sizePx) {
        this.activity = activity;
        this.io = io;
        this.net = net;
        this.prefs = activity.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        this.photo = new File(activity.getFilesDir(), "google_photo");
        this.size = sizePx;
    }

    /** Loads the picture that is set in Settings: the photo, else the letter avatar, or null for none. */
    void load(Consumer<Bitmap> onAvatar) {
        String account = prefs.getString(KEY_ACCOUNT, null), picture = picture();
        io.execute(() -> {
            Bitmap result = shown(account, picture);
            activity.runOnUiThread(() -> onAvatar.accept(result)); // null shows the default avatar
        });
    }

    /** Returns the avatar for {@code account} and the setting {@code picture}. Background thread. */
    private Bitmap shown(String account, String picture) {
        if (account == null || NONE.equals(picture)) return null;
        Bitmap round = PHOTO.equals(picture) && photo.isFile() ? decodeCircle(photo, size) : null;
        return round != null ? round : monogram(account, size);
    }

    /** Returns the name (the address) of the selected Google account, or null. */
    String account() {
        return prefs.getString(KEY_ACCOUNT, null);
    }

    /** Returns {@link #PHOTO}, {@link #INITIAL} or {@link #NONE}. */
    String picture() {
        return prefs.getString(KEY_PICTURE, PHOTO);
    }

    void setPicture(String picture, Consumer<Bitmap> onAvatar) {
        prefs.edit().putString(KEY_PICTURE, picture).apply();
        load(onAvatar);
    }

    /**
     * Downloads the photo if the last try was one day ago or more, then shows it. It does not show the consent screen.
     * UI thread.
     */
    void refresh(Consumer<Bitmap> onAvatar) {
        refresh(onAvatar, false);
    }

    /** {@code ask}: Android can show its consent screen for basic profile data. */
    private void refresh(Consumer<Bitmap> onAvatar, boolean ask) {
        String name = prefs.getString(KEY_ACCOUNT, null);
        long now = System.currentTimeMillis();
        if (name == null || now - prefs.getLong(KEY_PHOTO_TRIED, 0) < REFRESH_MS) return;
        // Tries only when the network is connected.
        if (!online()) return;
        prefs.edit().putLong(KEY_PHOTO_TRIED, now).apply();
        AccountManager am = AccountManager.get(activity);
        Account account = null;
        for (Account a : am.getAccountsByType(GOOGLE)) if (a.name.equals(name)) account = a;
        if (account != null) fetchPhoto(am, account, onAvatar, ask, true);
    }

    /**
     * Gets a token and downloads the photo. {@code retry}: tries one more time with a new token if the token expired.
     */
    private void fetchPhoto(AccountManager am, Account account, Consumer<Bitmap> onAvatar, boolean ask, boolean retry) {
        String name = account.name;
        AccountManagerCallback<Bundle> done = future -> net.execute(() -> {
            try {
                String token = future.getResult().getString(AccountManager.KEY_AUTHTOKEN);
                if (token == null) return; // consent is necessary
                if (!download(token)) {
                    // The token expired. Removes it from the cache and gets a new token.
                    am.invalidateAuthToken(GOOGLE, token);
                    if (retry) activity.runOnUiThread(() -> fetchPhoto(am, account, onAvatar, false, false));
                    return;
                }
                Bitmap round = shown(name, picture());
                activity.runOnUiThread(() -> onAvatar.accept(round));
            } catch (IOException e) {
                // A network or server error: tries again sooner than the next day.
                prefs.edit().putLong(KEY_PHOTO_TRIED, System.currentTimeMillis() - REFRESH_MS + RETRY_MS).apply();
                Log.w(TAG, "No Google profile photo: " + e.getMessage());
            } catch (OperationCanceledException | AuthenticatorException | RuntimeException e) {
                // The message is "UnregisteredOnApiConsole" when the app has no OAuth client.
                Log.w(TAG, "No Google profile photo: " + e.getMessage());
            }
        });
        if (ask) am.getAuthToken(account, SCOPE, null, activity, done, null);
        else am.getAuthToken(account, SCOPE, null, false, done, null);
    }

    private boolean online() {
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        Network net = cm != null ? cm.getActiveNetwork() : null;
        NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Reads the photo address from the userinfo of Google, then saves the photo, or deletes the old photo if there is
     * none. Returns false if Google refuses the token. Network thread.
     */
    private boolean download(String token) throws IOException {
        String picture;
        HttpURLConnection http = (HttpsURLConnection) new URL(USERINFO).openConnection(); // throws if the URL is not HTTPS
        try {
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            http.setUseCaches(false);
            http.setRequestProperty("Authorization", "Bearer " + token);
            int code = http.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) return false;
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("userinfo returned " + code);
            try (InputStream in = http.getInputStream()) {
                picture = new JSONObject(new String(read(in, 64 << 10), StandardCharsets.UTF_8)).optString("picture", "");
            }
        } catch (JSONException e) {
            throw new IOException(e);
        } finally {
            http.disconnect();
        }
        if (picture.isEmpty()) {
            //noinspection ResultOfMethodCallIgnored
            photo.delete();
            return true;
        }
        // Adds a size suffix to the path for the display size. An address with a query is used as it is. The host check
        // uses the same URL object as the request.
        String sized = picture.indexOf('?') >= 0 ? picture
                : SIZE_SUFFIX.matcher(picture).replaceAll("") + "=s" + Math.max(128, size * 2) + "-c";
        URL url = new URL(sized);
        String host = url.getHost();
        if (!"https".equals(url.getProtocol()) || host == null
                || !host.toLowerCase(Locale.ROOT).endsWith(".googleusercontent.com")) {
            throw new IOException("unexpected photo address");
        }
        http = (HttpsURLConnection) url.openConnection();
        File tmp = new File(photo.getPath() + ".part");
        try {
            http.setConnectTimeout(8000);
            http.setReadTimeout(15000);
            http.setInstanceFollowRedirects(false);
            if (http.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("photo returned " + http.getResponseCode());
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                out.write(read(in, MAX_PHOTO));
            }
            if (!tmp.renameTo(photo)) throw new IOException("couldn't save the photo");
        } finally {
            http.disconnect();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return true;
    }

    /** Reads all of the stream. Throws if it has more than {@code max} bytes. */
    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8 << 10];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (out.size() + n > max) throw new IOException("too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Decodes the cached photo at approximately its display size, then makes it round. */
    private static Bitmap decodeCircle(File f, int size) {
        if (f.length() <= 0 || f.length() > MAX_FILE) return null;
        try {
            Bitmap src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); // a shader on a software canvas reads it
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                // The short side sets the scale because the circle shows the centre square. The long side has a limit
                // too.
                float k = Math.min((float) size / Math.min(w, h), 8f * size / Math.max(w, h));
                if (k < 0.5f) {
                    decoder.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                }
            });
            Bitmap round = circle(src, size);
            src.recycle();
            return round;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Returns true if the user selected an account. */
    boolean hasAccount() {
        return prefs.getString(KEY_ACCOUNT, null) != null;
    }

    /** Shows the account chooser of Android. The result goes to {@link #onActivityResult}. */
    void choose() {
        Intent pick = AccountManager.newChooseAccountIntent(null, null, new String[]{GOOGLE}, null, null, null, null);
        // The account chooser activity of the Android system first. If a TV doesn't have it there, the chooser that
        // the intent resolves to.
        Intent system = new Intent(pick).setComponent(new ComponentName("android", "android.accounts.ChooseTypeAndAccountActivity"));
        for (Intent intent : new Intent[]{system, pick}) {
            try {
                activity.startActivityForResult(intent, REQUEST_ACCOUNT);
                return;
            } catch (RuntimeException e) {
                // try the next
            }
        }
        Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
    }

    void onActivityResult(int resultCode, Intent data, Consumer<Bitmap> onAvatar) {
        String name = data != null ? data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) : null;
        if (resultCode != Activity.RESULT_OK || name == null) return;
        // A new selection downloads the photo again immediately. The photo of a different account is deleted
        // immediately.
        boolean other = !name.equals(prefs.getString(KEY_ACCOUNT, null));
        prefs.edit().putString(KEY_ACCOUNT, name).remove(KEY_PHOTO_TRIED).apply();
        if (other) {
            // The delete runs on io before load(). The download starts after the delete, on net.
            io.execute(() -> {
                //noinspection ResultOfMethodCallIgnored
                photo.delete();
                activity.runOnUiThread(() -> refresh(onAvatar, true));
            });
            load(onAvatar);
        } else {
            load(onAvatar);
            refresh(onAvatar, true);
        }
    }

    /** Returns a round copy, cropped to the centre, at {@code size} px. */
    private static Bitmap circle(Bitmap src, int size) {
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        float s = (float) size / Math.min(src.getWidth(), src.getHeight());
        Matrix m = new Matrix();
        m.setScale(s, s);
        m.postTranslate((size - src.getWidth() * s) / 2f, (size - src.getHeight() * s) / 2f);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(shader);
        new Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, p);
        return out;
    }

    /** Returns a letter avatar: the first letter of the account on a colour from the name. */
    private static Bitmap monogram(String account, int size) {
        int[] colors = {0xFF1A73E8, 0xFFD93025, 0xFF188038, 0xFFE37400, 0xFF9334E6, 0xFF007B83};
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colors[Math.floorMod(account.hashCode(), colors.length)]);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(Color.WHITE);
        p.setTypeface(MONOGRAM);
        p.setTextSize(size * 0.5f);
        p.setTextAlign(Paint.Align.CENTER);
        String letter = account.isEmpty() ? "?" : new String(Character.toChars(account.codePointAt(0))).toUpperCase(Locale.ROOT);
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p);
        return out;
    }
}
