package app.atvos.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Locale;

/**
 * The tvOS-style Control Center: glass tiles that drop down under the status row. This class owns
 * the layout, focus movement and drawing; {@link HomeView} owns input routing and {@link Controls}
 * does the actual work.
 *
 *   9:41 AM               ☾ ⏱  (⚙) (S)
 *   [ Power ]  [ Wi-Fi     ]
 *   [  Off  ]  [ DND       ]
 *   [ Bluetooth ] [ Sleep  ]
 *   (In) (Acc) (System) (Find)
 */
final class ControlCenter {

    interface Glass {
        void draw(Canvas c, RectF rect, float radius, int tint, int alpha);
    }

    static final int PROFILE = 0, SETTINGS = 1, POWER = 2, WIFI = 3, DND = 4, BLUETOOTH = 5, SLEEP = 6,
            INPUTS = 7, ACCESSIBILITY = 8, SYSTEM = 9, SEARCH = 10;
    private static final int COUNT = 11;

    // One grid, as on tvOS: circles as tall as the pills, two circles to a pill column.
    private static final float ROW_H = 92f, GAP = 20f, COL_W = 2 * ROW_H + GAP, TOP = 134f, SETTINGS_D = 50f;
    /** Width of the tile block, also the span of the top status row. */
    static final float WIDTH = 2 * COL_W + GAP;
    private static final float LINE_1 = 23f, LINE_2 = 21f, TEXT_ROOM = COL_W - 88f;
    private static final int WHITE = Color.WHITE, DARK = 0xFF1C1C1E;
    private static final int CYAN = 0xFF40C8F4, INDIGO = 0xFF8E8CFF, ORANGE = 0xFFFF9F0A;

    private final RectF[] rects = new RectF[COUNT];
    private final float[] radii = new float[COUNT];
    private final float[] focus = new float[COUNT];
    private final float cogSize; // the Settings cog, a little smaller than the profile avatar beside it
    private final Drawable power, wifi, ethernet, moon, bluetooth, headphones, timer, input, accessibility,
            system, search, settings;
    private final String powerOff, wifiLabel, ethernetLabel, connected, notConnected, dnd1, dnd2,
            bluetoothLabel, on, off, sleep1, sleep2, minutes;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    // Derived from the state once per change (not per frame): tile text already fitted to its width.
    private Controls.State state = new Controls.State();
    private String wifi1 = "", wifi2 = "", bt1 = "", bt2 = "", sleepLine = "";
    private int idleInk = WHITE;

    int selected = POWER;

    /** {@code right} is the screen-space right edge to align to; {@code avatar} is where the clock draws the profile avatar. */
    ControlCenter(Context context, float right, RectF avatar) {
        float rx = right - COL_W, lx = right - WIDTH;
        float cy = avatar.centerY(), sx = avatar.left - 18f - SETTINGS_D / 2f;
        rects[PROFILE] = new RectF(avatar);
        rects[SETTINGS] = new RectF(sx - SETTINGS_D / 2f, cy - SETTINGS_D / 2f, sx + SETTINGS_D / 2f, cy + SETTINGS_D / 2f);
        rects[POWER] = new RectF(lx, TOP, lx + COL_W, TOP + 2 * ROW_H + GAP);
        rects[WIFI] = pill(rx, 0);
        rects[DND] = pill(rx, 1);
        rects[BLUETOOTH] = pill(lx, 2);
        rects[SLEEP] = pill(rx, 2);
        int[] round = {INPUTS, ACCESSIBILITY, SYSTEM, SEARCH};
        float y = TOP + 3 * (ROW_H + GAP);
        for (int i = 0; i < round.length; i++) {
            float x = lx + i * (ROW_H + GAP);
            rects[round[i]] = new RectF(x, y, x + ROW_H, y + ROW_H);
        }
        for (int i = 0; i < COUNT; i++) radii[i] = rects[i].height() / 2f;
        radii[POWER] = 44f;
        cogSize = avatar.width() * 0.95f; // its glyph is 20 of the icon's 24 units, so a little smaller than the avatar

        power = icon(context, R.drawable.ic_power);
        wifi = icon(context, R.drawable.ic_wifi);
        ethernet = icon(context, R.drawable.ic_ethernet);
        moon = icon(context, R.drawable.ic_moon);
        bluetooth = icon(context, R.drawable.ic_bluetooth);
        headphones = icon(context, R.drawable.ic_headphones);
        timer = icon(context, R.drawable.ic_timer);
        input = icon(context, R.drawable.ic_input);
        accessibility = icon(context, R.drawable.ic_accessibility);
        system = icon(context, R.drawable.ic_tv);
        search = icon(context, R.drawable.ic_search);
        settings = icon(context, R.drawable.ic_settings);

        powerOff = context.getString(R.string.power_off);
        wifiLabel = context.getString(R.string.wifi);
        ethernetLabel = context.getString(R.string.ethernet);
        connected = context.getString(R.string.connected);
        notConnected = context.getString(R.string.not_connected);
        dnd1 = context.getString(R.string.dnd_1);
        dnd2 = context.getString(R.string.dnd_2);
        bluetoothLabel = context.getString(R.string.bluetooth);
        on = context.getString(R.string.on);
        off = context.getString(R.string.off);
        sleep1 = context.getString(R.string.sleep_1);
        sleep2 = context.getString(R.string.sleep_2);
        minutes = context.getString(R.string.minutes);

        text.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(4f);
        setState(state);
    }

