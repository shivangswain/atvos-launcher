package app.atvos.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Locale;

/**
 * Draws the pictures in setup: the Settings screens of the TV, with the model, version and build of this TV. Google TV
 * and Android TV each have their own layout, in 1920×1080 units. Each picture shows the screen title, some rows and the
 * row to select, enlarged.
 */
final class SettingsArt {

    static final int ABOUT = 0, DEVELOPER = 1, PROMPT = 2, ACCESSIBILITY = 3;

    // Google TV: two panes. The focused row is a light card.
    private static final int GTV_LIST = 0xFF1E232C, GTV_PREVIEW = 0xFF222730, GTV_TEXT = 0xFFE8EAED,
            GTV_SUMMARY = 0xFFA8ACB2, GTV_CARD = 0xFFE8EAED, GTV_CARD_TEXT = 0xFF202124, GTV_CARD_SUMMARY = 0xFF5F6368,
            GTV_HEADER = 0xFF9AA0A6, GTV_BLUE = 0xFF3A75E2, GTV_ON_TRACK = 0xFFCBD7EE,
            GTV_OFF_TRACK = 0xFF444B57, GTV_THUMB = 0xFFF5F5F5;
    // Android TV: a side panel over the dimmed home screen. The focused row is a lighter band.
    private static final int ATV_DIM = 0xFF050505, ATV_PANEL = 0xFF202124, ATV_HEAD = 0xFF1B1C1E, ATV_FOCUS = 0xFF2E2F32,
            ATV_TEXT = 0xFFEEEEEE, ATV_SUMMARY = 0xFFB5B5B6, ATV_BLUE = 0xFF4FA6FA,
            ATV_ON_TRACK = 0xFF385470, ATV_OFF_TRACK = 0xFF5F6062, ATV_THUMB = 0xFFB9B9B9;
    // The "Allow USB debugging?" prompt, the same on both.
    private static final int PROMPT_SCRIM = 0xFF0C0E12, PROMPT_BG = 0xFF1F232B, PROMPT_BUTTON = 0xFF353940,
            PROMPT_FOCUS = 0xFFE8EAED;
    /** The key fingerprint on the prompt before atvOS knows its own (the first prompt makes the key). */
    private static final String NO_FINGERPRINT = "··:··:··:··:··:··:··:··:··:··:··:··:··:··:··:··";

    private final boolean googleTv;
    private final String about, model, version, build, developer, colorMode, colorSummary, debugging, usbDebugging,
            usbSummary, revoke, promptTitle, promptKey, alwaysAllow, allow, cancel, times, accessibility, captions,
            highContrast, services, remoteButtons, off;
    private final String modelValue, versionValue, buildValue;
    private String fingerprint = NO_FINGERPRINT;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface regular = Typeface.create(Typeface.DEFAULT, 400, false),
            medium = Typeface.create(Typeface.DEFAULT, 500, false), bold = Typeface.create(Typeface.DEFAULT, 700, false),
            condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    private final RectF r = new RectF();
    private final Path clip = new Path();
    private float right; // the right edge of the text on the current screen

    SettingsArt(Context c) {
        googleTv = c.getPackageManager().hasSystemFeature("com.google.android.feature.AMATI_EXPERIENCE");
        about = c.getString(R.string.art_about);
        model = c.getString(R.string.art_model);
        version = c.getString(R.string.art_version);
        build = c.getString(R.string.art_build);
        developer = c.getString(R.string.art_developer);
        colorMode = c.getString(R.string.art_color_mode);
        colorSummary = c.getString(R.string.art_color_summary);
        debugging = c.getString(R.string.art_debugging);
        usbDebugging = c.getString(R.string.art_usb_debugging);
        usbSummary = c.getString(R.string.art_usb_summary);
        revoke = c.getString(R.string.art_revoke);
        promptTitle = c.getString(R.string.art_prompt_title);
        promptKey = c.getString(R.string.art_prompt_key);
        alwaysAllow = c.getString(R.string.art_always_allow);
        allow = c.getString(R.string.art_allow);
        cancel = c.getString(R.string.cancel);
        times = c.getString(R.string.art_times);
        accessibility = c.getString(R.string.art_accessibility);
        captions = c.getString(R.string.art_captions);
        highContrast = c.getString(R.string.art_high_contrast);
        services = c.getString(R.string.art_services);
        remoteButtons = c.getString(R.string.remote_keys_label);
        off = c.getString(R.string.off);
        modelValue = Build.MODEL;
        versionValue = Build.VERSION.RELEASE;
        buildValue = Build.DISPLAY;
    }

