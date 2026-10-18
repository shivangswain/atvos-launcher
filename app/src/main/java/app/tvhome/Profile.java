package app.tvhome;

import android.accounts.AccountManager;
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
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The profile picture in the clock pill: the signed-in Google account's letter avatar (what Google
 * shows for accounts without a photo), or files/avatar.png if you push one. The account is picked
 * once with Android's own chooser; nothing is fetched from the network.
 */
final class Profile {

    static final int REQUEST_ACCOUNT = 7;
    private static final String GOOGLE = "com.google";
    private static final String KEY_ACCOUNT = "account";

    private final Activity activity;
    private final Executor io;
    private final SharedPreferences prefs;
    private final File file;
    private final int size;

    Profile(Activity activity, Executor io, int sizePx) {
        this.activity = activity;
        this.io = io;
        this.prefs = activity.getSharedPreferences("home", Context.MODE_PRIVATE);
        File external = activity.getExternalFilesDir(null);
        this.file = new File(external != null ? external : activity.getFilesDir(), "avatar.png");
        this.size = sizePx;
    }

    /** avatar.png if present, else the chosen account's monogram; nothing if no account was chosen. */
    void load(Consumer<Bitmap> onAvatar) {
        String account = prefs.getString(KEY_ACCOUNT, null);
        io.execute(() -> {
            Bitmap shown = file.isFile() ? decodeCircle(file, size) : null;
            if (shown == null && account != null) shown = monogram(account, size);
            Bitmap result = shown;
            if (result != null) activity.runOnUiThread(() -> onAvatar.accept(result));
        });
    }

    /** Decodes avatar.png straight to about the size it's shown at, then rounds it. */
    private static Bitmap decodeCircle(File f, int size) {
        try {
            Bitmap src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); // read by a shader on a software canvas
                int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                if (w > size * 2 && h > size * 2) {
                    float k = (float) size / Math.min(w, h);
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
        prefs.edit().putString(KEY_ACCOUNT, name).apply();
        load(onAvatar);
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
