package app.atvos.launcher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The whole home screen, drawn on one canvas. Everything is laid out in 1920-wide design units and
 * the canvas is scaled to the real width, so the layout is identical at any resolution.
 *
 * Animations are critically-damped exponential approaches evaluated per frame, so they can be
 * interrupted at any point and the view only redraws while something is still moving.
 */
@SuppressLint("ViewConstructor")
final class HomeView extends View {

    interface Host {
        void onFocusChanged(Apps.Entry entry, boolean inDock);
        void launch(Apps.Entry entry, Rect from);
        void showInfo(Apps.Entry entry);
        /** Persists the order and the hidden apps after Edit Home Screen. */
        void saveLayout(List<Apps.Entry> visible, List<Apps.Entry> hidden);
        void launchProgram(Recommendations.Item program, Rect from);
        /** The app's recommendation sources worth a menu switch, as {@link Recommendations#WATCH_NEXT} / {@link Recommendations#PREVIEWS} bits. */
        int shelfSources(Apps.Entry entry);
        boolean shelfSourceOn(Apps.Entry entry, int source);
        void setShelfSource(Apps.Entry entry, int source, boolean on);
        Controls.State controlState();
        /** Runs a Control Center action; returns true to keep the Control Center open. */
        boolean control(int id);
    }

    // Layout in design units, measured from the tvOS home screen.
    static final float W = 1920f;
    static final float TILE_W = 250f, TILE_H = 150f;
    static final float FOCUS_SCALE = 1.2f;
    private static final int COLS = 6;
    private static final float TILE_R = 22f;
    private static final float GAP_X = 47f, ROW_PITCH = 224f;
    private static final float GRID_X = (W - COLS * TILE_W - (COLS - 1) * GAP_X) / 2f;
    private static final float DOCK_ROW_Y = 800f;
    private static final float DOCK_PAD_X = 48f, DOCK_PAD_Y = 36f;
    private static final float EDGE = W - GRID_X + DOCK_PAD_X;      // right edge shared by dock and clock
    // Below the dock the apps fill the screen, as on tvOS: the top row rises to here, near the top
    // edge (the clock fades away), and deeper rows scroll only to keep the focused one above the line.
    private static final float GRID_TOP = 95f, FOCUS_LINE = 640f;
    private static final float SHADOW_PAD = 60f, SHADOW_DY = 20f, SHADOW_BLUR = 30f;
    private static final float CLOCK_TOP = 50f, CLOCK_H = 56f, AVATAR = 40f;
    private static final float HIDDEN_GAP = 84f; // room for the "Hidden" title above the hidden apps
    private static final long HOLD_MS = 800;     // holding Down on the last row this long reveals Hidden
    private static final int RED = 0xFFFF5F57, DARK_RED = 0xFFE0342B, INK = 0xFF141416;
    private static final float PLAY_H = 64f;
    private static final long IDLE_MS = 60_000;
    /** What the focused app's top shelf is: nothing, a full-screen picture, or a smaller one on a card. */
    private static final int NO_SHELF = 0, FULL_SHELF = 1, CARD_SHELF = 2;

    private final Host host;
    private final List<Apps.Entry> apps = new ArrayList<>();   // visible, in order
    private final List<Apps.Entry> hidden = new ArrayList<>(); // greyed out below, while editing or revealed
    private int focus;                                          // index into apps, then hidden (when shown)

    private float scale = 1f, height = 1080f;
    private long lastFrame;
    private boolean animating;

    // Animated values and the states that drive them
    private float scroll, expand, hint, menuAnim, pillAnim, ccAnim, editAnim, fade = 1f;
    private float cardAnim, playFocus, chrome = 1f; // card focused; Play button focused; dock and Play shown (not idle)
    private float away; // 0 in the dock, 1 once focus is in the grid: blurs and dims the backdrop
    private int dim; // black overlay alpha once focus leaves the dock
    // Liquid-glass text colour over the backdrop: 0 = white, 1 = dark (on bright images)
    private float clockInk, hintInk, ccInk;
    private boolean expanded, menuOpen, longPressed, pillFocused, ccOpen;
    private boolean cardFocused; // a small recommendation's card, selected from the dock
    private boolean idle;        // a minute untouched on the full-screen shelf: only the picture shows
    private final Runnable goIdle = () -> {
        if (expanded && !pillFocused && !ccOpen && !menuOpen) {
            idle = true;
            kick();
        }
    };
    private boolean editing;          // Edit Home Screen: the focused app is picked up and moving
    private boolean showHidden;       // the Hidden section, revealed by holding Down on the last row
    private boolean downHeld, swallowDown; // Down held on the last row; its repeats after the reveal
    private long downAt, bounceAt;
    private float downAppear, downProgress, bounce, menuSelAnim;
    private Backdrops.Backdrop backdrop, previous;

    // Control Center (opened from the clock pill)
    private final ControlCenter cc;
    private final ControlCenter.Glass ccGlass = (c, rect, radius, tint, alpha) -> drawGlass(c, rect, radius, 0f, tint, alpha);

    // Long-press menu
    private final List<String> menuLabels = new ArrayList<>();
    private final List<Drawable> menuIcons = new ArrayList<>();
    private final List<Boolean> menuDanger = new ArrayList<>();
    private final List<Runnable> menuActions = new ArrayList<>();
    private final List<Boolean> menuStays = new ArrayList<>(); // switches keep the menu open to show their new state
    private final Drawable editIcon, infoIcon, hideIcon, showIcon, onIcon, offIcon, playIcon;
    private int menuSel;

    // Clock
    private String time = "";
    private float timeWidth;
    // "9:41 AM" as on tvOS, or "21:41" when the TV is set to 24-hour time.
    private final SimpleDateFormat clock12 = new SimpleDateFormat("h:mm a", Locale.US);
    private final SimpleDateFormat clock24 = new SimpleDateFormat("H:mm", Locale.US);
    private final Runnable tick = this::tick;

