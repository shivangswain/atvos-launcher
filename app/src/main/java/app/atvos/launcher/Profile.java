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

/**
 * The profile picture in the clock pill: the Google account's profile photo, else its letter avatar
 * (what Google shows for accounts without one), or, as Settings &gt; Profile Picture chooses, just
 * the letter or nothing. The account is picked once with Android's own chooser; there's no other
 * source of pictures.
 * <p>
 * Google gives profile photos only to apps it knows: this one needs an Android OAuth client in a
 * Google Cloud project (see README). Until then the token request fails and the letter stays. The
 * token is only ever sent to Google over HTTPS and never stored; the photo is cached privately and
 * refreshed at most once a day.
 */
final class Profile {

    static final int REQUEST_ACCOUNT = 7;
    /** Settings > Profile Picture: the account's photo (else its letter), its letter, or none. */
    static final String PHOTO = "photo", INITIAL = "initial", NONE = "none";
    private static final String TAG = "atvOS";
    private static final String GOOGLE = "com.google";
    private static final String KEY_ACCOUNT = "account", KEY_PHOTO_TRIED = "photo_tried", KEY_PICTURE = "picture";
    private static final String SCOPE = "oauth2:https://www.googleapis.com/auth/userinfo.profile";
    private static final String USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo";
    private static final long REFRESH_MS = 24 * 60 * 60_000L;
    private static final int MAX_PHOTO = 4 << 20, MAX_FILE = 16 << 20; // a download; any picture we decode

    private final Activity activity;
    private final Executor io;
    private final SharedPreferences prefs;
    private final File photo;
    private final int size;

    Profile(Activity activity, Executor io, int sizePx) {
        this.activity = activity;
        this.io = io;
        this.prefs = activity.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        this.photo = new File(activity.getFilesDir(), "google_photo");
        this.size = sizePx;
    }

    /** The picture chosen in Settings: the account's Google photo, else its monogram; null for none. */
    void load(Consumer<Bitmap> onAvatar) {
        String account = prefs.getString(KEY_ACCOUNT, null), picture = picture();
        io.execute(() -> {
            Bitmap result = shown(account, picture);
            activity.runOnUiThread(() -> onAvatar.accept(result)); // null: the plain silhouette
        });
    }

    /** IO thread. */
    private Bitmap shown(String account, String picture) {
        if (account == null || NONE.equals(picture)) return null;
        Bitmap round = PHOTO.equals(picture) && photo.isFile() ? decodeCircle(photo, size) : null;
        return round != null ? round : monogram(account, size);
    }

    /** The chosen Google account's name (its address), or null. */
    String account() {
        return prefs.getString(KEY_ACCOUNT, null);
    }

    /** {@link #PHOTO}, {@link #INITIAL} or {@link #NONE}. */
    String picture() {
        return prefs.getString(KEY_PICTURE, PHOTO);
    }

    void setPicture(String picture, Consumer<Bitmap> onAvatar) {
        prefs.edit().putString(KEY_PICTURE, picture).apply();
        load(onAvatar);
    }

    /**
     * Fetches the account's Google photo if it's been a day since the last try, then shows it,
     * quietly: if Google still needs your consent this does nothing, so a resume never pops up its
     * screen (picking the account asks for it). UI thread.
     */
    void refresh(Consumer<Bitmap> onAvatar) {
        refresh(onAvatar, false);
    }