    private static RectF pill(float x, int row) {
        float y = TOP + row * (ROW_H + GAP);
        return new RectF(x, y, x + COL_W, y + ROW_H);
    }

    private static Drawable icon(Context c, int res) {
        Drawable d = c.getDrawable(res);
        return d != null ? d.mutate() : null;
    }

    /** New system state: refit the tile text now so drawing allocates nothing. */
    void setState(Controls.State s) {
        state = s;
        wifi1 = fit(s.ethernet ? ethernetLabel : wifiLabel, LINE_1);
        wifi2 = fit(s.online ? connected : notConnected, LINE_2);
        bt1 = fit(bluetoothLabel, LINE_1);
        bt2 = fit(s.bluetoothDevice != null ? s.bluetoothDevice : s.bluetoothOn ? on : off, LINE_2);
        sleepLine = s.sleepAt > 0
                ? String.format(Locale.getDefault(), minutes, Math.max(1, (s.sleepAt - SystemClock.elapsedRealtime() + 59_999) / 60_000))
                : sleep2;
    }

    private String fit(String s, float size) {
        text.setTextSize(size);
        return TextUtils.ellipsize(s, text, TEXT_ROOM, TextUtils.TruncateAt.END).toString();
    }

    /**
     * Spatial focus: among items wholly past the current one's edge in the pressed direction, the
     * nearest, favouring ones in line with it. Returns whether focus moved.
     */
    boolean move(int dx, int dy) {
        RectF from = rects[selected];
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < COUNT; i++) {
            if (i == selected) continue;
            RectF to = rects[i];
            boolean beyond = dx > 0 ? to.left >= from.right - 4f
                    : dx < 0 ? to.right <= from.left + 4f
                    : dy > 0 ? to.top >= from.bottom - 4f
                    : to.bottom <= from.top + 4f;
            if (!beyond) continue;
            float ox = to.centerX() - from.centerX(), oy = to.centerY() - from.centerY();
            float along = ox * dx + oy * dy, across = Math.abs(dx != 0 ? oy : ox);
            if (along <= 1f) continue;
            float score = along + 2f * across;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) return false;
        selected = best;
        return true;
    }

    /** Advances focus highlights; returns true while anything is still moving. */
    boolean step(float dt) {
        boolean moving = false;
        float k = 1f - (float) Math.exp(-16f * dt);
        for (int i = 0; i < COUNT; i++) {
            float target = i == selected ? 1f : 0f, d = target - focus[i];
            if (Math.abs(d) <= 0.002f) {
                focus[i] = target;
            } else {
                focus[i] += d * k;
                moving = true;
            }
        }
        return moving;
    }

    /** {@code ink}: 0 for white text on the glass, 1 for dark text when the backdrop behind is bright. */
    void draw(Canvas c, Glass glass, float appear, float ink) {
        int alpha = Math.round(255 * appear);
        if (alpha <= 0) return;
        idleInk = blend(WHITE, DARK, ink);

        // The profile avatar is drawn by the clock; when focused it gets a ring, as on tvOS.
        if (focus[PROFILE] > 0.01f) {
            RectF a = rects[PROFILE];
            ring.setColor(idleInk);
            ring.setAlpha(Math.round(alpha * focus[PROFILE]));
            c.drawCircle(a.centerX(), a.centerY(), a.width() / 2f + 5f, ring);
        }
        tile(c, glass, SETTINGS, alpha, settings, 0, null, null);

        c.save();
        c.translate(0f, (1f - appear) * -18f);
        Controls.State s = state;
        tile(c, glass, POWER, alpha, power, 0, null, null);
        tile(c, glass, WIFI, alpha, s.ethernet ? ethernet : wifi, s.online ? CYAN : 0, wifi1, wifi2);
        tile(c, glass, DND, alpha, moon, s.dnd ? INDIGO : 0, dnd1, dnd2);
        tile(c, glass, BLUETOOTH, alpha, s.bluetoothDevice != null ? headphones : bluetooth,
                s.bluetoothOn ? CYAN : 0, bt1, bt2);
        tile(c, glass, SLEEP, alpha, timer, s.sleepAt > 0 ? ORANGE : 0, sleep1, sleepLine);
        tile(c, glass, INPUTS, alpha, input, 0, null, null);
        tile(c, glass, ACCESSIBILITY, alpha, accessibility, 0, null, null);
        tile(c, glass, SYSTEM, alpha, system, 0, null, null);
        tile(c, glass, SEARCH, alpha, search, 0, null, null);
        c.restore();
    }

    /**
     * Status glyphs left of the Settings button, as on tvOS: what's on (Bluetooth audio, Do Not
     * Disturb, sleep timer). The time and profile avatar are drawn by the clock.
     */
    void drawStatus(Canvas c, float cy, int alpha) {
        float size = 28f, x = rects[SETTINGS].left - 26f - size;
        Controls.State s = state;
        if (s.sleepAt > 0) {
            drawIcon(c, timer, x, cy - size / 2f, size, WHITE, alpha);
            x -= size + 18f;
        }
        if (s.dnd) {
            drawIcon(c, moon, x, cy - size / 2f, size, WHITE, alpha);
            x -= size + 18f;
        }
        if (s.bluetoothDevice != null) drawIcon(c, headphones, x, cy - size / 2f, size, WHITE, alpha);
    }

    /** One tile: glass when idle, white when focused; an accent colour marks "on". */
    private void tile(Canvas c, Glass glass, int id, int alpha, Drawable icon, int accent, String line1, String line2) {
        float f = focus[id], grow = 1f + 0.06f * f;
        RectF base = rects[id];
        float w = base.width() * grow, h = base.height() * grow;
        r.set(base.centerX() - w / 2f, base.centerY() - h / 2f, base.centerX() + w / 2f, base.centerY() + h / 2f);
        float radius = radii[id] * grow;

        // The Settings button in the status row has no glass: just its icon, until focused.
        if (f < 0.99f && id != SETTINGS) glass.draw(c, r, radius, 0x24FFFFFF, Math.round(alpha * (1f - f)));
        if (f > 0.01f) {
            fill.setColor(Color.argb(Math.round(alpha * f * 0.96f), 255, 255, 255));
            c.drawRoundRect(r, radius, radius, fill);
        }
        int ink = blend(idleInk, DARK, f);
        int iconColor = accent != 0 ? accent : ink;

        if (id == POWER) {
            float size = 88f * grow;
            drawIcon(c, icon, r.centerX() - size / 2f, r.top + 34f * grow, size, iconColor, alpha);
            text.setTextSize(25f * grow);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(ink);
            text.setAlpha(alpha);
            c.drawText(powerOff, r.centerX(), r.bottom - 34f * grow, text);
        } else if (line1 == null) {
            float size = id == SETTINGS ? cogSize * grow : r.height() * 0.46f;
            drawIcon(c, icon, r.centerX() - size / 2f, r.centerY() - size / 2f, size, iconColor, alpha);
        } else {
            float size = 36f * grow, x = r.left + 24f * grow;
            drawIcon(c, icon, x, r.centerY() - size / 2f, size, iconColor, alpha);
            float tx = x + size + 14f * grow;
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(LINE_1 * grow);
            text.setColor(ink);
            text.setAlpha(alpha);
            c.drawText(line1, tx, r.centerY() - 4f * grow, text);
            text.setTextSize((id == DND ? LINE_1 : LINE_2) * grow);
            text.setColor(ink);
            text.setAlpha(Math.round(alpha * (id == DND ? 1f : 0.62f)));
            c.drawText(line2, tx, r.centerY() + 24f * grow, text);
        }
    }

    private static void drawIcon(Canvas c, Drawable d, float x, float y, float size, int color, int alpha) {
        if (d == null) return;
        d.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size));
        d.setTint(color);
        d.setAlpha(alpha);
        d.draw(c);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