    // Drawing objects, allocated once
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final LightingColorFilter[] dimFilters = new LightingColorFilter[26];
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint hiddenArt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); // greyscale
    private final Paint headerText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint bottomScrim = new Paint();
    private final Paint topScrim = new Paint();
    private final LinearGradient rimGradient, sheenGradient;
    private final Path chevron = new Path();
    private final RectF r = new RectF(), r2 = new RectF(), playRect = new RectF();
    private final Matrix m = new Matrix();
    private Bitmap defaultAvatar, avatar, shadowMask;

    private final String fullScreenHint, hiddenTitle, hiddenEmpty, playLabel;
    private final float playWidth;

    HomeView(Context context, Host host) {
        super(context);
        this.host = host;
        setFocusable(true);
        setFocusableInTouchMode(true);

        fullScreenHint = context.getString(R.string.full_screen_hint);
        playLabel = context.getString(R.string.play);
        hiddenTitle = context.getString(R.string.hidden);
        hiddenEmpty = context.getString(R.string.hidden_empty);
        editIcon = menuIcon(context, R.drawable.ic_tv);
        infoIcon = menuIcon(context, R.drawable.ic_info);
        hideIcon = menuIcon(context, R.drawable.ic_hide);
        showIcon = menuIcon(context, R.drawable.ic_show);
        onIcon = menuIcon(context, R.drawable.ic_check_on);
        offIcon = menuIcon(context, R.drawable.ic_check_off);
        playIcon = menuIcon(context, R.drawable.ic_play);

        Typeface medium = Typeface.create(Typeface.DEFAULT, 500, false);
        Typeface semibold = Typeface.create(Typeface.DEFAULT, 600, false);
        clockText.setTypeface(semibold);
        clockText.setTextSize(27f);
        clockText.setColor(Color.WHITE);
        labelText.setTypeface(medium);
        labelText.setTextSize(25f);
        labelText.setTextAlign(Paint.Align.CENTER);
        hintText.setTypeface(medium);
        hintText.setTextSize(21f);
        hintText.setTextAlign(Paint.Align.CENTER);
        menuText.setTypeface(medium);
        menuText.setTextSize(26f);
        playText.setTypeface(semibold);
        playText.setTextSize(26f);
        playWidth = 26f + 30f + 10f + playText.measureText(playLabel) + 34f;
        headerText.setTypeface(semibold);
        headerText.setTextSize(30f);
        ColorMatrix grey = new ColorMatrix();
        grey.setSaturation(0f);
        hiddenArt.setColorFilter(new ColorMatrixColorFilter(grey));
        dashed.setStyle(Paint.Style.STROKE);
        dashed.setStrokeWidth(3f);
        dashed.setPathEffect(new DashPathEffect(new float[]{14f, 10f}, 0f));

        cc = new ControlCenter(context, EDGE,
                new RectF(EDGE - 8f - AVATAR, CLOCK_TOP + (CLOCK_H - AVATAR) / 2f, EDGE - 8f, CLOCK_TOP + (CLOCK_H + AVATAR) / 2f));

        // Liquid-glass: the blurred backdrop, more saturated, with a light rim brighter at the top.
        ColorMatrix saturate = new ColorMatrix();
        saturate.setSaturation(1.3f);
        glass.setColorFilter(new ColorMatrixColorFilter(saturate));
        rimGradient = new LinearGradient(0, 0, 0, 1, new int[]{0x73FFFFFF, 0x14FFFFFF, 0x2EFFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f);
        rim.setShader(rimGradient);

        // Specular highlight across the top of a focused icon.
        sheenGradient = new LinearGradient(0, 0, 0, 1, 0x3DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        sheen.setShader(sheenGradient);

        shadow.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setColor(Color.WHITE);
        chevron.moveTo(-17f, 5f);
        chevron.lineTo(0f, -4f);
        chevron.lineTo(17f, 5f);
    }

    // ---- State from the activity -------------------------------------------------------------

    /** False while editing: the arrangement being edited wins, and the caller retries later. */
    boolean setApps(List<Apps.Entry> visible, List<Apps.Entry> hiddenApps) {
        if (editing) return false;
        Apps.Entry was = focused();
        apps.clear();
        apps.addAll(visible);
        hidden.clear();
        hidden.addAll(hiddenApps);
        if (apps.isEmpty() && !hidden.isEmpty()) {
            // Everything hidden would leave nothing to focus, and no way back to Hidden: keep one out.
            apps.add(hidden.remove(0));
            host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
        }
        focus = Math.max(0, Math.min(focus, apps.size() - 1));
        if (was != null) {
            for (int i = 0; i < apps.size(); i++) {
                if (apps.get(i).pkg.equals(was.pkg)) {
                    focus = i;
                    break;
                }
            }
        }
        focusChanged();
        return true;
    }

    void setBackdrop(Backdrops.Backdrop b) {
        if (b == null || b == backdrop) return;
        previous = backdrop;
        backdrop = b;
        fade = previous == null ? 1f : 0f;
        kick();
    }

    /** The account's avatar (already round), shown in the clock pill. */
    void setAvatar(Bitmap round) {
        avatar = round;
        kick();
    }

    /** Home pressed while already home: back to the top row, everything closed. */
    void goHome() {
        menuOpen = false;
        ccOpen = false;
        pillFocused = false;
        if (editing) finishEditing();
        setExpanded(false);
        cardFocused = false;
        showHidden = false;
        downHeld = false;
        if (!apps.isEmpty()) focus = Math.min(focus, apps.size() - 1) % COLS;
        focusChanged();
    }

    /** Opens the Control Center (the clock pill was selected). */
    void openControlCenter() {
        menuOpen = false;
        if (editing) finishEditing();
        cc.setState(host.controlState());
        cc.selected = ControlCenter.POWER;
        pillFocused = true;
        ccOpen = true;
        kick();
    }

    private void closeControlCenter() {
        ccOpen = false;
        kick();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        removeCallbacks(goIdle);
        super.onDetachedFromWindow();
    }

    void setClockRunning(boolean run) {
        removeCallbacks(tick);
        if (run) tick();
    }

    private void tick() {
        SimpleDateFormat format = DateFormat.is24HourFormat(getContext()) ? clock24 : clock12;
        format.setTimeZone(TimeZone.getDefault()); // follow time-zone changes
        time = format.format(new Date());
        timeWidth = clockText.measureText(time);
        if (ccOpen) cc.setState(host.controlState()); // sleep-timer countdown, network changes
        kick();
        long now = System.currentTimeMillis();
        postDelayed(tick, 60_000 - now % 60_000 + 20);
    }

    // ---- Input -------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (downHeld && code != KeyEvent.KEYCODE_DPAD_DOWN) downHeld = false; // another key cancels the reveal
        if (expanded) {
            removeCallbacks(goIdle);
            postDelayed(goIdle, IDLE_MS);
        }
        if (idle) {
            // The first press only brings the dock and Play button back.
            idle = false;
            kick();
            if (isSelect(code) || isArrow(code) || code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return true;
        }
        if (menuOpen) {
            // Select acts on release (see onKeyUp): a held remote key repeats, and those repeats
            // must not pick a menu item the moment the long press opens the menu.
            if (isSelect(code)) return true;
            return menuKey(code) || super.onKeyDown(code, e);
        }
        if (isSelect(code)) {
            if (e.getRepeatCount() == 0) {
                e.startTracking();
                longPressed = false;
            }
            return true;
        }
        if (ccOpen) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT: ccMove(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: ccMove(1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_UP: ccMove(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: ccMove(0, 1); return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    closeControlCenter();
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
        }
        if (pillFocused) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) navSound(0, 1);
                    pillFocused = false;
                    setExpanded(false);
                    cardFocused = false;
                    return true;
                case KeyEvent.KEYCODE_MENU:
                    openControlCenter();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
        }
        if (expanded || cardFocused) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) navSound(0, 1);
                    setExpanded(false);
                    cardFocused = false;
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    navSound(0, -1);
                    focusPill();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                default:
                    return super.onKeyDown(code, e);
            }
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (swallowDown && e.getRepeatCount() > 0) return true; // still held from the reveal
                if (canRevealHidden()) {
                    // Held on the last row: an arrow appears, a ring fills round it, then Hidden opens.
                    if (e.getRepeatCount() == 0) {
                        downHeld = true;
                        downAt = SystemClock.uptimeMillis();
                        kick();
                    }
                    return true;
                }
                move(0, 1);
                return true;
            case KeyEvent.KEYCODE_MENU: if (!editing) openMenu(); return true;
            case KeyEvent.KEYCODE_BACK: back(); return true;
            default: return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (isSelect(code) && !editing && !expanded && !cardFocused && !menuOpen && !pillFocused && !ccOpen) {
            longPressed = true;
            openMenu();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isSelect(code)) {
            if (menuOpen) {
                if (!longPressed) activateMenu(); // the release of the opening long press picks nothing
            } else if (e.isTracking() && !e.isCanceled() && !longPressed) {
                select();
            }
            longPressed = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && (downHeld || swallowDown)) {
            downHeld = false;
            swallowDown = false;
            kick();
            return true;
        }
        return code == KeyEvent.KEYCODE_BACK || super.onKeyUp(code, e);
    }

    private static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean isArrow(int code) {
        return code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    /**
     * D-pad movement over the grid. The hidden apps form a second section below the visible ones,
     * shown while an app is being moved or once revealed; moving a picked-up app between the
     * sections hides or unhides it.
     */
    private void move(int dx, int dy) {
        int v = apps.size(), total = total();
        if (total == 0) return;
        boolean inHidden = focus >= v;
        int local = inHidden ? focus - v : focus, n = inHidden ? hidden.size() : v;
        int col = local % COLS, row = local / COLS;
        List<Apps.Entry> list = inHidden ? hidden : apps;
        int base = inHidden ? v : 0;

        if (dx != 0) {
            if (col + dx < 0 || col + dx >= COLS || local + dx >= n) return;
            if (editing) list.add(local + dx, list.remove(local));
            focus = focus + dx;
        } else if (dy > 0) {
            if ((row + 1) * COLS < n) {
                int to = Math.min(local + COLS, n - 1);
                if (editing) list.add(to, list.remove(local));
                focus = base + to;
            } else if (!inHidden && (editing ? v > 1 : showHidden && !hidden.isEmpty())) {
                // Into the hidden section (moving an app there hides it; at least one app stays).
                int to = Math.min(col, hidden.size() - (editing ? 0 : 1));
                if (editing) hidden.add(to, apps.remove(local));
                focus = apps.size() + to;
            } else {
                return;
            }
        } else if (row > 0) {
            int to = local - COLS;
            if (editing) list.add(to, list.remove(local));
            focus = base + to;
        } else if (inHidden) {
            // Up out of the hidden section, into the last row of apps (unhides a held app).
            int lastRow = v == 0 ? 0 : ((v - 1) / COLS) * COLS;
            if (editing) {
                int to = Math.min(lastRow + col, v);
                apps.add(to, hidden.remove(local));
                focus = to;
            } else if (v > 0) {
                focus = Math.min(lastRow + col, v - 1);
                showHidden = false; // leaving Hidden folds it away again
            } else {
                return;
            }
        } else if (!editing) {
            // Up from the dock: a full-screen top shelf, or a small one's card, else the clock pill.
            navSound(0, -1);
            int shelf = shelf();
            if (shelf == FULL_SHELF) {
                setExpanded(true);
            } else if (shelf == CARD_SHELF) {
                cardFocused = true;
                kick();
            } else {
                focusPill();
            }
            return;
        } else {
            return;
        }
        navSound(dx, dy);
        focusChanged();
    }

    private void ccMove(int dx, int dy) {
        if (!cc.move(dx, dy)) return;
        navSound(dx, dy);
        kick();
    }

    /** The system's navigation click (silent when System sounds are off in Settings). */
    private void navSound(int dx, int dy) {
        playSoundEffect(dx < 0 ? SoundEffectConstants.NAVIGATION_LEFT
                : dx > 0 ? SoundEffectConstants.NAVIGATION_RIGHT
                : dy < 0 ? SoundEffectConstants.NAVIGATION_UP
                : SoundEffectConstants.NAVIGATION_DOWN);
    }

    private void select() {
        if (ccOpen) {
            playSoundEffect(SoundEffectConstants.CLICK);
            if (host.control(cc.selected)) {
                cc.setState(host.controlState());
            } else {
                ccOpen = false;
                pillFocused = false;
            }
            kick();
            return;
        }
        if (pillFocused) {
            playSoundEffect(SoundEffectConstants.CLICK);
            openControlCenter();
            return;
        }
        Apps.Entry e = focused();
        if (e == null) return;
        playSoundEffect(SoundEffectConstants.CLICK);
        if (editing) {
            finishEditing(); // drop it here, and that's the edit done
            return;
        }
        Recommendations.Item program = backdrop != null ? backdrop.program : null;
        if ((expanded || cardFocused) && program != null && program.pkg.equals(e.pkg)) {
            // Play on the top shelf: what's shown (as on tvOS), zooming out of the Play button.
            Rect from = new Rect(Math.round(playRect.left * scale), Math.round(playRect.top * scale),
                    Math.round(playRect.right * scale), Math.round(playRect.bottom * scale));
            if (program.intentUri != null) host.launchProgram(program, from); else host.launch(e, from);
            setExpanded(false);
            cardFocused = false;
            return;
        }
        // Launch from the focused tile's on-screen rect so the system zooms the app out of it.
        float w = TILE_W * FOCUS_SCALE, h = TILE_H * FOCUS_SCALE;
        float cx = e.x + TILE_W / 2f, cy = e.y + TILE_H / 2f - scroll + lift();
        Rect from = new Rect(Math.round((cx - w / 2) * scale), Math.round((cy - h / 2) * scale),
                Math.round((cx + w / 2) * scale), Math.round((cy + h / 2) * scale));
        host.launch(e, from);
    }

    private void focusPill() {
        pillFocused = true;
        kick();
    }

    private void back() {
        if (editing) {
            finishEditing();
        } else if (focus >= apps.size()) {
            // Out of Hidden, back to the dock.
            showHidden = false;
            focus = Math.max(0, Math.min((focus - apps.size()) % COLS, apps.size() - 1));
            focusChanged();
        } else if (focus >= COLS) {
            focus %= COLS;
            focusChanged();
        }
    }

    /** The full-screen top shelf; after a minute untouched, the dock and Play button fade away. */
    private void setExpanded(boolean on) {
        expanded = on;
        idle = false;
        removeCallbacks(goIdle);
        if (on) postDelayed(goIdle, IDLE_MS);
        kick();
    }

    /** The top shelf showing for the focused dock app (only once its backdrop is the one on screen). */
    private int shelf() {
        Apps.Entry e = focused();
        Backdrops.Backdrop b = backdrop;
        if (e == null || b == null || b.program == null || !inDock() || !b.program.pkg.equals(e.pkg)) return NO_SHELF;
        return b.card != null ? CARD_SHELF : FULL_SHELF;
    }

    /**
     * Edit Home Screen, as on tvOS: the chosen app is picked up and wiggles, the arrows move it (into
     * or out of Hidden too), and select (or Back) drops it and ends the edit.
     */
    private void startEditing() {
        editing = true;
        kick();
    }

    private void finishEditing() {
        editing = false;
        host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
        // Dropped among the hidden apps: stay there. Otherwise Hidden folds away.
        showHidden = focus >= apps.size() && !hidden.isEmpty();
        if (focus >= total()) focus = Math.max(0, apps.size() - 1);
        focusChanged();
    }

    /** Held Down long enough on the last row: drop into the Hidden section with a little bounce. */
    private void revealHidden() {
        downHeld = false;
        swallowDown = true;
        showHidden = true;
        focus = apps.size() + Math.min(focus % COLS, hidden.size() - 1);
        bounceAt = SystemClock.uptimeMillis();
        navSound(0, 1);
        focusChanged();
    }

    private boolean canRevealHidden() {
        int v = apps.size();
        return !editing && !showHidden && !hidden.isEmpty() && focus < v && focus / COLS == (v - 1) / COLS;
    }

    private void hide(Apps.Entry e) {
        if (apps.size() <= 1 || !apps.remove(e)) return; // at least one app stays out
        hidden.add(e);
        host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
        focus = Math.max(0, Math.min(focus, apps.size() - 1));
        focusChanged();
    }

    private void unhide(Apps.Entry e) {
        if (!hidden.remove(e)) return;
        apps.add(e);
        host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
        showHidden = false;
        focus = apps.size() - 1;
        focusChanged();
    }

    private void openMenu() {
        Apps.Entry e = focused();
        if (e == null) return;
        buildMenu(e);
        menuSel = 0;
        menuSelAnim = 0f;
        menuOpen = true;
        kick();
    }

    /**
     * The app's menu. Apps that publish Continue Watching or recommendations get a switch for each,
     * so the top shelf can show either, both, or neither (then just the app's logo).
     */
    private void buildMenu(Apps.Entry e) {
        menuLabels.clear();
        menuIcons.clear();
        menuDanger.clear();
        menuActions.clear();
        menuStays.clear();
        addMenu(R.string.edit_home, editIcon, false, false, this::startEditing);
        addMenu(R.string.app_info, infoIcon, false, false, () -> host.showInfo(e));
        if (hidden.contains(e)) {
            addMenu(R.string.unhide_app, showIcon, false, false, () -> unhide(e));
            return;
        }
        int sources = host.shelfSources(e);
        if ((sources & Recommendations.WATCH_NEXT) != 0) addSwitch(R.string.continue_watching, e, Recommendations.WATCH_NEXT);
        if ((sources & Recommendations.PREVIEWS) != 0) addSwitch(R.string.recommendations, e, Recommendations.PREVIEWS);
        if (apps.size() > 1) addMenu(R.string.hide_app, hideIcon, true, false, () -> hide(e));
    }

    private void addSwitch(int label, Apps.Entry e, int source) {
        boolean on = host.shelfSourceOn(e, source);
        addMenu(label, on ? onIcon : offIcon, false, true, () -> host.setShelfSource(e, source, !on));
    }

    private void addMenu(int label, Drawable icon, boolean danger, boolean stays, Runnable action) {
        menuLabels.add(getContext().getString(label));
        menuIcons.add(icon);
        menuDanger.add(danger);
        menuActions.add(action);
        menuStays.add(stays);
    }

    private void activateMenu() {
        playSoundEffect(SoundEffectConstants.CLICK);
        int sel = menuSel;
        menuActions.get(sel).run();
        Apps.Entry e = focused();
        if (menuStays.get(sel) && e != null) {
            buildMenu(e);
            menuSel = sel;
        } else {
            menuOpen = false;
        }
        kick();
    }

    private static Drawable menuIcon(Context c, int res) {
        Drawable d = c.getDrawable(res);
        return d != null ? d.mutate() : null;
    }

    /** Keys while the long-press menu is open; false lets anything else (volume…) through. */
    private boolean menuKey(int code) {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
            int to = Math.max(0, Math.min(menuLabels.size() - 1, menuSel + dy));
            if (to != menuSel) navSound(0, dy);
            menuSel = to;
        } else if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            menuOpen = false;
        } else {
            return false;
        }
        kick();
        return true;
    }

    private Apps.Entry focused() {
        return focus < total() ? entry(focus) : null;
    }

    private void focusChanged() {
        host.onFocusChanged(focused(), inDock());
        kick();
    }

    /** Tiles on screen: the visible apps, plus the hidden ones while editing or revealed. */
    private int total() {
        return apps.size() + (editing || showHidden ? hidden.size() : 0);
    }

    private Apps.Entry entry(int i) {
        return i < apps.size() ? apps.get(i) : hidden.get(i - apps.size());
    }

    private boolean inDock() {
        return focus < COLS && focus < apps.size();
    }

    private float slotX(int i) {
        int local = i < apps.size() ? i : i - apps.size();
        return GRID_X + (local % COLS) * (TILE_W + GAP_X);
    }

    private float slotY(int i) {
        if (i < apps.size()) return DOCK_ROW_Y + (i / COLS) * ROW_PITCH;
        return hiddenTop() + ((i - apps.size()) / COLS) * ROW_PITCH;
    }

    /** Top of the hidden section: below the last row of apps, with room for its title. */
    private float hiddenTop() {
        int rows = Math.max(1, (apps.size() + COLS - 1) / COLS);
        return DOCK_ROW_Y + rows * ROW_PITCH + HIDDEN_GAP;
    }

    // ---- Animation ---------------------------------------------------------------------------

    /** Starts the frame loop; resets the clock if it was idle so the first step isn't a jump. */
    private void kick() {
        if (!animating) lastFrame = SystemClock.uptimeMillis() - 16;
        postInvalidateOnAnimation();
    }

    private float ease(float v, float target, float rate, float dt, float eps) {
        float d = target - v;
        if (Math.abs(d) <= eps) return target;
        animating = true;
        return v + d * (1f - (float) Math.exp(-rate * dt));
    }

    private void step(float dt) {
        boolean dock = inDock();
        float scrollTo = 0f;
        if (!dock && total() > 0) scrollTo = Math.max(DOCK_ROW_Y - GRID_TOP, slotY(focus) - FOCUS_LINE);
        scroll = ease(scroll, scrollTo, 10f, dt, 0.2f);
        away = ease(away, dock ? 0f : 1f, 8f, dt, 0.002f);
        // A shelf that went away (its source switched off, say) can't stay selected.
        int shelf = shelf();
        if (expanded && shelf != FULL_SHELF) setExpanded(false);
        if (cardFocused && shelf != CARD_SHELF) cardFocused = false;
        expand = ease(expand, expanded ? 1f : 0f, 9f, dt, 0.001f);
        cardAnim = ease(cardAnim, cardFocused && !pillFocused && !ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        playFocus = ease(playFocus, (expanded || cardFocused) && !pillFocused && !ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        chrome = ease(chrome, idle ? 0f : 1f, idle ? 2.5f : 12f, dt, 0.002f); // fades out slowly, back at once
        editAnim = ease(editAnim, editing || showHidden ? 1f : 0f, 10f, dt, 0.002f);
        long now = SystemClock.uptimeMillis();
        if (downHeld) {
            downProgress = Math.min(1f, (now - downAt) / (float) HOLD_MS);
            animating = true;
            if (downProgress >= 1f) revealHidden();
        } else {
            downProgress = ease(downProgress, 0f, 10f, dt, 0.002f);
        }
        downAppear = ease(downAppear, downHeld ? 1f : 0f, 14f, dt, 0.002f);
        bounce = 0f;
        if (bounceAt != 0) {
            float t = (now - bounceAt) / 1000f;
            if (t >= 0.9f) {
                bounceAt = 0;
            } else {
                bounce = 26f * (float) (Math.exp(-5.5 * t) * Math.sin(t * 2 * Math.PI * 2.2));
                animating = true;
            }
        }
        menuSelAnim = ease(menuSelAnim, menuSel, 20f, dt, 0.002f);
        boolean showHint = dock && !expanded && !cardFocused && !menuOpen && !editing && !pillFocused && shelf == FULL_SHELF;
        hint = ease(hint, showHint ? 1f : 0f, 8f, dt, 0.002f);
        menuAnim = ease(menuAnim, menuOpen ? 1f : 0f, 18f, dt, 0.002f);
        pillAnim = ease(pillAnim, pillFocused ? 1f : 0f, 16f, dt, 0.002f);
        ccAnim = ease(ccAnim, ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        if (ccAnim > 0f && cc.step(dt)) animating = true;
        // Bright backdrops get dark text. Account for what's layered over the image at each spot.
        float shade = 1f - 0.39f * away;
        Backdrops.Backdrop b = backdrop;
        float clockLight = b == null ? 0f : (b.clockLight * shade) * 0.9f + 0.1f;   // + the pill's white tint
        float hintLight = b == null ? 0f : b.hintLight * 0.78f;                      // under the bottom scrim
        float ccLight = b == null ? 0f : b.controlsLight * 0.86f + 0.14f;            // + the tiles' white tint
        clockInk = ease(clockInk, clockLight > 0.6f ? 1f : 0f, 6f, dt, 0.002f);
        hintInk = ease(hintInk, hintLight > 0.6f ? 1f : 0f, 6f, dt, 0.002f);
        ccInk = ease(ccInk, ccLight > 0.62f ? 1f : 0f, 6f, dt, 0.002f);
        fade = ease(fade, 1f, 9f, dt, 0.002f);
        if (fade == 1f) previous = null;
        for (int i = 0, n = total(); i < n; i++) {
            Apps.Entry e = entry(i);
            float sx = slotX(i), sy = slotY(i);
            if (Float.isNaN(e.x)) {
                e.x = sx;
                e.y = sy;
            }
            e.x = ease(e.x, sx, 14f, dt, 0.2f);
            e.y = ease(e.y, sy, 14f, dt, 0.2f);
            e.focus = ease(e.focus, i == focus && !expanded && !cardFocused && !pillFocused ? 1f : 0f, 14f, dt, 0.002f);
        }
        if (editing) animating = true; // the picked-up app wiggles
    }

    /** How far the UI has slid down for the full-screen top shelf: the dock peeks in where the second row was. */
    private float lift() {
        return expand * ROW_PITCH;
    }

    // ---- Drawing -----------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        scale = w / W;
        height = h / scale;
        bottomScrim.setShader(new LinearGradient(0, height * 0.45f, 0, height, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        topScrim.setShader(new LinearGradient(0, 0, 0, 220f, 0x4D000000, 0x00000000, Shader.TileMode.CLAMP));
        defaultAvatar = makeAvatar(Math.max(1, Math.round(AVATAR * scale)));
        shadowMask = makeShadowMask(scale);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min((now - lastFrame) / 1000f, 0.05f);
        lastFrame = now;
        animating = false;
        step(dt);

        c.save();
        c.scale(scale, scale);
        drawBackground(c);
        drawCard(c);

        float offset = lift() - scroll - bounce;
        // Idle on the full-screen shelf, the dock fades away with the Play button (as one layer, only while fading).
        int chromeAlpha = Math.round(255 * (expanded ? chrome : 1f));
        if (chromeAlpha > 2) {
            int layer = chromeAlpha < 253
                    ? c.saveLayerAlpha(0f, Math.max(0f, DOCK_ROW_Y - DOCK_PAD_Y - PLAY_H - 120f + offset), W, height, chromeAlpha)
                    : -1;
            c.save();
            c.translate(0, offset);
            drawDock(c, offset);
            drawHiddenSection(c);
            int n = total();
            for (int i = 0; i < n; i++) if (i != focus) drawTile(c, entry(i), i, offset);
            if (focus < n) drawTile(c, entry(focus), focus, offset);
            drawHint(c);
            drawRevealHint(c);
            c.restore();
            drawFullScreenPlay(c);
            if (layer >= 0) c.restoreToCount(layer);
        }

        if (ccAnim > 0f) c.drawColor(Color.argb(Math.round(110 * ccAnim), 0, 0, 0));
        drawClock(c);
        if (ccAnim > 0f) cc.draw(c, ccGlass, ccAnim, ccInk);
        if (menuAnim > 0f) drawMenu(c, offset);
        c.restore();

        if (animating) postInvalidateOnAnimation();
    }

    /**
     * The backdrop is the costliest thing on screen (each layer covers all 2 million pixels), so
     * layers that can't be seen are skipped, the grid's dimming is folded into the image draw as a
     * colour filter rather than a separate pass, and the scrims fade out once the grid is dimmed.
     */
    private void drawBackground(Canvas c) {
        dim = Math.round(away * 100);
        backdropPaint.setColorFilter(dimFilter(dim));
        boolean crossfading = previous != null && fade < 0.98f;
        if (crossfading) drawBackdrop(c, previous, 255);
        if (backdrop != null) drawBackdrop(c, backdrop, crossfading ? Math.round(255 * fade) : 255);
        int a = Math.round(255 * (1f - expand) * (1f - away));
        if (a > 2) {
            bottomScrim.setAlpha(a);
            c.drawRect(0, height * 0.45f, W, height, bottomScrim);
            topScrim.setAlpha(a);
            c.drawRect(0, 0, W, 220f, topScrim);
        }
    }

    /** Sharp, blurred, or (mid-transition) the blurred copy faded in over the sharp one. */
    private void drawBackdrop(Canvas c, Backdrops.Backdrop b, int alpha) {
        r.set(0, 0, W, height);
        if (away < 0.97f) {
            backdropPaint.setAlpha(alpha);
            c.drawBitmap(b.sharp, null, r, backdropPaint);
        }
        if (away > 0.03f) {
            backdropPaint.setAlpha(Math.round(alpha * away));
            c.drawBitmap(b.blur, null, r, backdropPaint);
        }
    }

    /** Darkens by {@code alpha}/255, like a black overlay, cached in 26 steps. */
    private ColorFilter dimFilter(int alpha) {
        int level = alpha / 4;
        if (level == 0) return null;
        if (dimFilters[level] == null) {
            int k = 255 - level * 4;
            dimFilters[level] = new LightingColorFilter(Color.rgb(k, k, k), 0);
        }
        return dimFilters[level];
    }

    /** A liquid-glass shape: blurred backdrop + tint + rim. {@code offsetY} is the canvas' current y translation. */
    private void drawGlass(Canvas c, RectF rect, float radius, float offsetY, int tint, int alpha) {
        if (previous != null) glassLayer(c, previous, rect, radius, offsetY, alpha);
        if (backdrop != null) {
            glassLayer(c, backdrop, rect, radius, offsetY, previous != null ? Math.round(alpha * fade) : alpha);
        }
        // The glass sees the same dimmed backdrop as everything else: the black dim and the tint over
        // it, composited here into one fill so it's one draw instead of two.
        int ta = Color.alpha(tint) * alpha / 255, da = dim * alpha / 255;
        int a = ta + da * (255 - ta) / 255;
        if (a > 0) {
            fill.setColor(Color.argb(a, Color.red(tint) * ta / a, Color.green(tint) * ta / a, Color.blue(tint) * ta / a));
            c.drawRoundRect(rect, radius, radius, fill);
        }
        m.setScale(1f, rect.height());
        m.postTranslate(0f, rect.top);
        rimGradient.setLocalMatrix(m);
        rim.setAlpha(alpha);
        c.drawRoundRect(rect, radius, radius, rim);
    }

    private void glassLayer(Canvas c, Backdrops.Backdrop b, RectF rect, float radius, float offsetY, int alpha) {
        m.setScale(W / b.blur.getWidth(), height / b.blur.getHeight());
        m.postTranslate(0f, -offsetY);
        b.blurShader.setLocalMatrix(m);
        glass.setShader(b.blurShader);
        glass.setAlpha(alpha);
        c.drawRoundRect(rect, radius, radius, glass);
    }

    private void drawDock(Canvas c, float offset) {
        r.set(GRID_X - DOCK_PAD_X, DOCK_ROW_Y - DOCK_PAD_Y, EDGE, DOCK_ROW_Y + TILE_H + DOCK_PAD_Y);
        if (r.bottom + offset < 0f || r.top + offset > height) return;
        drawGlass(c, r, TILE_R + DOCK_PAD_Y, offset, 0x14FFFFFF, 255);
    }

    private void drawTile(Canvas c, Apps.Entry e, int i, float offset) {
        boolean held = editing && i == focus, isHidden = i >= apps.size();
        float f = e.focus, s = 1f + (FOCUS_SCALE - 1f) * f + (held ? 0.08f : 0f);
        float w = TILE_W * s, h = TILE_H * s;
        float cx = e.x + TILE_W / 2f, cy = e.y + TILE_H / 2f;
        if (cy + h + offset < 0f || cy - h + offset > height) return;
        r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        float radius = TILE_R * s;

        int save = c.save();
        if (held) {
            // The picked-up app wiggles, as on tvOS.
            double t = SystemClock.uptimeMillis() / 1000.0;
            c.rotate((float) Math.sin(t * Math.PI * 2 * 3.2) * 1.4f, cx, cy);
        }
        if (f > 0.01f && shadowMask != null) {
            // Pre-blurred mask, scaled with the tile: no per-frame GPU blur.
            float pad = SHADOW_PAD * w / (TILE_W * FOCUS_SCALE), dy = SHADOW_DY * f;
            r2.set(r.left - pad, r.top - pad + dy, r.right + pad, r.bottom + pad + dy);
            shadow.setAlpha(Math.round(165 * f));
            c.drawBitmap(shadowMask, null, r2, shadow);
        }
        m.setScale(w / e.art.getWidth(), h / e.art.getHeight());
        m.postTranslate(r.left, r.top);
        e.shader.setLocalMatrix(m);
        Paint p = art;
        if (isHidden) {
            p = hiddenArt;
            p.setAlpha(Math.round(255 * (0.45f + 0.45f * f)));
        }
        p.setShader(e.shader);
        c.drawRoundRect(r, radius, radius, p);
        if (f > 0.01f) {
            m.setScale(1f, h * 0.6f);
            m.postTranslate(0f, r.top);
            sheenGradient.setLocalMatrix(m);
            sheen.setAlpha(Math.round(255 * f));
            c.drawRoundRect(r, radius, radius, sheen);
        }
        c.restoreToCount(save);

        // Names show under focused icons in the grid (not in the dock), as on tvOS.
        if (f > 0.01f && (i >= COLS || isHidden)) {
            int a = Math.round(240 * f);
            labelText.setColor(Color.argb(a, 255, 255, 255));
            labelText.setShadowLayer(8f, 0f, 1f, Color.argb(a / 2, 0, 0, 0));
            if (e.shortLabel == null) {
                e.shortLabel = TextUtils.ellipsize(e.label, labelText, TILE_W * FOCUS_SCALE + GAP_X, TextUtils.TruncateAt.END).toString();
            }
            c.drawText(e.shortLabel, cx, r.bottom + 38f, labelText);
        }
    }

    private void drawHint(Canvas c) {
        float a = hint * (1f - expand);
        if (a <= 0.01f) return;
        int alpha = Math.round(205 * a);
        float y = DOCK_ROW_Y - DOCK_PAD_Y - 64f;
        c.save();
        c.translate(W / 2f, y - 6f);
        int level = Math.round(255 - 227 * hintInk);
        stroke.setColor(Color.argb(alpha, level, level, level));
        c.drawPath(chevron, stroke);
        c.restore();
        hintText.setColor(Color.argb(alpha, level, level, level));
        hintText.setShadowLayer(8f, 0f, 1f, Color.argb(Math.round(alpha / 2f * (1f - hintInk)), 0, 0, 0));
        c.drawText(fullScreenHint, W / 2f, y + 37f, hintText);
    }

    /**
     * The clock: a glass pill (white while focused). With the Control Center open it becomes the
     * tvOS status row instead: time on the left, status glyphs, the Control Center button, profile.
     */
    private void drawClock(Canvas c) {
        int alpha = Math.round(255 * Math.max(Math.max((1f - expand) * (1f - away), pillAnim), ccAnim));
        if (alpha <= 2) return;
        float h = CLOCK_H, pad = 22f, gap = 12f, size = AVATAR, cy = CLOCK_TOP + h / 2f;

        int pillAlpha = Math.round(alpha * (1f - ccAnim));
        if (pillAlpha > 2) {
            float grow = 1f + 0.05f * pillAnim;
            float w = (pad + timeWidth + gap + size + 8f) * grow, gh = h * grow;
            r.set(EDGE - w, cy - gh / 2f, EDGE, cy + gh / 2f);
            drawGlass(c, r, gh / 2f, 0f, 0x1AFFFFFF, pillAlpha);
            if (pillAnim > 0.01f) {
                fill.setColor(Color.argb(Math.round(pillAlpha * pillAnim * 0.96f), 255, 255, 255));
                c.drawRoundRect(r, gh / 2f, gh / 2f, fill);
            }
            int ink = Math.round(255 - (255 - 28) * Math.max(clockInk, Math.min(1f, pillAnim * 1.4f)));
            clockText.setColor(Color.argb(pillAlpha, ink, ink, ink));
            c.drawText(time, r.left + pad * grow, cy + 9.5f, clockText);
        }
        if (ccAnim > 0.01f) {
            int rowAlpha = Math.round(255 * ccAnim);
            clockText.setColor(Color.argb(rowAlpha, 255, 255, 255));
            c.drawText(time, EDGE - ControlCenter.WIDTH + 6f, cy + 9.5f, clockText);
            cc.drawStatus(c, cy, rowAlpha);
        }
        Bitmap face = avatar != null ? avatar : defaultAvatar;
        if (face != null) {
            float ax = EDGE - 8f - size, ay = cy - size / 2f;
            r.set(ax, ay, ax + size, ay + size);
            image.setAlpha(alpha);
            c.drawBitmap(face, null, r, image);
        }
    }

    /**
     * A small recommendation's card while it's selected: redrawn from the backdrop a little larger,
     * lifted on a shadow with a sheen, as a focused tile is, with a Play button under its title.
     */
    private void drawCard(Canvas c) {
        Backdrops.Backdrop b = backdrop;
        if (cardAnim <= 0.01f || b == null || b.sharpShader == null) return;
        RectF card = b.card;
        float k = 1f + 0.06f * cardAnim, cx = card.centerX(), cy = card.centerY();
        float w = card.width() * k, h = card.height() * k, radius = Backdrops.CARD_R * k;
        r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        if (shadowMask != null) {
            float pad = SHADOW_PAD * w / (TILE_W * FOCUS_SCALE), dy = SHADOW_DY * 1.5f * cardAnim;
            r2.set(r.left - pad, r.top - pad + dy, r.right + pad, r.bottom + pad + dy);
            shadow.setAlpha(Math.round(190 * cardAnim));
            c.drawBitmap(shadowMask, null, r2, shadow);
        }
        m.setScale(W / b.sharp.getWidth() * k, height / b.sharp.getHeight() * k);
        m.postTranslate(cx * (1f - k), cy * (1f - k)); // the backdrop, enlarged about the card's centre
        b.sharpShader.setLocalMatrix(m);
        cardPaint.setShader(b.sharpShader);
        c.drawRoundRect(r, radius, radius, cardPaint);
        m.setScale(1f, h * 0.6f);
        m.postTranslate(0f, r.top);
        sheenGradient.setLocalMatrix(m);
        sheen.setAlpha(Math.round(255 * cardAnim));
        c.drawRoundRect(r, radius, radius, sheen);
        drawPlay(c, card.right + 60f, b.titleBottom + 34f, Math.round(255 * cardAnim));
    }

    /** The full-screen shelf's Play button, above the peeking dock. */
    private void drawFullScreenPlay(Canvas c) {
        if (expand <= 0.01f) return;
        float y = DOCK_ROW_Y - DOCK_PAD_Y + ROW_PITCH - 44f - PLAY_H + (1f - expand) * 30f;
        drawPlay(c, GRID_X, y, Math.round(255 * expand));
    }

    /** A capsule button: glass, or white with dark ink while focused (the only thing focused on a shelf). */
    private void drawPlay(Canvas c, float x, float y, int alpha) {
        r.set(x, y, x + playWidth, y + PLAY_H);
        playRect.set(r);
        float radius = PLAY_H / 2f, f = playFocus;
        if (f < 0.99f) drawGlass(c, r, radius, 0f, 0x24FFFFFF, Math.round(alpha * (1f - f)));
        if (f > 0.01f) {
            fill.setColor(Color.argb(Math.round(alpha * f * 0.96f), 255, 255, 255));
            c.drawRoundRect(r, radius, radius, fill);
        }
        int level = Math.round(255 - (255 - Color.red(INK)) * f);
        int ink = Color.argb(alpha, level, level, level);
        float size = 30f, ix = x + 26f, iy = y + (PLAY_H - size) / 2f;
        playIcon.setBounds(Math.round(ix), Math.round(iy), Math.round(ix + size), Math.round(iy + size));
        playIcon.setTint(ink | 0xFF000000);
        playIcon.setAlpha(alpha);
        playIcon.draw(c);
        playText.setColor(ink);
        c.drawText(playLabel, ix + size + 10f, y + PLAY_H / 2f + 9f, playText);
    }

    /**
     * The long-press menu: a dark glass list beside the focused icon, icons on the right. As on tvOS,
     * the selected option pops out of it: a white capsule a little wider than the list, lifted on a
     * shadow, its text slightly larger, gliding from option to option.
     */
    private void drawMenu(Canvas c, float offset) {
        Apps.Entry e = focused();
        if (e == null || menuLabels.isEmpty()) return;
        int alpha = Math.round(255 * menuAnim);
        float itemH = 68f, pad = 8f, w = 440f, h = pad * 2 + itemH * menuLabels.size();
        float tcx = e.x + TILE_W / 2f, tcy = e.y + TILE_H / 2f + offset;
        float tileL = tcx - TILE_W * FOCUS_SCALE / 2f, tileR = tcx + TILE_W * FOCUS_SCALE / 2f;
        float tileT = tcy - TILE_H * FOCUS_SCALE / 2f;
        boolean right = tileR + 12f + w <= W - 30f;
        float left = right ? tileR + 12f : tileL - 12f - w;
        left += (1f - menuAnim) * (right ? -16f : 16f);
        float top = Math.max(30f, Math.min(tileT, height - 30f - h));

        // Capsule rows in frosted glass whose corners follow them, like the dock and Control Center:
        // light over the dimmed grid, as on tvOS, and darker over a bright backdrop in the dock so
        // the white text still reads.
        r.set(left, top, left + w, top + h);
        int tint = Color.argb(Math.round(0x8C + (0x30 - 0x8C) * away), blendChannel(0x1C, 255, away),
                blendChannel(0x1C, 255, away), blendChannel(0x22, 255, away));
        drawGlass(c, r, itemH / 2f + pad, 0f, tint, alpha);

        float pop = 10f, sy = top + pad + menuSelAnim * itemH;
        r.set(left - pop, sy - pop / 2f, left + w + pop, sy + itemH + pop / 2f);
        if (shadowMask != null) {
            // The tile shadow mask, stretched so its shape lands on the capsule.
            float sx = r.width() * 0.194f, sh = r.height() * 0.333f;
            r2.set(r.left - sx, r.top - sh + 10f, r.right + sx, r.bottom + sh + 10f);
            shadow.setAlpha(Math.round(130 * menuAnim));
            c.drawBitmap(shadowMask, null, r2, shadow);
        }
        fill.setColor(Color.argb(Math.round(248 * menuAnim), 255, 255, 255));
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill);

        menuText.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < menuLabels.size(); i++) {
            float y = top + pad + i * itemH, cy = y + itemH / 2f;
            float sel = Math.max(0f, 1f - Math.abs(menuSelAnim - i)); // how much of the pop-out is on this row
            boolean danger = menuDanger.get(i);
            int ink = danger ? blend(RED, DARK_RED, sel) : blend(Color.WHITE, INK, sel);
            c.save();
            float k = 1f + 0.05f * sel;
            c.scale(k, k, left + w / 2f, cy);
            menuText.setColor(ink);
            menuText.setAlpha(alpha);
            c.drawText(menuLabels.get(i), left + pad + 30f, cy + 9f, menuText);
            Drawable icon = menuIcons.get(i);
            if (icon != null) {
                float size = 30f, ix = left + w - pad - 28f - size, iy = cy - size / 2f;
                icon.setBounds(Math.round(ix), Math.round(iy), Math.round(ix + size), Math.round(iy + size));
                icon.setTint(ink);
                icon.setAlpha(alpha);
                icon.draw(c);
            }
            c.restore();
        }
    }

    private static int blendChannel(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /**
     * Down held on the last row: an arrow below it, encircled as the hold goes on; once the ring
     * closes, focus drops into the Hidden section.
     */
    private void drawRevealHint(Canvas c) {
        if (downAppear <= 0.01f || apps.isEmpty()) return;
        int alpha = Math.round(235 * downAppear);
        float cx = W / 2f, cy = slotY(apps.size() - 1) + TILE_H + 110f, radius = 30f;
        stroke.setColor(Color.argb(alpha / 4, 255, 255, 255));
        c.drawCircle(cx, cy, radius, stroke);
        stroke.setColor(Color.argb(alpha, 255, 255, 255));
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        c.drawArc(r, -90f, 360f * downProgress, false, stroke);
        c.save();
        c.translate(cx, cy + 1f);
        c.scale(0.8f, -0.8f); // the hint's up chevron, turned down
        c.drawPath(chevron, stroke);
        c.restore();
    }

    /** With Hidden shown: its title, and a drop target while editing when nothing is hidden yet. */
    private void drawHiddenSection(Canvas c) {
        int a = Math.round(255 * editAnim);
        if (a <= 2) return;
        float top = hiddenTop();
        headerText.setColor(Color.argb(Math.round(a * 0.9f), 255, 255, 255));
        c.drawText(hiddenTitle, GRID_X, top - 30f, headerText);
        if (hidden.isEmpty()) {
            r.set(GRID_X, top, GRID_X + TILE_W, top + TILE_H);
            dashed.setColor(Color.argb(Math.round(a * 0.45f), 255, 255, 255));
            c.drawRoundRect(r, TILE_R, TILE_R, dashed);
            hintText.setTextAlign(Paint.Align.LEFT);
            hintText.setColor(Color.argb(Math.round(a * 0.6f), 255, 255, 255));
            hintText.clearShadowLayer();
            c.drawText(hiddenEmpty, GRID_X + TILE_W + 32f, top + TILE_H / 2f + 8f, hintText);
            hintText.setTextAlign(Paint.Align.CENTER);
        }
    }

    /** A soft drop shadow for a focused tile, blurred once in software. Drawn tinted by the paint's alpha. */
    private static Bitmap makeShadowMask(float scale) {
        float w = TILE_W * FOCUS_SCALE, h = TILE_H * FOCUS_SCALE;
        int bw = Math.round((w + 2 * SHADOW_PAD) * scale), bh = Math.round((h + 2 * SHADOW_PAD) * scale);
        Bitmap b = Bitmap.createBitmap(Math.max(1, bw), Math.max(1, bh), Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR * scale, BlurMaskFilter.Blur.NORMAL));
        float pad = SHADOW_PAD * scale, radius = TILE_R * FOCUS_SCALE * scale;
        new Canvas(b).drawRoundRect(new RectF(pad, pad, bw - pad, bh - pad), radius, radius, p);
        return b;
    }

    /** The default profile picture: a grey disc with a person glyph. */
    private static Bitmap makeAvatar(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, size, 0xFFA9A9AF, 0xFF6C6C72, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setShader(null);
        p.setColor(0xF2FFFFFF);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)); // clip the glyph to the disc
        c.drawCircle(size / 2f, size * 0.39f, size * 0.19f, p);
        c.drawOval(new RectF(size * 0.17f, size * 0.66f, size * 0.83f, size * 1.30f), p);
        return b;
    }
}