    /** Shows {@code code}, the fingerprint of the key of atvOS, on the picture of the prompt (null: the placeholder). */
    void fingerprint(String code) {
        fingerprint = code != null ? code : NO_FINGERPRINT;
    }

    /** Draws the picture {@code kind} into {@code box} with rounded corners, at {@code alpha}. */
    void draw(Canvas c, int kind, RectF box, float radius, int alpha) {
        c.save();
        clip.rewind();
        clip.addRoundRect(box, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        c.translate(box.left, box.top);
        // Enlarges the important area: the Google TV list, the Android TV side panel, or the prompt dialog.
        float vx = kind == PROMPT ? 404f : googleTv ? 180f : 924f, vy = kind == PROMPT ? 224f : googleTv ? 90f : 16f;
        float vw = kind == PROMPT ? 1110f : 996f;
        float k = box.width() / vw;
        c.scale(k, k);
        c.translate(-vx, -vy);
        int layer = alpha < 253 ? c.saveLayerAlpha(0f, 0f, 1920f, 1080f, alpha) : -1;
        switch (kind) {
            case ABOUT -> {
                if (googleTv) gtvAbout(c); else atvAbout(c);
            }
            case DEVELOPER -> {
                if (googleTv) gtvDeveloper(c); else atvDeveloper(c);
            }
            case ACCESSIBILITY -> {
                if (googleTv) gtvAccessibility(c); else atvAccessibility(c);
            }
            default -> prompt(c);
        }
        if (layer >= 0) c.restoreToCount(layer);
        c.restore();
    }

    // Google TV

    private void gtvPanes(Canvas c, String title) {
        right = 880f;
        rect(c, 0f, 0f, 1120f, 1080f, GTV_LIST);
        rect(c, 1120f, 0f, 1920f, 1080f, GTV_PREVIEW);
        draw(c, title, 260f, 186f, 62f, regular, GTV_TEXT);
    }

    private void gtvRow(Canvas c, String title, String summary, float baseline, int color) {
        draw(c, title, 260f, baseline, 30f, regular, color);
        if (summary != null) draw(c, summary, 260f, baseline + 44f, 24f, regular, color == GTV_TEXT ? GTV_SUMMARY : color);
    }

    /** Draws the focused row: a light, lifted card with dark text. */
    private void gtvCard(Canvas c, float top, float bottom, String title, String summary) {
        fill.setColor(0x40000000);
        r.set(209f, top + 8f, 913f, bottom + 10f);
        c.drawRoundRect(r, 16f, 16f, fill);
        r.set(205f, top, 917f, bottom);
        fill.setColor(GTV_CARD);
        c.drawRoundRect(r, 16f, 16f, fill);
        float mid = (top + bottom) / 2f;
        draw(c, title, 245f, summary != null ? mid - 8f : mid + 11f, 30f, regular, GTV_CARD_TEXT);
        if (summary != null) draw(c, summary, 245f, mid + 36f, 24f, regular, GTV_CARD_SUMMARY);
    }

    private void gtvAbout(Canvas c) {
        gtvPanes(c, about);
        gtvRow(c, model, modelValue, 290f, GTV_TEXT);
        gtvRow(c, version, versionValue, 420f, GTV_TEXT);
        gtvCard(c, 500f, 625f, build, buildValue);
        badge(c, 846f, 562f, GTV_BLUE);
    }

    private void gtvDeveloper(Canvas c) {
        gtvPanes(c, developer);
        gtvRow(c, colorMode, colorSummary, 290f, GTV_TEXT);
        gtvSwitch(c, 299f, false, false);
        text.setLetterSpacing(0.06f);
        draw(c, debugging.toUpperCase(Locale.getDefault()), 260f, 412f, 20f, medium, GTV_HEADER);
        text.setLetterSpacing(0f);
        gtvCard(c, 430f, 556f, usbDebugging, usbSummary);
        gtvSwitch(c, 493f, true, true);
        gtvRow(c, revoke, null, 640f, GTV_TEXT);
    }

    /** Accessibility: the Services section with Remote Buttons focused and off. */
    private void gtvAccessibility(Canvas c) {
        gtvPanes(c, accessibility);
        gtvRow(c, captions, null, 300f, GTV_TEXT);
        gtvRow(c, highContrast, null, 380f, GTV_TEXT);
        gtvSwitch(c, 370f, false, false);
        text.setLetterSpacing(0.06f);
        draw(c, services.toUpperCase(Locale.getDefault()), 260f, 452f, 20f, medium, GTV_HEADER);
        text.setLetterSpacing(0f);
        gtvCard(c, 470f, 596f, remoteButtons, off);
    }

    private void gtvSwitch(Canvas c, float cy, boolean on, boolean onCard) {
        r.set(784f, cy - 14f, 846f, cy + 14f);
        fill.setColor(on ? GTV_ON_TRACK : onCard ? 0xFFB9BEC6 : GTV_OFF_TRACK);
        c.drawRoundRect(r, 14f, 14f, fill);
        fill.setColor(on ? GTV_BLUE : GTV_THUMB);
        c.drawCircle(on ? 846f : 792f, cy, 18f, fill);
    }

    // Android TV

    private void atvPanel(Canvas c, String title) {
        right = 1700f;
        rect(c, 0f, 0f, 1200f, 1080f, ATV_DIM);
        // The row of apps of the home screen behind the panel, dimmed.
        fill.setColor(0xFF141517);
        for (int i = 0; i < 2; i++) {
            r.set(960f + i * 140f, 300f, 1080f + i * 140f, 380f);
            c.drawRoundRect(r, 10f, 10f, fill);
        }
        rect(c, 1200f, 0f, 1920f, 1080f, ATV_PANEL);
        rect(c, 1200f, 0f, 1920f, 186f, ATV_HEAD);
        draw(c, title, 1248f, 134f, 40f, bold, ATV_TEXT);
    }

    private void atvRow(Canvas c, String title, String summary, float baseline, int color) {
        draw(c, title, 1248f, baseline, 30f, regular, color);
        if (summary != null) draw(c, summary, 1248f, baseline + 42f, 23f, condensed, color == ATV_TEXT ? ATV_SUMMARY : color);
    }

    private void atvFocus(Canvas c, float top, float bottom) {
        rect(c, 1200f, top, 1920f, bottom, ATV_FOCUS);
    }

    private void atvAbout(Canvas c) {
        atvPanel(c, about);
        atvRow(c, model, modelValue, 250f, ATV_TEXT);
        atvRow(c, version, versionValue, 379f, ATV_TEXT);
        atvFocus(c, 442f, 570f);
        atvRow(c, build, buildValue, 500f, ATV_TEXT);
        badge(c, 1840f, 506f, ATV_BLUE);
    }

    private void atvDeveloper(Canvas c) {
        atvPanel(c, developer);
        atvRow(c, colorMode, colorSummary, 250f, ATV_TEXT);
        atvSwitch(c, 253f, false);
        draw(c, debugging, 1248f, 362f, 26f, medium, ATV_BLUE);
        atvFocus(c, 391f, 520f);
        atvRow(c, usbDebugging, usbSummary, 452f, ATV_TEXT);
        atvSwitch(c, 454f, true);
        atvRow(c, revoke, null, 578f, ATV_TEXT);
    }

    private void atvAccessibility(Canvas c) {
        atvPanel(c, accessibility);
        atvRow(c, captions, null, 250f, ATV_TEXT);
        atvRow(c, highContrast, null, 330f, ATV_TEXT);
        atvSwitch(c, 320f, false);
        draw(c, services, 1248f, 422f, 26f, medium, ATV_BLUE);
        atvFocus(c, 451f, 580f);
        atvRow(c, remoteButtons, off, 512f, ATV_TEXT);
    }

    private void atvSwitch(Canvas c, float cy, boolean on) {
        r.set(1736f, cy - 12f, 1796f, cy + 12f);
        fill.setColor(on ? ATV_ON_TRACK : ATV_OFF_TRACK);
        c.drawRoundRect(r, 12f, 12f, fill);
        fill.setColor(on ? ATV_BLUE : ATV_THUMB);
        c.drawCircle(on ? 1782f : 1740f, cy, 18f, fill);
    }

    // The debugging prompt

    /**
     * Draws the prompt with the fingerprint of the key of atvOS (once known), "Always allow" not selected, and Allow
     * focused. atvOS uses the debugging connection once, so the key does not need to stay allowed.
     */
    private void prompt(Canvas c) {
        right = 1420f;
        rect(c, 0f, 0f, 1920f, 1080f, PROMPT_SCRIM);
        r.set(464f, 304f, 1454f, 774f);
        fill.setColor(PROMPT_BG);
        c.drawRoundRect(r, 18f, 18f, fill);
        draw(c, promptTitle, 512f, 384f, 40f, bold, GTV_TEXT);
        draw(c, promptKey, 512f, 432f, 31f, regular, GTV_TEXT);
        draw(c, fingerprint, 512f, 470f, 31f, regular, GTV_TEXT);
        r.set(512f, 510f, 534f, 532f); // an empty check box
        fill.setColor(GTV_SUMMARY);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(2.5f);
        c.drawRoundRect(r, 3f, 3f, fill);
        fill.setStyle(Paint.Style.FILL);
        draw(c, alwaysAllow, 556f, 532f, 30f, regular, GTV_TEXT);
        button(c, 514f, 686f, allow, true);
        button(c, 708f, 880f, cancel, false);
    }

    private void button(Canvas c, float left, float right, String label, boolean focused) {
        r.set(left, 636f, right, 728f);
        fill.setColor(focused ? PROMPT_FOCUS : PROMPT_BUTTON);
        c.drawRoundRect(r, 46f, 46f, fill);
        drawCentred(c, label, (left + right) / 2f, 692f, right - left - 24f, 28f, medium, focused ? GTV_CARD_TEXT : GTV_TEXT);
    }

    // Helpers

    /** Draws "7×" in the accent colour of Settings: the number of times to select the row. */
    private void badge(Canvas c, float cx, float cy, int color) {
        fill.setColor(color);
        c.drawCircle(cx, cy, 34f, fill);
        drawCentred(c, times, cx, cy + 10f, 60f, 28f, bold, Color.WHITE);
    }

    private void rect(Canvas c, float l, float t, float rr, float b, int color) {
        fill.setColor(color);
        c.drawRect(l, t, rr, b, fill);
    }

    /** Draws text, cut at the right edge of the row. */
    private void draw(Canvas c, String s, float x, float baseline, float size, Typeface face, int color) {
        fit(c, s, x, baseline, right - x, size, face, color);
    }

    /** Draws text centred on {@code cx}, cut at {@code width}. */
    private void drawCentred(Canvas c, String s, float cx, float baseline, float width, float size, Typeface face, int color) {
        text.setTextAlign(Paint.Align.CENTER);
        fit(c, s, cx, baseline, width, size, face, color);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void fit(Canvas c, String s, float x, float baseline, float room, float size, Typeface face, int color) {
        text.setTypeface(face);
        text.setTextSize(size);
        text.setColor(color);
        CharSequence fitted = text.measureText(s) <= room ? s : TextUtils.ellipsize(s, text, room, TextUtils.TruncateAt.END);
        c.drawText(fitted, 0, fitted.length(), x, baseline, text);
    }
}