    /** With {@code ask}, Android may show its consent screen (to let the launcher see basic profile info). */
    private void refresh(Consumer<Bitmap> onAvatar, boolean ask) {
        String name = prefs.getString(KEY_ACCOUNT, null);
        long now = System.currentTimeMillis();
        if (name == null || now - prefs.getLong(KEY_PHOTO_TRIED, 0) < REFRESH_MS) return;
        // Waking from standby, the TV resumes before Wi-Fi is back, and a try then would wait a day.
        if (!online()) return;
        prefs.edit().putLong(KEY_PHOTO_TRIED, now).apply();
        AccountManager am = AccountManager.get(activity);
        Account account = null;
        for (Account a : am.getAccountsByType(GOOGLE)) if (a.name.equals(name)) account = a;
        if (account == null) return;
        AccountManagerCallback<Bundle> done = future -> io.execute(() -> {
            try {
                String token = future.getResult().getString(AccountManager.KEY_AUTHTOKEN);
                if (token == null) return; // consent still needed (only shown when asked)
                if (!download(token)) {
                    am.invalidateAuthToken(GOOGLE, token); // expired: a fresh one next time
                    return;
                }
                Bitmap round = shown(name, picture());
                activity.runOnUiThread(() -> onAvatar.accept(round));
            } catch (OperationCanceledException | AuthenticatorException | IOException | RuntimeException e) {
                // Typically "UnregisteredOnApiConsole" until the app has an OAuth client.
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
     * Reads the photo's address from Google's userinfo, then saves the photo (or removes the old one
     * if there's none now). False if the token was refused. IO thread.
     */
    private boolean download(String token) throws IOException {
        String picture;
        HttpURLConnection http = (HttpURLConnection) new URL(USERINFO).openConnection();
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
        // Google serves 96px by default; ask for the size it's shown at. The address is checked on the
        // very URL that's fetched, so no difference between two parsers can slip past it.
        URL url = new URL(picture.replaceAll("=s\\d+(-c)?$", "") + "=s" + Math.max(128, size * 2) + "-c");
        String host = url.getHost();
        if (!"https".equals(url.getProtocol()) || host == null || !host.endsWith(".googleusercontent.com")) {
            throw new IOException("unexpected photo address");
        }
        http = (HttpURLConnection) url.openConnection();
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

    /** The whole stream, refusing anything over {@code max} bytes. */
    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8 << 10];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (out.size() + n > max) throw new IOException("too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Decodes the cached photo straight to about the size it's shown at, then rounds it. */
    private static Bitmap decodeCircle(File f, int size) {
        if (f.length() <= 0 || f.length() > MAX_FILE) return null;
        try {
            Bitmap src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); // read by a shader on a software canvas
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                // The circle shows the middle square, so the short side sets the scale; the long side
                // is capped too, or a thin strip of a picture could still decode to hundreds of MB.
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

    /** Whether an account has been chosen for the profile picture. */
    boolean hasAccount() {
        return prefs.getString(KEY_ACCOUNT, null) != null;
    }

    /** Shows Android's account picker; the result arrives in {@link #onActivityResult}. */
    void choose() {
        Intent pick = AccountManager.newChooseAccountIntent(null, null, new String[]{GOOGLE}, null, null, null, null);
        // Google TV routes this to a Play services picker that hands off to the stock launcher (which
        // we replace), so use Android's own chooser instead.
        pick.setComponent(new ComponentName("android", "android.accounts.ChooseTypeAndAccountActivity"));
        try {
            activity.startActivityForResult(pick, REQUEST_ACCOUNT);
        } catch (RuntimeException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    void onActivityResult(int resultCode, Intent data, Consumer<Bitmap> onAvatar) {
        String name = data != null ? data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) : null;
        if (resultCode != Activity.RESULT_OK || name == null) return;
        // A new pick also retries the photo now (say, after setting up the OAuth client).
        prefs.edit().putString(KEY_ACCOUNT, name).remove(KEY_PHOTO_TRIED).apply();
        io.execute(photo::delete); // before load() reads it: the executor is single-threaded
        load(onAvatar);
        refresh(onAvatar, true);
    }

    /** A round, centre-cropped copy at {@code size} px. */
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

    /** Google-style letter avatar: the account's initial on a colour picked from the name. */
    private static Bitmap monogram(String account, int size) {
        int[] colors = {0xFF1A73E8, 0xFFD93025, 0xFF188038, 0xFFE37400, 0xFF9334E6, 0xFF007B83};
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colors[Math.floorMod(account.hashCode(), colors.length)]);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        p.setTextSize(size * 0.5f);
        p.setTextAlign(Paint.Align.CENTER);
        String letter = account.isEmpty() ? "?" : account.substring(0, 1).toUpperCase(Locale.ROOT);
        c.drawText(letter, size / 2f, size / 2f - (p.descent() + p.ascent()) / 2f, p);
        return out;
    }
}
