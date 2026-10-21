package app.atvos.launcher;

import android.animation.ValueAnimator;
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
 * The Control Center: glass tiles below the status row. This class lays out the tiles, moves the focus and draws the
 * tiles. {@link HomeView} sends the keys, and {@link Controls} does the actions.
 * <pre>
 *   9:41 AM            ☾ ⏱  (≡) (⚙) (S)
 *   [ Power ]  [ Wi-Fi     ]
 *   [  Off  ]  [ DND       ]
 *   [ Bluetooth ] [ Sleep  ]
 *   (In) (Acc) (System) (Find)
 * </pre>
 * (≡) opens atvOS Settings. (⚙) opens the Settings app of the TV.
 */
final class ControlCenter {

    interface Glass {
        void draw(Canvas c, RectF rect, float radius, int tint, int alpha);
    }

    static final int PROFILE = 0, SETTINGS = 1, POWER = 2, WIFI = 3, DND = 4, BLUETOOTH = 5, SLEEP = 6,
            INPUTS = 7, ACCESSIBILITY = 8, SYSTEM = 9, SEARCH = 10, LAUNCHER_SETTINGS = 11;
    static final int COUNT = 12;
    /**
     * The tiles in the order for screen readers: the status row, then the grid from left to right and from top to
     * bottom.
     */
    static final int[] READING_ORDER = {LAUNCHER_SETTINGS, SETTINGS, PROFILE, POWER, WIFI, DND, BLUETOOTH, SLEEP,
            INPUTS, ACCESSIBILITY, SYSTEM, SEARCH};

    // The grid: a circle is as tall as a pill, and two circles fill one pill column.
    private static final float ROW_H = 92f, GAP = 20f, COL_W = 2 * ROW_H + GAP, TOP = 134f, SETTINGS_D = 50f;
    /** The width of the tile block and of the status row. */
    static final float WIDTH = 2 * COL_W + GAP;
    private static final float LINE_1 = 23f, LINE_2 = 21f, TEXT_ROOM = COL_W - 88f;
    private static final int WHITE = Color.WHITE, DARK = 0xFF1C1C1E;
    private static final int CYAN = 0xFF40C8F4, INDIGO = 0xFF8E8CFF, ORANGE = 0xFFFF9F0A;

    private final RectF[] rects = new RectF[COUNT];
    private final float[] radii = new float[COUNT];
    private final float[] focus = new float[COUNT];
    private final float cogSize; // the size of the two Settings icons
    private final Drawable power, wifi, ethernet, moon, bluetooth, headphones, timer, input, accessibility,
            system, search, settings, switches;
    private final String powerOff, wifiLabel, ethernetLabel, connected, notConnected, dndLabel,
            bluetoothLabel, on, off, sleepTimerLabel, sleepLabel, minutes, profileLabel, settingsLabel, inputsLabel,
            accessibilityLabel, systemLabel, searchLabel, launcherSettingsLabel, notAvailable;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    // Values from the state, calculated once for each change: the tile text, fitted to the tile width.
    private Controls.State state = new Controls.State();
    private String wifi1 = "", wifi2 = "", bt1 = "", bt2 = "", dnd1 = "", dnd2 = "", sleep1 = "", sleep2 = "";
    private int idleInk = WHITE;

    int selected = POWER;

    /**
     * {@code right}: the right edge on the screen. {@code avatar}: the bounds of the profile avatar that the clock
     * draws.
     */
    ControlCenter(Context context, float right, RectF avatar) {
        float rx = right - COL_W, lx = right - WIDTH;
        // The status row from right to left: the profile, the Settings of the TV, then atvOS Settings, with the same
        // visible gap between them. The glyph of each icon fills 20 of its 24 units.
        cogSize = avatar.width() * 0.95f; // slightly smaller than the avatar
        float glyph = cogSize * 20f / 24f, gap = 24f;
        float cy = avatar.centerY(), sx = avatar.left - gap - glyph / 2f, qx = sx - glyph - gap;
        rects[PROFILE] = new RectF(avatar);
        rects[SETTINGS] = new RectF(sx - SETTINGS_D / 2f, cy - SETTINGS_D / 2f, sx + SETTINGS_D / 2f, cy + SETTINGS_D / 2f);
        rects[LAUNCHER_SETTINGS] = new RectF(qx - SETTINGS_D / 2f, cy - SETTINGS_D / 2f, qx + SETTINGS_D / 2f, cy + SETTINGS_D / 2f);
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
        switches = icon(context, R.drawable.ic_switches);

        powerOff = context.getString(R.string.power_off);
        wifiLabel = context.getString(R.string.wifi);
        ethernetLabel = context.getString(R.string.ethernet);
        connected = context.getString(R.string.connected);
        notConnected = context.getString(R.string.not_connected);
        dndLabel = context.getString(R.string.dnd);
        bluetoothLabel = context.getString(R.string.bluetooth);
        on = context.getString(R.string.on);
        off = context.getString(R.string.off);
        sleepTimerLabel = context.getString(R.string.sleep_timer);
        sleepLabel = context.getString(R.string.sleep);
        profileLabel = context.getString(R.string.a11y_profile);
        settingsLabel = context.getString(R.string.a11y_settings);
        inputsLabel = context.getString(R.string.a11y_inputs);
        accessibilityLabel = context.getString(R.string.a11y_accessibility);
        systemLabel = context.getString(R.string.a11y_system);
        searchLabel = context.getString(R.string.a11y_search);
        launcherSettingsLabel = context.getString(R.string.a11y_launcher_settings);
        minutes = context.getString(R.string.minutes);
        notAvailable = context.getString(R.string.not_available);

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

    /** Sets the system state and fits the tile text to the tiles. The draw then allocates no memory. */
    void setState(Controls.State s) {
        state = s;
        wifi1 = fit(s.ethernet ? ethernetLabel : wifiLabel, LINE_1);
        wifi2 = fit(s.online ? connected : notConnected, LINE_2);
        bt1 = fit(bluetoothLabel, LINE_1);
        bt2 = fit(s.bluetoothDevice != null ? s.bluetoothDevice : s.bluetoothOn ? on : off, LINE_2);
        String[] dnd = wrap(dndLabel, LINE_1);
        dnd1 = dnd[0];
        dnd2 = dnd[1];
        if (s.sleepAt > 0) {
            sleep1 = fit(sleepLabel, LINE_1);
            sleep2 = fit(String.format(Locale.getDefault(), minutes,
                    Math.max(1, (s.sleepAt - SystemClock.elapsedRealtime() + 59_999) / 60_000)), LINE_2);
        } else {
            String[] timer = wrap(sleepTimerLabel, LINE_2);
            sleep1 = timer[0];
            sleep2 = timer[1];
        }
    }

    /**
     * Divides a title into two lines: all of it on the first line if it fits, else a break at the last space that fits.
     * Text with no space that does not fit is shortened.
     */
    private String[] wrap(String s, float secondLineSize) {
        text.setTextSize(LINE_1);
        if (text.measureText(s) <= TEXT_ROOM) return new String[]{s, ""};
        int cut = -1;
        for (int i = s.indexOf(' '); i > 0; i = s.indexOf(' ', i + 1)) {
            if (text.measureText(s, 0, i) > TEXT_ROOM) break;
            cut = i;
        }
        if (cut < 0) return new String[]{fit(s, LINE_1), ""};
        return new String[]{s.substring(0, cut), fit(s.substring(cut + 1).trim(), secondLineSize)};
    }

    private String fit(String s, float size) {
        text.setTextSize(size);
        return TextUtils.ellipsize(s, text, TEXT_ROOM, TextUtils.TruncateAt.END).toString();
    }

    /**
     * Moves the focus in the direction (dx, dy) to the nearest item past the edge of the current item. Items in line
     * with the current item have priority. Returns true if the focus moved.
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

    /** Returns the bounds of a tile in design units. */
    RectF bounds(int id) {
        return rects[id];
    }

    /** Returns the name of a tile for a screen reader. */
    String label(int id) {
        return switch (id) {
            case PROFILE -> profileLabel;
            case SETTINGS -> settingsLabel;
            case POWER -> powerOff;
            case WIFI -> state.ethernet ? ethernetLabel : wifiLabel;
            case DND -> dndLabel;
            case BLUETOOTH -> bluetoothLabel;
            case SLEEP -> sleepTimerLabel;
            case INPUTS -> inputsLabel;
            case ACCESSIBILITY -> accessibilityLabel;
            case SYSTEM -> systemLabel;
            case LAUNCHER_SETTINGS -> launcherSettingsLabel;
            default -> searchLabel;
        };
    }

    /** Returns the state of a tile (for example, Connected, On or 15 min), or null for a tile with no state. */
    String state(int id) {
        Controls.State s = state;
        return switch (id) {
            case WIFI -> s.online ? connected : notConnected;
            case DND -> !s.dndAvailable ? notAvailable : s.dnd ? on : off;
            case BLUETOOTH ->
                    s.bluetoothDevice != null ? s.bluetoothDevice : s.bluetoothOn ? on : off;
            case SLEEP -> s.sleepAt > 0 ? sleep2 : off;
            default -> null;
        };
    }

    /** Moves the focus highlights forward by {@code dt}. Returns true while a highlight moves. */
    boolean step(float dt) {
        boolean moving = false;
        float k = ValueAnimator.areAnimatorsEnabled() ? 1f - (float) Math.exp(-16f * dt) : 1f; // 1 when animations are off
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

    /** {@code ink}: 0 for white text on the glass, 1 for dark text on a bright backdrop. */
    void draw(Canvas c, Glass glass, float appear, float ink) {
        int alpha = Math.round(255 * appear);
        if (alpha <= 0) return;
        idleInk = towardDark(WHITE, ink);

        // The clock draws the profile avatar. A focused avatar gets a ring.
        if (focus[PROFILE] > 0.01f) {
            RectF a = rects[PROFILE];
            ring.setColor(idleInk);
            ring.setAlpha(Math.round(alpha * focus[PROFILE]));
            c.drawCircle(a.centerX(), a.centerY(), a.width() / 2f + 5f, ring);
        }
        tile(c, glass, LAUNCHER_SETTINGS, alpha, switches, 0, null, null);
        tile(c, glass, SETTINGS, alpha, settings, 0, null, null);

        c.save();
        c.translate(0f, (1f - appear) * -18f);
        Controls.State s = state;
        tile(c, glass, POWER, alpha, power, 0, null, null);
        tile(c, glass, WIFI, alpha, s.ethernet ? ethernet : wifi, s.online ? CYAN : 0, wifi1, wifi2);
        // A tile that cannot work on this TV is dimmed, as tvOS dims a row that is not available.
        tile(c, glass, DND, s.dndAvailable ? alpha : Math.round(alpha * 0.45f), moon, s.dnd ? INDIGO : 0, dnd1, dnd2);
        tile(c, glass, BLUETOOTH, alpha, s.bluetoothDevice != null ? headphones : bluetooth,
                s.bluetoothOn ? CYAN : 0, bt1, bt2);
        tile(c, glass, SLEEP, alpha, timer, s.sleepAt > 0 ? ORANGE : 0, sleep1, sleep2);
        tile(c, glass, INPUTS, alpha, input, 0, null, null);
        tile(c, glass, ACCESSIBILITY, alpha, accessibility, 0, null, null);
        tile(c, glass, SYSTEM, alpha, system, 0, null, null);
        tile(c, glass, SEARCH, alpha, search, 0, null, null);
        c.restore();
    }

    /**
     * Draws the status glyphs to the left of the status row buttons: Bluetooth audio, Do Not Disturb and the sleep
     * timer, when they are on. No glyph goes to the left of {@code minX}. The clock draws the time and the avatar.
     */
    void drawStatus(Canvas c, float cy, int alpha, float minX) {
        float size = 28f, x = rects[LAUNCHER_SETTINGS].left - 24f - size;
        Controls.State s = state;
        if (s.sleepAt > 0 && x >= minX) {
            drawIcon(c, timer, x, cy - size / 2f, size, WHITE, alpha);
            x -= size + 16f;
        }
        if (s.dnd && x >= minX) {
            drawIcon(c, moon, x, cy - size / 2f, size, WHITE, alpha);
            x -= size + 16f;
        }
        if (s.bluetoothDevice != null && x >= minX) drawIcon(c, headphones, x, cy - size / 2f, size, WHITE, alpha);
    }

    /** Draws one tile: glass when not focused, white when focused. An accent colour shows that it is on. */
    private void tile(Canvas c, Glass glass, int id, int alpha, Drawable icon, int accent, String line1, String line2) {
        float f = focus[id], grow = 1f + 0.06f * f;
        RectF base = rects[id];
        float w = base.width() * grow, h = base.height() * grow;
        r.set(base.centerX() - w / 2f, base.centerY() - h / 2f, base.centerX() + w / 2f, base.centerY() + h / 2f);
        float radius = radii[id] * grow;

        // The buttons of the status row have no glass when they are not focused.
        boolean bare = id == SETTINGS || id == LAUNCHER_SETTINGS;
        if (f < 0.99f && !bare) glass.draw(c, r, radius, 0x24FFFFFF, Math.round(alpha * (1f - f)));
        if (f > 0.01f) {
            fill.setColor(Color.argb(Math.round(alpha * f * 0.96f), 255, 255, 255));
            c.drawRoundRect(r, radius, radius, fill);
        }
        int ink = towardDark(idleInk, f);
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
            float size = bare ? (id == LAUNCHER_SETTINGS ? cogSize - 1f : cogSize) * grow : r.height() * 0.46f; // switches icon: 1 pt smaller
            drawIcon(c, icon, r.centerX() - size / 2f, r.centerY() - size / 2f, size, iconColor, alpha);
        } else {
            float size = 36f * grow, x = r.left + 24f * grow;
            drawIcon(c, icon, x, r.centerY() - size / 2f, size, iconColor, alpha);
            float tx = x + size + 14f * grow;
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(LINE_1 * grow);
            text.setColor(ink);
            text.setAlpha(alpha);
            if (line2.isEmpty()) { // a one-line title is centred vertically
                c.drawText(line1, tx, r.centerY() + 8f * grow, text);
                return;
            }
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

    /** Returns {@code a} moved by the fraction {@code t} toward the dark ink of a focused tile. */
    private static int towardDark(int a, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(DARK) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(DARK) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(DARK) - Color.blue(a)) * t));
    }
}
