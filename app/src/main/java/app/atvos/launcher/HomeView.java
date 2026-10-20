package app.atvos.launcher;

import android.animation.ValueAnimator;
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
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
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
        /** The focused app's full-screen top shelf pages ready to show, best first. */
        List<Recommendations.Item> shelfPages(Apps.Entry entry);
        /** Shows one of them behind the dock (and keeps it there, as tvOS does). */
        void showShelfPage(Apps.Entry entry, Recommendations.Item page);
        /** The app's recommendation sources worth a menu switch, as {@link Recommendations#WATCH_NEXT} / {@link Recommendations#PREVIEWS} bits. */
        int shelfSources(Apps.Entry entry);
        boolean shelfSourceOn(Apps.Entry entry, int source);
        void setShelfSource(Apps.Entry entry, int source, boolean on);
        Controls.State controlState();
        /** Runs a Control Center action; returns true to keep the Control Center open. */
        boolean control(int id);
        /** Gives up power control (the device admin), so the app can be uninstalled. */
        void releasePower();
        /** How the home screen is set up: system calls, so read when the sheet opens, not per frame. */
        Controls.Setup setupState();
        /** Asks Android to make this the home app. */
        void requestDefaultHome();
        /** Turns off this TV's own locked home apps over its own adb (the user approves the on-screen prompt). */
        void selfSetupHome(SelfSetup progress);
        /** Puts back the home apps a previous self-setup turned off. */
        void restoreTvHome(SelfSetup progress);
        /** The setup steps closed; the first time, that ends first-run setup. */
        void setupDone();
        /** Settings' About screen (select the build number to turn on Developer options). */
        void openAbout();
        /** Developer options (turn on USB debugging). */
        void openDeveloperOptions();
        /** Android's account picker, for the profile picture. */
        void chooseAccount();
        boolean hasAccount();
        /** The chosen Google account's address, or null. */
        String account();
        /** Settings > Profile Picture: {@link Profile#PHOTO}, {@link Profile#INITIAL} or {@link Profile#NONE}. */
        String profilePicture();
        void setProfilePicture(String picture);
        /** Settings > Time Format: the clock in 24-hour time (by default, as the TV has it). */
        boolean use24Hour();
        void setUse24Hour(boolean on);
        /** Settings > Support: opens {@link SettingsScreen#WEBSITE}, {@link SettingsScreen#STORE} or {@link SettingsScreen#APP_INFO}. */
        boolean openFromSettings(int what);
        /** Whether the TV has an app for it (many TVs have no web browser). */
        boolean canOpenFromSettings(int what);
        /** Uninstalls an app the user chose to delete (they've already confirmed it). */
        void deleteApp(Apps.Entry entry);
        /** Settings > Appearance: the rows of programs (and Settings) light rather than dark. */
        boolean lightAppearance();
        void setLightAppearance(boolean light);
        /** The optional tip through Google Play, for Settings. */
        Donation donation();
        /** Shows the TV's keyboard to edit {@code text} (a folder's name), reporting what's typed. */
        void editText(String text, TextEdit edit);
    }

    /** What's typed on the TV's keyboard. Called on the UI thread. */
    interface TextEdit {
        void changed(String text);
        void done(String text);
    }

    /** Progress for the on-TV setup, so the sheet can show where it's up to. Called on the UI thread. */
    interface SelfSetup {
        void status(int messageRes);
        void done(boolean ok, int messageRes);
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
    private static final int INK = 0xFF141416, TV_RED = 0xFFFF4245;
    // An open folder: a glass panel under its name, its apps four to a row, as on tvOS.
    private static final int FOLDER_COLS = 4;
    private static final float FOLDER_W = 1200f, FOLDER_TOP = 150f, FOLDER_PAD = 48f, FOLDER_GAP = 35f;
    private static final float FOLDER_R = 48f, FOLDER_NAME_Y = 78f, FOLDER_NAME_H = 84f;
    // The Delete alert
    private static final float ALERT_W = 660f, ALERT_PAD = 40f, ALERT_BUTTON_H = 70f;
    private static final float PLAY_H = 64f, PLAY_PAD = 34f, PLAY_ICON = 22f, PLAY_GAP = 14f, PLAY_BAR = 88f;
    // The full-screen top shelf's paging controls, as tvOS shows them for a click remote: arrows at
    // the sides, page dots at the bottom, shown on arrival and on each page, then fading away.
    private static final float PAGER_ARROW_D = 80f, PAGER_ARROW_X = 130f, PAGER_DOT = 8f, PAGER_DOT_STEP = 21f,
            PAGER_DOTS_H = 26f, PAGER_DOTS_BOTTOM = 24f;
    private static final long PAGER_SHOWN_MS = 1600;
    // A row shelf (programs under Full HD), laid out as tvOS lays out a Continue Watching row
    private static final float ROW_X = 90f, ROW_TOP = 180.5f, ROW_STEP = 900f, ROW_FOCUS_SCALE = 1.048f,
            ROW_HEADER_Y = 157f, ROW_HEADER_LIFT = 21f, ROW_BAR_INSET = 41f, ROW_BAR_BOTTOM = 28f, ROW_BAR_H = 6f;
    // A focused card's title too long for it scrolls in place, as tvOS's does (measured): a second
    // after focus arrives, at 45 units a second, a copy following 44 units behind, both edges faded,
    // then two seconds' rest at the start each time round.
    private static final float MARQUEE_SPEED = 45f, MARQUEE_GAP = 44f, MARQUEE_FADE = 24f;
    private static final long MARQUEE_DELAY_MS = 1000, MARQUEE_REST_MS = 2000;
    // After an app moves to Hidden, a note at the bottom right says how to get back to it: a pill
    // the clock pill's height, as far from the bottom right corner as the clock is from the top right.
    private static final long TOAST_MS = 4000;
    private static final float TOAST_PAD = 24f, TOAST_KEY = 30f, TOAST_KEY_GAP = 9f; // the Down key's icon, and the space either side
    // The full-screen shelf's details at the bottom left: title, a line of facts, the description, Play
    private static final float DETAILS_X = 92f, DETAILS_W = 820f, DETAILS_BOTTOM = 104f;
    /** What the focused app's top shelf is: nothing, a full-screen picture, or a smaller one on a card. */
    private static final int NO_SHELF = 0, FULL_SHELF = 1, ROW_SHELF = 2; // FULL: a Full HD picture; ROW: cards

    private final Host host;
    private final List<Apps.Entry> apps = new ArrayList<>();   // visible, in order
    private final List<Apps.Entry> hidden = new ArrayList<>(); // greyed out below, while editing or revealed
    private int focus;                                          // index into apps, then hidden (when shown)

    private float scale = 1f, height = 1080f;
    private long lastFrame;
    private boolean animating;

    // Animated values and the states that drive them
    private float scroll, expand, hint, menuAnim, pillAnim, ccAnim, editAnim, fade = 1f;
    private float rowAnim; // the row shelf focused (the headers rise, the focused card shows its title)
    private float playFocus; // the full-screen shelf's Play button focused (else glass: focus is on the clock pill)
    private float away; // 0 in the dock, 1 once focus is in the grid: blurs and dims the backdrop
    private int dim; // black overlay alpha once focus leaves the dock
    // Liquid-glass text colour over the backdrop: 0 = white, 1 = dark (on bright images)
    private float clockInk, hintInk, ccInk;
    private boolean expanded, menuOpen, longPressed, pillFocused, ccOpen;
    private boolean rowFocused; // a card in the row shelf is focused (Up from the dock)
    private int rowIndex;          // which card
    private float rowScroll;       // how far the row has scrolled left, design units
    private float[] rowFocus = new float[0]; // each card's focus, easing
    private String[] rowHeaders = new String[0]; // the section title over each card that starts one, else null
    private float[] rowHeaderWidths = new float[0];
    private String[] rowTitles = new String[0]; // each card's title
    private float[] rowTitleWidths = new float[0];
    private int marqueeCard = -1;  // the focused card whose title is too long for it, scrolling
    private long marqueeSince;     // when focus came to it
    private float marqueeOffset;   // how far its title has scrolled
    private final Runnable marqueeWake = this::kick; // the end of a rest: scroll again
    private boolean toastShown;    // "Long press ↓…", after an app moved to Hidden
    private float toastAnim, toastWidth;
    private final Runnable hideToast = () -> {
        toastShown = false;
        kick();
    };
    private List<Backdrops.Card> rowCards = Collections.emptyList(); // the row shown
    // The full-screen shelf's details, laid out when the program shown changes (not per frame)
    private Recommendations.Item detailsFor;
    private String detailsTitle, detailsLine;
    private StaticLayout detailsBody;
    // Its Play button: "Play", or for something partly watched, a progress bar and the time left (as tvOS does)
    private String detailsPlay = "";
    private float detailsProgress, detailsPlayWidth;
    private boolean motion = true; // animations are on (off: Remove animations, or an animator scale of 0)
    // The full-screen top shelf's pages (the focused app's programs), which one is shown, and its controls
    private List<Recommendations.Item> pages = Collections.emptyList();
    private int page;
    private boolean pagerShown;
    private float pagerAnim;
    private int hintTint = Color.WHITE; // the full-screen hint's colour: the backdrop's own, lightened (or darkened)
    private final Runnable hidePager = () -> {
        pagerShown = false;
        kick();
    };
    private boolean editing;          // Edit Home Screen: the focused app is picked up and moving
    private boolean editFromHidden;   // ...and it was picked up in Hidden
    private boolean showHidden;       // the Hidden section, revealed by holding Down on the last row
    private boolean downHeld, swallowDown; // Down held on the last row; its repeats after the reveal
    private long downAt, bounceAt;
    private float downAppear, downProgress, bounce, menuSelAnim;
    private Backdrops.Backdrop backdrop, previous;

    // Screen readers: a tree of virtual items, since everything is drawn on this one view
    private final A11y a11y;
    private static final int A11Y_PILL = 1, A11Y_PLAY = 2, A11Y_ALERT = 10, A11Y_ROW = 20, A11Y_CC = 100, A11Y_MENU = 200, A11Y_SUB = 250,
            A11Y_SETUP = 300, A11Y_SETTINGS = 350, A11Y_FOLDER = 400, A11Y_APP = 1000; // A11Y_FOLDER is its name, its apps follow

    // Control Center (opened from the clock pill)
    private final ControlCenter cc;
    private final ControlCenter.Glass ccGlass = (c, rect, radius, tint, alpha) -> drawGlass(c, rect, radius, 0f, tint, alpha);

    // Long-press menu, as on tvOS: options beside the tile, and a second list beside them for the
    // options that open one (Move to…, Top Shelf…).
    private static final int ROW_PLAIN = 0, ROW_DANGER = 1, ROW_SUBMENU = 2;
    /** One option. {@code checked} is a switch's state (in Top Shelf…), null for anything else. */
    private record MenuRow(String label, Drawable icon, int kind, Runnable action, Boolean checked) {
    }
    private final List<MenuRow> menuRows = new ArrayList<>(), subRows = new ArrayList<>();
    private static final float MENU_ITEM_H = 66f, MENU_PAD = 12f;
    private final RectF menuBox = new RectF(), subBox = new RectF(); // where they were last drawn, for screen readers
    private final Drawable editIcon, newFolderIcon, deleteIcon, checkIcon, chevronIcon;
    private Apps.Entry menuEntry;            // the tile the menu is for
    private int menuSel, subSel, subOf = -1; // subOf: the option whose list is open, or -1
    private float menuWidth, subWidth, subAnim, subSelAnim;
    private int subFrom;                     // the option the second list last opened from (it fades out there)
    private boolean subLeft;                 // no room on the right: the second list opens to the left
    private Runnable subBuilder;             // fills the open list again (a switch changed)

    // An open folder, over the home screen gone out of focus
    private Apps.Entry openFolder, shownFolder; // shown: still drawn while it fades away
    private int folderFocus;                    // index into its apps, or -1 for its name
    private float folderAnim, folderNameFocus, folderScroll;
    private boolean renaming;                   // its name is being typed on the TV's keyboard
    private float tileFade = 1f;                // tiles drawn now fade by this (an open folder's, as it opens)
    private String folderName = "";             // the name shown (and typed)

    // The Delete alert, as on tvOS
    private Apps.Entry alertEntry;
    private int alertSel;                       // 0 Cancel, 1 Delete
    private float alertAnim, alertSelAnim;
    private String alertTitle = "";
    private StaticLayout alertMessage;
    private final String cancelLabel, deleteLabel;
    private final RectF alertBox = new RectF();

    // The setup steps: on first run, and from Settings while another app has the Home button
    private final SetupFlow setupFlow;
    private boolean setupOpen;
    private float setupAnim;
    // Settings (the switches in Control Center): full screen, as tvOS Settings
    private final SettingsScreen settings;
    private boolean settingsOpen;
    private float settingsAnim;
    private boolean light; // Settings > Appearance: Light

    // Clock
    private String time = "";
    private float timeWidth;
    private final Runnable tick = this::tick;

    // Drawing objects, allocated once
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    // Behind an open folder: the blurred backdrop, darkened as it's drawn.
    private final Paint folderBlur = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final LightingColorFilter[] dimFilters = new LightingColorFilter[26];
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint hiddenArt = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); // greyscale
    private final TextPaint headerText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
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
    private final TextPaint menuText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titleText = new TextPaint(Paint.ANTI_ALIAS_FLAG), bodyText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rowHeaderText = new TextPaint(Paint.ANTI_ALIAS_FLAG), rowTitleText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detailsTitleText = new TextPaint(Paint.ANTI_ALIAS_FLAG), detailsLineText = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            detailsBodyText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint detailsScrim = new Paint();
    private final TextPaint rowMarqueeText = new TextPaint(Paint.ANTI_ALIAS_FLAG), toastText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path playTriangle = new Path();
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // Darker at the bottom for the dock, at the top for the clock. (Multiplying them into the
    // backdrop in one shader pass measured twice as slow on Mali: a hardware bitmap in a compose
    // shader loses the fast path. Plain passes it is.)
    private final Paint bottomScrim = new Paint();
    private final Paint topScrim = new Paint();
    private final LinearGradient rimGradient, sheenGradient, marqueeFade;
    private final Path chevron = new Path(), hintChevron = new Path(), pagerChevron = new Path(), downChevron = new Path();
    private final RectF r = new RectF(), r2 = new RectF(), playRect = new RectF();
    private final Matrix m = new Matrix();
    private Bitmap defaultAvatar, avatar, shadowMask;

    private final String fullScreenHint, hiddenTitle, hiddenEmpty, playLabel;
    private final String toastBefore, toastAfter, toastSpoken; // either side of the Down key's icon, and as read aloud

    HomeView(Context context, Host host) {
        super(context);
        this.host = host;
        setFocusable(true);
        setFocusableInTouchMode(true);

        fullScreenHint = context.getString(R.string.full_screen_hint);
        playLabel = context.getString(R.string.play);
        hiddenTitle = context.getString(R.string.hidden);
        hiddenEmpty = context.getString(R.string.hidden_empty);
        String toast = context.getString(R.string.hidden_toast), key = "%1$s";
        int at = Math.max(0, toast.indexOf(key));
        toastBefore = toast.substring(0, at).trim();
        toastAfter = toast.indexOf(key) < 0 ? "" : toast.substring(at + key.length()).trim();
        toastSpoken = context.getString(R.string.hidden_toast, context.getString(R.string.a11y_down));
        editIcon = menuIcon(context, R.drawable.ic_tv);
        newFolderIcon = menuIcon(context, R.drawable.ic_add_circle);
        deleteIcon = menuIcon(context, R.drawable.ic_remove_circle);
        checkIcon = menuIcon(context, R.drawable.ic_check);
        chevronIcon = menuIcon(context, R.drawable.ic_chevron_right);
        cancelLabel = context.getString(R.string.cancel);
        deleteLabel = context.getString(R.string.delete);

        Typeface medium = Typeface.create(Typeface.DEFAULT, 500, false);
        Typeface semibold = Typeface.create(Typeface.DEFAULT, 600, false);
        clockText.setTypeface(semibold);
        clockText.setTextSize(27f);
        clockText.setColor(Color.WHITE);
        labelText.setTypeface(medium);
        labelText.setTextSize(25f);
        labelText.setTextAlign(Paint.Align.CENTER);
        hintText.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        hintText.setTextSize(27f);
        hintText.setTextAlign(Paint.Align.CENTER);
        menuText.setTypeface(medium);
        menuText.setTextSize(28f);
        titleText.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        titleText.setTextSize(30f);
        titleText.setTextAlign(Paint.Align.CENTER);
        bodyText.setTypeface(Typeface.DEFAULT);
        bodyText.setTextSize(23f);
        playText.setTypeface(semibold);
        playText.setTextSize(30f);
        // tvOS's play.fill: a triangle with softened corners (drawn with a round join)
        playTriangle.moveTo(0f, 0f);
        playTriangle.lineTo(19f, 11f);
        playTriangle.lineTo(0f, 22f);
        playTriangle.close();
        rowHeaderText.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        rowHeaderText.setTextSize(44f);
        rowTitleText.setTypeface(medium);
        rowTitleText.setTextSize(28f);
        rowTitleText.setTextAlign(Paint.Align.CENTER);
        rowMarqueeText.setTypeface(medium);
        rowMarqueeText.setTextSize(28f);
        float edge = MARQUEE_FADE / (Backdrops.ROW_CARD_W * ROW_FOCUS_SCALE); // across a focused card, 0 to 1
        marqueeFade = new LinearGradient(0, 0, 1, 0, new int[]{0x00FFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, edge, 1f - edge, 1f}, Shader.TileMode.CLAMP);
        rowMarqueeText.setShader(marqueeFade);
        toastText.setTypeface(medium);
        toastText.setTextSize(26f);
        toastText.setColor(Color.WHITE);
        detailsTitleText.setTypeface(Typeface.create(Typeface.DEFAULT, 800, false));
        detailsTitleText.setTextSize(64f);
        detailsLineText.setTypeface(semibold);
        detailsLineText.setTextSize(25f);
        detailsBodyText.setTypeface(Typeface.DEFAULT);
        detailsBodyText.setTextSize(26f);
        headerText.setTypeface(semibold);
        headerText.setTextSize(30f);
        ColorMatrix grey = new ColorMatrix();
        grey.setSaturation(0f);
        hiddenArt.setColorFilter(new ColorMatrixColorFilter(grey));
        folderBlur.setColorFilter(new LightingColorFilter(0xFFB9B9B9, 0)); // 27% darker
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
        hintChevron.moveTo(-24.5f, 4f); // tvOS's: wide and shallow
        hintChevron.lineTo(0f, -4f);
        hintChevron.lineTo(24.5f, 4f);
        downChevron.moveTo(-6f, -3f);
        downChevron.lineTo(0f, 3f);
        downChevron.lineTo(6f, -3f);
        pagerChevron.moveTo(-5f, -10f); // pointing right; mirrored for left
        pagerChevron.lineTo(5f, 0f);
        pagerChevron.lineTo(-5f, 10f);
        a11y = new A11y(this, new Accessible());
        setupFlow = new SetupFlow(context, new SetupFlow.Host() {
            @Override public Controls.Setup state() { return HomeView.this.host.setupState(); }
            @Override public boolean hasAccount() { return HomeView.this.host.hasAccount(); }
            @Override public void openAbout() { HomeView.this.host.openAbout(); }
            @Override public void openDeveloperOptions() { HomeView.this.host.openDeveloperOptions(); }
            @Override public void setUpHome(SelfSetup progress) { HomeView.this.host.selfSetupHome(progress); }
            @Override public void requestDefaultHome() { HomeView.this.host.requestDefaultHome(); }
            @Override public void chooseAccount() { HomeView.this.host.chooseAccount(); }
            @Override public void close() { closeSetup(); }
            @Override public void sound(int effect) { playSoundEffect(effect); }

            @Override
            public void changed() {
                kick();
                a11y.sync();
            }
        });
        settings = new SettingsScreen(context, new SettingsScreen.Host() {
            @Override public Controls.Setup setupState() { return HomeView.this.host.setupState(); }
            @Override public void setUpHome() { closeSettings(); openSetupSteps(); }
            @Override public void restoreHome(SelfSetup progress) { HomeView.this.host.restoreTvHome(progress); }
            @Override public void releasePower() { HomeView.this.host.releasePower(); }
            @Override public String account() { return HomeView.this.host.account(); }
            @Override public void chooseAccount() { HomeView.this.host.chooseAccount(); }
            @Override public String picture() { return HomeView.this.host.profilePicture(); }
            @Override public void setPicture(String picture) { HomeView.this.host.setProfilePicture(picture); }
            @Override public boolean light() { return light; }
            @Override public void setLight(boolean on) { HomeView.this.host.setLightAppearance(on); }
            @Override public boolean use24Hour() { return HomeView.this.host.use24Hour(); }
            @Override public void setUse24Hour(boolean on) { HomeView.this.host.setUse24Hour(on); }
            @Override public boolean open(int what) { return HomeView.this.host.openFromSettings(what); }
            @Override public boolean canOpen(int what) { return HomeView.this.host.canOpenFromSettings(what); }
            @Override public Donation donation() { return HomeView.this.host.donation(); }
            @Override public void close() { closeSettings(); }
            @Override public void sound(int effect) { playSoundEffect(effect); }

            @Override
            public void changed() {
                kick();
                a11y.sync();
            }
        });
        light = host.lightAppearance();
    }

    /** Settings > Appearance changed (or was read): the rows of programs and Settings follow. */
    void setLight(boolean on) {
        light = on;
        kick();
    }

    // ---- State from the activity -------------------------------------------------------------

    /** False while editing: the arrangement being edited wins, and the caller retries later. */
    boolean setApps(List<Apps.Entry> visible, List<Apps.Entry> hiddenApps) {
        if (editing || renaming) return false;
        Apps.Entry was = focused(), wasInFolder = folderEntry();
        String folderKey = openFolder != null ? openFolder.pkg : null;
        apps.clear();
        apps.addAll(visible);
        hidden.clear();
        hidden.addAll(hiddenApps);
        if (apps.isEmpty() && !hidden.isEmpty()) {
            // Everything hidden would leave nothing to focus, and no way back to Hidden: keep one out.
            apps.add(hidden.remove(0));
            host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
        }
        if (hidden.isEmpty()) showHidden = false;
        focus = Math.max(0, Math.min(focus, apps.size() - 1));
        if (was != null) {
            // Back on the same app, among the hidden ones too while Hidden is open.
            for (int i = 0; i < total(); i++) {
                if (entry(i).pkg.equals(was.pkg)) {
                    focus = i;
                    break;
                }
            }
        }
        if (folderKey != null) reopenFolder(folderKey, wasInFolder);
        if (menuOpen && !onScreen(menuEntry)) closeMenu();
        if (alertEntry != null && !onScreen(alertEntry)) alertEntry = null;
        focusChanged();
        a11y.sync();
        return true;
    }

    /** After a reload: the same folder (a new object now), on the same app, or closed if it's gone. */
    private void reopenFolder(String key, Apps.Entry was) {
        Apps.Entry tile = null;
        for (Apps.Entry e : apps) if (e.pkg.equals(key)) tile = e;
        if (tile == null) {
            openFolder = null;
            return;
        }
        openFolder = shownFolder = tile;
        List<Apps.Entry> in = tile.folder.apps;
        int at = -1;
        for (int i = 0; was != null && i < in.size(); i++) if (in.get(i).pkg.equals(was.pkg)) at = i;
        folderFocus = at >= 0 ? at : Math.min(folderFocus, in.size() - 1);
    }

    /** Whether a tile is still on the home screen, in Hidden, or in a folder. */
    private boolean onScreen(Apps.Entry e) {
        if (e == null) return false;
        if (apps.contains(e) || hidden.contains(e)) return true;
        for (Apps.Entry t : apps) if (t.folder != null && t.folder.apps.contains(e)) return true;
        return false;
    }

    void setBackdrop(Backdrops.Backdrop b) {
        if (b == null || b == backdrop) return;
        previous = backdrop;
        backdrop = b;
        fade = previous == null ? 1f : 0f;
        hintTint = vibrant(b.hintColor);
        if (expanded) page = pageOf(b.program);
        setRow(b.row != null ? b.row : Collections.<Backdrops.Card>emptyList());
        kick();
    }

    /**
     * A new row of cards: keep the focused card where it was if it's still there (more cards just
     * arrived), else start again at the first. Section titles are worked out here, not per frame.
     */
    private void setRow(List<Backdrops.Card> cards) {
        if (cards == rowCards) return;
        String was = rowIndex < rowCards.size() ? rowCards.get(rowIndex).item().image() : null;
        boolean same = !cards.isEmpty() && !rowCards.isEmpty() && cards.get(0).item().pkg().equals(rowCards.get(0).item().pkg());
        rowCards = cards;
        rowFocus = new float[cards.size()];
        rowHeaders = new String[cards.size()];
        rowHeaderWidths = new float[cards.size()];
        int at = 0;
        for (int i = 0; i < cards.size(); i++) {
            Recommendations.Item item = cards.get(i).item();
            if (same && item.image().equals(was)) at = i;
            String section = item.section();
            if (i == 0 || !java.util.Objects.equals(section, cards.get(i - 1).item().section())) {
                rowHeaders[i] = section == null ? getContext().getString(R.string.continue_watching)
                        : section.isEmpty() ? getContext().getString(R.string.recommendations) : section;
                rowHeaderWidths[i] = rowHeaderText.measureText(rowHeaders[i]);
            }
        }
        rowTitles = new String[cards.size()];
        rowTitleWidths = new float[cards.size()];
        for (int i = 0; i < cards.size(); i++) {
            String title = cards.get(i).item().title();
            rowTitles[i] = title == null ? "" : title.replace('\n', ' ');
            rowTitleWidths[i] = rowTitleText.measureText(rowTitles[i]);
        }
        marqueeCard = -1;
        if (!same) {
            rowIndex = 0;
            rowScroll = 0f;
        } else {
            rowIndex = at;
            if (rowFocused) rowFocus[at] = 1f;
        }
    }

    /** The focused app's top shelf has more pages ready (downloaded since). */
    void shelfPagesChanged() {
        if (!expanded) return;
        Apps.Entry e = focused();
        pages = e != null ? host.shelfPages(e) : Collections.emptyList();
        page = pageOf(backdrop != null ? backdrop.program : null);
        kick();
    }

    private int pageOf(Recommendations.Item program) {
        for (int i = 0; program != null && i < pages.size(); i++) if (pages.get(i).image().equals(program.image())) return i;
        return 0;
    }

    /** The paging controls appear, and fade a moment later. */
    private void showPager() {
        pagerShown = true;
        removeCallbacks(hidePager);
        postDelayed(hidePager, PAGER_SHOWN_MS);
        kick();
    }

    /**
     * Text over the backdrop the way tvOS tints it: the colour behind, much lighter and a little
     * less saturated (green field, pale green text); over a bright picture, much darker instead.
     */
    private static int vibrant(int behind) {
        float[] hsv = new float[3];
        Color.colorToHSV(behind, hsv);
        hsv[1] *= 0.83f;
        hsv[2] = luminance(behind) > 0.62f ? hsv[2] * 0.32f : Math.min(1f, 0.62f + 0.55f * hsv[2]);
        return Color.HSVToColor(hsv);
    }

    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /** The account's avatar (already round), shown in the clock pill. */
    void setAvatar(Bitmap round) {
        avatar = round;
        kick();
    }

    /** Home pressed while already home: back to the top row, everything closed. */
    void goHome() {
        if (setupOpen && !setupFlow.staysOnHome()) closeSetup(); // setup waits for the user to come back to it
        closeSettings();
        alertEntry = null;
        closeMenu();
        if (openFolder != null && !renaming) closeFolder();
        ccOpen = false;
        pillFocused = false;
        if (editing) finishEditing();
        setExpanded(false);
        rowFocused = false;
        showHidden = false;
        downHeld = false;
        if (!apps.isEmpty()) focus = Math.min(focus, apps.size() - 1) % COLS;
        focusChanged();
    }

    /** Opens the Control Center (the clock pill was selected). */
    void openControlCenter() {
        closeMenu();
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
        removeCallbacks(hidePager);
        removeCallbacks(marqueeWake);
        removeCallbacks(hideToast);
        super.onDetachedFromWindow();
    }

    void setClockRunning(boolean run) {
        removeCallbacks(tick);
        if (run) tick();
    }

    /** Settings > Time Format changed. */
    void timeFormatChanged() {
        removeCallbacks(tick);
        tick();
    }

    private void tick() {
        // "9:41 AM" as on tvOS, in the TV's own language and conventions, 12- or 24-hour as Settings says.
        Locale locale = Locale.getDefault();
        String pattern = DateFormat.getBestDateTimePattern(locale, host.use24Hour() ? "Hm" : "hm");
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale); // once a minute: cheap
        format.setTimeZone(TimeZone.getDefault()); // follow time-zone changes
        // AM and PM in capitals, as tvOS writes them, whatever the language's own case ("pm" in English (India)).
        DateFormatSymbols symbols = format.getDateFormatSymbols();
        String[] ampm = symbols.getAmPmStrings();
        for (int i = 0; i < ampm.length; i++) ampm[i] = ampm[i].toUpperCase(locale);
        symbols.setAmPmStrings(ampm);
        format.setDateFormatSymbols(symbols);
        time = format.format(new Date());
        timeWidth = clockText.measureText(time);
        if (ccOpen) cc.setState(host.controlState()); // sleep-timer countdown, network changes
        kick();
        long now = System.currentTimeMillis();
        postDelayed(tick, 60_000 - now % 60_000 + 20);
    }

    // ---- Input -------------------------------------------------------------------------------

    /** Every key can move focus or change what's on screen, so screen readers hear about it after each. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean handled = super.dispatchKeyEvent(e);
        a11y.sync();
        return handled;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocused) {
        super.onFocusChanged(gainFocus, direction, previouslyFocused);
        if (gainFocus) a11y.sync();
    }

    @Override
    public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return a11y;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (downHeld && code != KeyEvent.KEYCODE_DPAD_DOWN) downHeld = false; // another key cancels the reveal
        if (setupOpen) {
            if (isSelect(code)) return true; // acts on release, like the menu
            return setupFlow.key(code) || super.onKeyDown(code, e);
        }
        if (settingsOpen) {
            if (isSelect(code)) return true; // acts on release, like the menu
            return settings.key(code) || super.onKeyDown(code, e);
        }
        if (alertEntry != null) {
            if (isSelect(code)) return true; // acts on release, like the menu
            return alertKey(code) || super.onKeyDown(code, e);
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
        if (openFolder != null) return folderKey(code) || super.onKeyDown(code, e);
        if (ccOpen) {
            return switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT -> {
                    ccMove(-1, 0);
                    yield true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    ccMove(1, 0);
                    yield true;
                }
                case KeyEvent.KEYCODE_DPAD_UP -> {
                    ccMove(0, -1);
                    yield true;
                }
                case KeyEvent.KEYCODE_DPAD_DOWN -> {
                    ccMove(0, 1);
                    yield true;
                }
                case KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> {
                    closeControlCenter();
                    yield true;
                }
                default -> super.onKeyDown(code, e);
            };
        }
        if (pillFocused) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN: // back where Up came from: the shelf's Play button, or the dock
                    navSound(0, 1);
                    pillFocused = false;
                    kick();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    pillFocused = false;
                    setExpanded(false);
                    rowFocused = false;
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
        if (expanded || rowFocused) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_BACK:
                    if (code == KeyEvent.KEYCODE_DPAD_DOWN) navSound(0, 1);
                    setExpanded(false);
                    rowFocused = false;
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    navSound(0, -1);
                    focusPill();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    int d = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1;
                    if (expanded) turnPage(d);
                    else if (rowIndex + d >= 0 && rowIndex + d < rowCards.size()) { // along the row, no wrapping
                        navSound(d, 0);
                        rowIndex += d;
                        kick();
                    }
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
        if (isSelect(code) && ccOpen && cc.selected == ControlCenter.POWER) {
            longPressed = true; // so the release doesn't also power off
            playSoundEffect(SoundEffectConstants.CLICK);
            host.releasePower();
            return true;
        }
        if (isSelect(code) && !editing && !expanded && !rowFocused && !menuOpen && !setupOpen && !pillFocused && !ccOpen
                && alertEntry == null && (openFolder == null || folderFocus >= 0)) {
            longPressed = true;
            openMenu();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isSelect(code)) {
            if (setupOpen) {
                if (!longPressed) setupFlow.select();
            } else if (settingsOpen) {
                if (!longPressed) settings.select();
            } else if (alertEntry != null) {
                if (!longPressed) activateAlert();
            } else if (menuOpen) {
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

    /**
     * D-pad movement over the grid. The hidden apps form a second section below the visible ones,
     * shown while an app is being moved or once revealed; moving a picked-up app between the
     * sections hides or unhides it.
     */
    private void move(int dx, int dy) {
        int v = apps.size(), total = total();
        if (total == 0) { // nothing to focus (it shouldn't happen): Up still reaches the clock pill
            if (dy < 0 && !editing) {
                navSound(0, -1);
                focusPill();
            }
            return;
        }
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
            } else if (!inHidden && (editing ? v > 1 && apps.get(local).folder == null : showHidden && !hidden.isEmpty())) {
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
            } else if (shelf == ROW_SHELF) {
                rowFocused = true;
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
        if (openFolder != null) {
            playSoundEffect(SoundEffectConstants.CLICK);
            if (editing) {
                finishEditing();
            } else if (folderFocus < 0) {
                startRename();
            } else {
                Apps.Entry app = openFolder.folder.apps.get(folderFocus);
                host.launch(app, tileRect(app, -folderScroll));
            }
            return;
        }
        Apps.Entry e = focused();
        if (e == null) return;
        playSoundEffect(SoundEffectConstants.CLICK);
        if (editing) {
            finishEditing(); // drop it here, and that's the edit done
            return;
        }
        if (e.folder != null) {
            openFolder(e, 0);
            return;
        }
        Recommendations.Item program = rowFocused && rowIndex < rowCards.size() ? rowCards.get(rowIndex).item()
                : backdrop != null ? backdrop.program : null;
        if ((expanded || rowFocused) && program != null && program.pkg().equals(e.pkg)) {
            // Play on the top shelf: what's shown (as on tvOS), zooming out of the picture, or the focused card.
            RectF out = expanded ? new RectF(0, 0, W, height) : rowCardRect(rowIndex, 1f, new RectF());
            Rect from = new Rect(Math.round(out.left * scale), Math.round(out.top * scale),
                    Math.round(out.right * scale), Math.round(out.bottom * scale));
            if (program.intentUri() != null) host.launchProgram(program, from); else host.launch(e, from);
            setExpanded(false);
            rowFocused = false;
            return;
        }
        host.launch(e, tileRect(e, lift() - scroll));
    }

    /** A focused tile's on-screen rect, so the system zooms the app it launches out of it. */
    private Rect tileRect(Apps.Entry e, float offset) {
        float w = TILE_W * FOCUS_SCALE, h = TILE_H * FOCUS_SCALE;
        float cx = e.x + TILE_W / 2f, cy = e.y + TILE_H / 2f + offset;
        return new Rect(Math.round((cx - w / 2) * scale), Math.round((cy - h / 2) * scale),
                Math.round((cx + w / 2) * scale), Math.round((cy + h / 2) * scale));
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

    /**
     * The full-screen top shelf, as on tvOS: the dock and clock slide away and the picture fills the
     * screen. With pages to turn, arrows and page dots show for a moment. Select plays what's shown.
     */
    private void setExpanded(boolean on) {
        if (on == expanded) {
            kick(); // the caller may have changed focus around it (Down out of the row, say): redraw
            return;
        }
        expanded = on;
        if (on) {
            Apps.Entry e = focused();
            pages = e != null ? host.shelfPages(e) : Collections.emptyList();
            page = pageOf(backdrop != null ? backdrop.program : null);
            showPager();
        } else {
            removeCallbacks(hidePager);
            pagerShown = false;
        }
        kick();
    }

    /** Left or Right on the full-screen shelf: the app's previous or next page (no wrapping, as on tvOS). */
    private void turnPage(int d) {
        Apps.Entry e = focused();
        int to = page + d;
        if (e != null && to >= 0 && to < pages.size()) {
            navSound(d, 0);
            page = to;
            host.showShelfPage(e, pages.get(to));
        }
        showPager();
    }

    /** The top shelf showing for the focused dock app (only once its backdrop is the one on screen). */
    private int shelf() {
        Apps.Entry e = focused();
        Backdrops.Backdrop b = backdrop;
        if (e == null || b == null || b.program == null || !inDock() || !b.program.pkg().equals(e.pkg)) return NO_SHELF;
        return b.row != null ? ROW_SHELF : FULL_SHELF;
    }

    /**
     * Edit Home Screen, as on tvOS: the chosen app is picked up and wiggles, the arrows move it (into
     * or out of Hidden too), and select (or Back) drops it and ends the edit.
     */
    private void startEditing() {
        editing = true;
        editFromHidden = focus >= apps.size();
        kick();
    }

    private void finishEditing() {
        editing = false;
        host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
        if (openFolder != null) {
            kick();
            return;
        }
        // Dropped among the hidden apps: stay there. Otherwise, Hidden folds away.
        showHidden = focus >= apps.size() && !hidden.isEmpty();
        if (showHidden && !editFromHidden) showHiddenToast();
        if (focus >= total()) focus = Math.max(0, apps.size() - 1);
        focusChanged();
    }

    /** Held Down long enough on the last row: drop into the Hidden section with a little bounce. */
    private void revealHidden() {
        toastShown = false;
        downHeld = false;
        swallowDown = true;
        showHidden = true;
        focus = apps.size() + Math.min(focus % COLS, hidden.size() - 1);
        bounceAt = SystemClock.uptimeMillis();
        navSound(0, 1);
        focusChanged();
        a11y.sync(); // the hold finishes between key events
    }

    private boolean canRevealHidden() {
        int v = apps.size();
        return !editing && !showHidden && !hidden.isEmpty() && focus < v && focus / COLS == (v - 1) / COLS;
    }

    // ---- Folders -----------------------------------------------------------------------------

    /** Opens a folder over the home screen, as on tvOS, focused on one of its apps. */
    private void openFolder(Apps.Entry tile, int at) {
        openFolder = shownFolder = tile;
        folderName = tile.label;
        folderFocus = Math.max(0, Math.min(at, tile.folder.apps.size() - 1));
        folderScroll = 0f;
        for (Apps.Entry e : tile.folder.apps) e.x = Float.NaN; // laid out afresh inside it
        closeMenu();
        kick();
        a11y.sync();
    }

    private void closeFolder() {
        if (editing) finishEditing();
        openFolder = null;
        focusChanged();
    }

    /** The app focused inside the open folder, or null (none open, or its name is focused). */
    private Apps.Entry folderEntry() {
        return openFolder != null && folderFocus >= 0 && folderFocus < openFolder.folder.apps.size()
                ? openFolder.folder.apps.get(folderFocus) : null;
    }

    /** Keys inside an open folder (select is handled on release, in {@link #select}). */
    private boolean folderKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT -> folderMove(-1, 0);
            case KeyEvent.KEYCODE_DPAD_RIGHT -> folderMove(1, 0);
            case KeyEvent.KEYCODE_DPAD_UP -> folderMove(0, -1);
            case KeyEvent.KEYCODE_DPAD_DOWN -> folderMove(0, 1);
            case KeyEvent.KEYCODE_MENU -> {
                if (!editing && folderFocus >= 0) openMenu();
            }
            case KeyEvent.KEYCODE_BACK -> {
                if (editing) finishEditing(); else closeFolder();
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /** Arrows in a folder: its apps four to a row, its name above them. A picked-up app moves along. */
    private void folderMove(int dx, int dy) {
        List<Apps.Entry> in = openFolder.folder.apps;
        int n = in.size(), at = folderFocus;
        if (at < 0) {
            if (dy <= 0 || n == 0) return;
            folderFocus = 0;
        } else {
            int col = at % FOLDER_COLS, row = at / FOLDER_COLS, to;
            if (dx != 0) {
                if (col + dx < 0 || col + dx >= FOLDER_COLS || at + dx >= n) return;
                to = at + dx;
            } else if (dy < 0) {
                if (row == 0) {
                    if (editing) return;
                    folderFocus = -1; // up to its name
                    navSound(dx, dy);
                    kick();
                    return;
                }
                to = at - FOLDER_COLS;
            } else {
                if ((row + 1) * FOLDER_COLS >= n) return;
                to = Math.min(at + FOLDER_COLS, n - 1);
            }
            if (editing) in.add(to, in.remove(at));
            folderFocus = to;
        }
        navSound(dx, dy);
        kick();
    }

    /** Selecting a folder's name: type a new one on the TV's keyboard, shown live in its place. */
    private void startRename() {
        Apps.Entry tile = openFolder;
        renaming = true;
        folderName = tile.label;
        host.editText(tile.label, new TextEdit() {
            @Override
            public void changed(String text) {
                folderName = text;
                kick();
            }

            @Override
            public void done(String text) {
                renaming = false;
                String name = text.trim();
                if (!name.isEmpty()) {
                    tile.label = name;
                    tile.shortLabel = null;
                    host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
                }
                folderName = tile.label;
                kick();
                a11y.sync();
            }
        });
        kick();
    }

    private int folderRows() {
        return Math.max(1, (int) ((folderBottom() - FOLDER_TOP - 44f) / ROW_PITCH));
    }

    private float folderBottom() {
        return height - 60f;
    }

    private static float folderSlotX(int i) {
        return (W - FOLDER_W) / 2f + FOLDER_PAD + (i % FOLDER_COLS) * (TILE_W + FOLDER_GAP);
    }

    private static float folderSlotY(int i) {
        return FOLDER_TOP + 44f + (i / FOLDER_COLS) * ROW_PITCH;
    }

    // ---- Move to… ----------------------------------------------------------------------------

    /**
     * Takes an app out of wherever it is: the home screen, Hidden, or a folder (a folder left
     * empty goes too). Returns where it was in that list.
     */
    private int detach(Apps.Entry e) {
        int at = apps.indexOf(e);
        if (at >= 0) {
            apps.remove(at);
            return at;
        }
        at = hidden.indexOf(e);
        if (at >= 0) {
            hidden.remove(at);
            return at;
        }
        for (int i = 0; i < apps.size(); i++) {
            Apps.Entry t = apps.get(i);
            if (t.folder == null || !t.folder.apps.remove(e)) continue;
            if (t.folder.apps.isEmpty()) apps.remove(i);
            return i;
        }
        return -1;
    }

    private void saveLayout() {
        host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
    }

    /** New Folder: a folder in the app's place holding it, opened on it, as on tvOS. */
    private void moveToNewFolder(Apps.Entry e) {
        boolean wasHidden = hidden.contains(e);
        int at = detach(e);
        Apps.Entry folder = Apps.newFolder(getContext(), e, apps);
        apps.add(wasHidden || at < 0 ? apps.size() : Math.min(at, apps.size()), folder);
        if (wasHidden && hidden.isEmpty()) showHidden = false;
        focus = apps.indexOf(folder);
        saveLayout();
        focusChanged();
        openFolder(folder, 0);
    }

    /** Into a folder: added at its end, and the folder opens on it. */
    private void moveToFolder(Apps.Entry e, Apps.Entry folder) {
        boolean wasHidden = hidden.contains(e);
        detach(e);
        folder.folder.apps.add(e);
        if (wasHidden && hidden.isEmpty()) showHidden = false;
        focus = Math.max(0, apps.indexOf(folder));
        saveLayout();
        focusChanged();
        openFolder(folder, folder.folder.apps.size() - 1);
    }

    /**
     * Whether {@code e} can go to Hidden and still leave a tile on the home screen: not the last
     * tile, nor the only app of a folder that's the last tile (the folder would go with it).
     */
    private boolean canHide(Apps.Entry e) {
        if (hidden.contains(e)) return false;
        if (apps.size() > 1) return true;
        if (apps.isEmpty() || apps.contains(e)) return false;
        Apps.Entry last = apps.get(0);
        return last.folder != null && last.folder.apps.size() > 1;
    }

    /** Into Hidden: greyed out at its end; at least one tile stays on the home screen. */
    private void moveToHidden(Apps.Entry e) {
        if (!canHide(e)) return;
        Apps.Entry folder = openFolder;
        detach(e);
        hidden.add(e);
        saveLayout();
        showHiddenToast();
        if (folder != null) {
            if (!apps.contains(folder)) {
                openFolder = null; // that was its last app
            } else {
                folderFocus = Math.min(folderFocus, folder.folder.apps.size() - 1);
            }
        }
        focus = Math.max(0, Math.min(focus, apps.size() - 1));
        focusChanged();
    }

    /**
     * Back onto the home screen, from a folder (next to it, or in its place if it was the last app)
     * or from Hidden (at the end). As on tvOS it lands picked up, to be put where it belongs.
     */
    private void moveToHome(Apps.Entry e) {
        int at;
        if (hidden.contains(e)) {
            hidden.remove(e);
            if (hidden.isEmpty()) showHidden = false;
            at = apps.size();
        } else {
            Apps.Entry folder = openFolder;
            int was = detach(e);
            at = folder != null && apps.contains(folder) ? apps.indexOf(folder) + 1 : Math.max(0, was);
            openFolder = null;
        }
        at = Math.min(at, apps.size());
        apps.add(at, e);
        if (Float.isNaN(e.x)) e.x = slotX(at);
        showHidden = false;
        focus = at;
        saveLayout();
        focusChanged();
        startEditing();
    }

    // ---- Long-press menu ---------------------------------------------------------------------

    private void openMenu() {
        Apps.Entry e = openFolder != null ? folderEntry() : focused();
        if (e == null) return;
        menuEntry = e;
        buildMenu(e);
        menuSel = 0;
        menuSelAnim = 0f;
        subOf = -1;
        subAnim = 0f;
        menuOpen = true;
        kick();
    }

    private void closeMenu() {
        menuOpen = false;
        subOf = -1;
        kick();
    }

    /**
     * tvOS's options first (Edit Home Screen, Move to…), then the two top-shelf sources for apps
     * that publish them, App Info, and Delete App for apps that can be uninstalled. A folder can
     * only be moved.
     */
    private void buildMenu(Apps.Entry e) {
        menuRows.clear();
        addRow(menuRows, R.string.edit_home, editIcon, ROW_PLAIN, this::startEditing, null);
        if (e.folder == null) {
            addRow(menuRows, R.string.move_to, null, ROW_SUBMENU, () -> openSubmenu(() -> buildMoveTo(e)), null);
            if (host.shelfSources(e) != 0) {
                addRow(menuRows, R.string.top_shelf, null, ROW_SUBMENU, () -> openSubmenu(() -> buildTopShelf(e)), null);
            }
            addRow(menuRows, R.string.app_info, null, ROW_PLAIN, () -> host.showInfo(e), null);
            if (e.removable) addRow(menuRows, R.string.delete_app, deleteIcon, ROW_DANGER, () -> openAlert(e), null);
        }
        menuWidth = listWidth(menuRows);
    }

    /** Where the app can go: the home screen (from a folder or Hidden), a new folder, other folders, Hidden. */
    private void buildMoveTo(Apps.Entry e) {
        subRows.clear();
        boolean inFolder = openFolder != null, isHidden = hidden.contains(e);
        if (inFolder || isHidden) addRow(subRows, R.string.home_screen, null, ROW_PLAIN, () -> moveToHome(e), null);
        if (!inFolder) addRow(subRows, R.string.new_folder, newFolderIcon, ROW_PLAIN, () -> moveToNewFolder(e), null);
        for (Apps.Entry t : apps) {
            if (t.folder != null && t != openFolder) addRow(subRows, t.label, null, ROW_PLAIN, () -> moveToFolder(e, t), null);
        }
        if (canHide(e)) addRow(subRows, R.string.hidden, null, ROW_PLAIN, () -> moveToHidden(e), null);
        subWidth = listWidth(subRows);
    }

    /** The top shelf's two sources, each a switch: the shelf shows either, both, or neither (then the app's logo). */
    private void buildTopShelf(Apps.Entry e) {
        subRows.clear();
        int sources = host.shelfSources(e);
        if ((sources & Recommendations.WATCH_NEXT) != 0) addSwitch(R.string.continue_watching, e, Recommendations.WATCH_NEXT);
        if ((sources & Recommendations.PREVIEWS) != 0) addSwitch(R.string.recommendations, e, Recommendations.PREVIEWS);
        subWidth = listWidth(subRows);
    }

    private void addSwitch(int label, Apps.Entry e, int source) {
        boolean on = host.shelfSourceOn(e, source);
        addRow(subRows, label, null, ROW_PLAIN, () -> host.setShelfSource(e, source, !on), on);
    }

    private void addRow(List<MenuRow> rows, int label, Drawable icon, int kind, Runnable action, Boolean checked) {
        addRow(rows, getContext().getString(label), icon, kind, action, checked);
    }

    private void addRow(List<MenuRow> rows, String label, Drawable icon, int kind, Runnable action, Boolean checked) {
        rows.add(new MenuRow(fit(label, menuText, 640f), icon, kind, action, checked));
    }

    /** Wide enough for the longest option in any language: the icon column, its text, a chevron. */
    private float listWidth(List<MenuRow> rows) {
        float w = 0f;
        for (MenuRow row : rows) w = Math.max(w, menuText.measureText(row.label()) * 1.04f);
        return Math.min(760f, Math.max(380f, 2 * MENU_PAD + 70f + w + 64f));
    }

    private void openSubmenu(Runnable builder) {
        subBuilder = builder;
        builder.run();
        if (subRows.isEmpty()) return;
        subOf = subFrom = menuSel;
        subSel = 0;
        subSelAnim = 0f;
        kick();
    }

    /** Keys while the menu is open; false lets anything else (volume…) through. */
    private boolean menuKey(int code) {
        boolean sub = subOf >= 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                int n = sub ? subRows.size() : menuRows.size(), at = sub ? subSel : menuSel;
                int to = Math.max(0, Math.min(n - 1, at + dy));
                if (to != at) navSound(0, dy);
                if (sub) subSel = to; else menuSel = to;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Toward the second list opens it; away from it (or from the menu) closes.
                boolean toward = (code == KeyEvent.KEYCODE_DPAD_RIGHT) != subLeftGuess();
                if (sub) {
                    if (!toward) subOf = -1;
                } else if (toward && menuRows.get(menuSel).kind() == ROW_SUBMENU) {
                    menuRows.get(menuSel).action().run();
                } else {
                    closeMenu();
                }
            }
            case KeyEvent.KEYCODE_BACK -> {
                if (sub) subOf = -1; else closeMenu();
            }
            case KeyEvent.KEYCODE_MENU -> closeMenu();
            default -> {
                return false;
            }
        }
        kick();
        return true;
    }

    /** Whether the second list would open to the left: not enough room to the right of the menu. */
    private boolean subLeftGuess() {
        return subOf >= 0 ? subLeft : menuBox.right - 24f + Math.max(subWidth, 380f) > W - 30f;
    }

    private void activateMenu() {
        playSoundEffect(SoundEffectConstants.CLICK);
        if (subOf >= 0) {
            MenuRow row = subRows.get(subSel);
            if (row.checked() != null) {
                row.action().run();
                subBuilder.run(); // a switch: show its new state, and stay open
            } else {
                closeMenu();
                row.action().run();
            }
        } else {
            MenuRow row = menuRows.get(menuSel);
            if (row.kind() == ROW_SUBMENU) {
                row.action().run();
            } else {
                closeMenu();
                row.action().run();
            }
        }
        kick();
    }

    // ---- Delete alert ------------------------------------------------------------------------

    /** "Delete “App”?", as on tvOS: Cancel first and focused, Delete in red. */
    private void openAlert(Apps.Entry e) {
        alertEntry = e;
        alertSel = 0;
        alertSelAnim = 0f;
        float inner = ALERT_W - 2 * ALERT_PAD;
        alertTitle = fit(getContext().getString(R.string.delete_title, e.label), titleText, inner);
        String message = getContext().getString(R.string.delete_message);
        alertMessage = StaticLayout.Builder.obtain(message, 0, message.length(), bodyText, Math.round(inner))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(4f, 1f).build();
        kick();
        a11y.sync();
    }

    private boolean alertKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                int to = code == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                if (to != alertSel) navSound(to == 0 ? -1 : 1, 0);
                alertSel = to;
            }
            case KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> alertEntry = null;
            case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { } // nothing above or below
            default -> {
                return false;
            }
        }
        kick();
        return true;
    }

    private void activateAlert() {
        playSoundEffect(SoundEffectConstants.CLICK);
        Apps.Entry e = alertEntry;
        alertEntry = null;
        if (alertSel == 1 && e != null) host.deleteApp(e);
        kick();
    }

    // ---- Setup and Settings ------------------------------------------------------------------

    /** First run: the setup steps, from Welcome. */
    void openSetup() {
        closeAll();
        setupOpen = true;
        setupFlow.open();
        kick();
    }

    /** From Settings: the steps this TV still needs to hand atvOS the Home button. */
    private void openSetupSteps() {
        closeAll();
        setupOpen = true;
        setupFlow.openSteps();
        kick();
        a11y.sync();
    }

    /** Settings, full screen, from the switches in Control Center. */
    void openSettings() {
        closeAll();
        setExpanded(false);
        rowFocused = false;
        settingsOpen = true;
        settings.open();
        kick();
        a11y.sync();
    }

    private void closeSettings() {
        if (!settingsOpen) return;
        settingsOpen = false;
        settings.closed();
        kick();
        a11y.sync();
    }

    private void closeAll() {
        closeMenu();
        alertEntry = null;
        ccOpen = false;
        pillFocused = false;
        if (editing) finishEditing();
    }

    /** Back from Settings, Android's home or account picker, or a package change: the setup moves on. */
    void refreshSetup() {
        if (settingsOpen) settings.refresh();
        if (setupOpen) setupFlow.refresh();
    }

    private void closeSetup() {
        setupOpen = false;
        host.setupDone();
        kick();
        a11y.sync();
    }

    private static String fit(String text, TextPaint paint, float width) {
        return TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString();
    }

    private static Drawable menuIcon(Context c, int res) {
        Drawable d = c.getDrawable(res);
        return d != null ? d.mutate() : null;
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
        // Whole rows: the integer division is the row number.
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
        if (!motion || Math.abs(d) <= eps) return target;
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
        if (rowFocused && shelf != ROW_SHELF) rowFocused = false;
        expand = ease(expand, expanded ? 1f : 0f, 9f, dt, 0.001f);
        rowAnim = ease(rowAnim, rowFocused && !pillFocused && !ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        playFocus = ease(playFocus, expanded && !pillFocused && !ccOpen && !setupOpen && !settingsOpen ? 1f : 0f, 14f, dt, 0.002f);
        for (int i = 0; i < rowFocus.length; i++) {
            rowFocus[i] = ease(rowFocus[i], rowFocused && i == rowIndex && !pillFocused && !ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        }
        // tvOS keeps the focused card in the second place (the first, for the first card).
        if (rowFocused) rowScroll = ease(rowScroll, Math.max(0, rowIndex - 1) * ROW_STEP, 10f, dt, 0.2f);
        stepMarquee();
        toastAnim = ease(toastAnim, toastShown && !settingsOpen && !setupOpen ? 1f : 0f, 10f, dt, 0.002f);
        boolean pager = expanded && pagerShown && pages.size() > 1 && !pillFocused && !ccOpen && !menuOpen && !setupOpen;
        pagerAnim = ease(pagerAnim, pager ? 1f : 0f, pager ? 14f : 5f, dt, 0.002f); // fades away slowly
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
            if (t >= 0.9f || !motion) {
                bounceAt = 0;
            } else {
                bounce = 26f * (float) (Math.exp(-5.5 * t) * Math.sin(t * 2 * Math.PI * 2.2));
                animating = true;
            }
        }
        menuSelAnim = ease(menuSelAnim, menuSel, 20f, dt, 0.002f);
        subSelAnim = ease(subSelAnim, subSel, 20f, dt, 0.002f);
        subAnim = ease(subAnim, menuOpen && subOf >= 0 ? 1f : 0f, 18f, dt, 0.002f);
        alertAnim = ease(alertAnim, alertEntry != null ? 1f : 0f, 16f, dt, 0.002f);
        alertSelAnim = ease(alertSelAnim, alertSel, 20f, dt, 0.002f);
        folderAnim = ease(folderAnim, openFolder != null ? 1f : 0f, 14f, dt, 0.002f);
        if (openFolder != null) {
            List<Apps.Entry> in = openFolder.folder.apps;
            int row = Math.max(0, folderFocus) / FOLDER_COLS;
            folderScroll = ease(folderScroll, Math.max(0, row - (folderRows() - 1)) * ROW_PITCH, 10f, dt, 0.2f);
            folderNameFocus = ease(folderNameFocus, folderFocus < 0 || renaming ? 1f : 0f, 16f, dt, 0.002f);
            for (int i = 0; i < in.size(); i++) {
                Apps.Entry e = in.get(i);
                float sx = folderSlotX(i), sy = folderSlotY(i);
                if (Float.isNaN(e.x)) {
                    e.x = sx;
                    e.y = sy;
                }
                e.x = ease(e.x, sx, 14f, dt, 0.2f);
                e.y = ease(e.y, sy, 14f, dt, 0.2f);
                e.focus = ease(e.focus, i == folderFocus ? 1f : 0f, 14f, dt, 0.002f);
            }
            if (renaming) animating = true; // the caret blinks
        }
        boolean showHint = dock && !expanded && !rowFocused && !menuOpen && !setupOpen && !editing && !pillFocused
                && openFolder == null && alertEntry == null && shelf == FULL_SHELF;
        hint = ease(hint, showHint ? 1f : 0f, 8f, dt, 0.002f);
        menuAnim = ease(menuAnim, menuOpen ? 1f : 0f, 18f, dt, 0.002f);
        setupAnim = ease(setupAnim, setupOpen ? 1f : 0f, 16f, dt, 0.002f);
        if (setupAnim > 0f && setupFlow.step(dt)) animating = true;
        settingsAnim = ease(settingsAnim, settingsOpen ? 1f : 0f, 14f, dt, 0.002f);
        if (settingsAnim > 0f && settings.step(dt)) animating = true;
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
            e.focus = ease(e.focus, i == focus && !expanded && !rowFocused && !pillFocused ? 1f : 0f, 14f, dt, 0.002f);
        }
        if (editing && motion) animating = true; // the picked-up app wiggles
    }

    /**
     * The focused card's title, if it's too long for the card: still for a second, then scrolling
     * round once, then resting two seconds at the start, and again. Frames are drawn only while it
     * moves; a rest ends with a wake-up call.
     */
    private void stepMarquee() {
        int card = rowFocused && !pillFocused && !ccOpen && rowIndex < rowTitles.length
                && rowTitleWidths[rowIndex] > Backdrops.ROW_CARD_W * ROW_FOCUS_SCALE ? rowIndex : -1;
        long now = SystemClock.uptimeMillis();
        if (card != marqueeCard) {
            marqueeCard = card;
            marqueeSince = now;
            marqueeOffset = 0f;
            removeCallbacks(marqueeWake);
        }
        if (card < 0 || !motion) {
            marqueeOffset = 0f;
            return;
        }
        long t = now - marqueeSince - MARQUEE_DELAY_MS;
        long scrolling = Math.round((rowTitleWidths[card] + MARQUEE_GAP) / MARQUEE_SPEED * 1000f);
        long u = t < 0 ? t : t % (scrolling + MARQUEE_REST_MS);
        if (u >= 0 && u < scrolling) {
            marqueeOffset = u * MARQUEE_SPEED / 1000f;
            animating = true;
        } else {
            marqueeOffset = 0f;
            removeCallbacks(marqueeWake);
            postDelayed(marqueeWake, u < 0 ? -u : scrolling + MARQUEE_REST_MS - u);
        }
    }

    /** How far the UI has slid down for the full-screen top shelf: the dock all the way off the screen. */
    private float lift() {
        return expand * (height - (DOCK_ROW_Y - DOCK_PAD_Y) + 40f);
    }

    // ---- Drawing -----------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        scale = w / W;
        height = h / scale;
        bottomScrim.setShader(new LinearGradient(0, height * 0.45f, 0, height, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        // Behind the full-screen shelf's details: darkest at the bottom-left corner, in one pass.
        detailsScrim.setShader(new RadialGradient(0f, height, 1500f, new int[]{0xB8000000, 0x66000000, 0x00000000},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
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
        motion = ValueAnimator.areAnimatorsEnabled();
        step(dt);
        a11y.invalidate(); // things may have moved: TalkBack's next look gets them afresh

        c.save();
        c.scale(scale, scale);
        // An open folder covers the home screen with its blurred backdrop, and Settings covers
        // everything: nothing under them shows.
        if (settingsAnim > 0.999f) {
            settings.draw(c, 1f, height);
            c.restore();
            if (animating) postInvalidateOnAnimation();
            a11y.drawn();
            return;
        }
        boolean covered = folderCover() > 0.999f;
        if (!covered) drawBackground(c);

        float offset = lift() - scroll - bounce;
        if (!covered) {
            drawRow(c, offset);
            if (expand > 0.01f) drawDetails(c);
        }
        // The dock and grid, unless covered by a folder or slid off the screen for the full-screen shelf.
        if (!covered && expand < 0.999f) {
            c.save();
            c.translate(0, offset);
            drawDock(c, offset);
            drawHiddenSection(c);
            int n = total();
            for (int i = 0; i < n; i++) if (i != focus) drawHomeTile(c, i, offset);
            // The menu dims everything but its tile, as on tvOS.
            if (menuAnim > 0f && openFolder == null) c.drawColor(Color.argb(Math.round(100 * menuAnim), 0, 0, 0));
            if (focus < n) drawHomeTile(c, focus, offset);
            drawHint(c);
            drawRevealHint(c);
            c.restore();
        }
        if (pagerAnim > 0.01f && !covered) drawPager(c);

        if (ccAnim > 0f) c.drawColor(Color.argb(Math.round(110 * ccAnim), 0, 0, 0));
        if (!covered) drawClock(c);
        if (ccAnim > 0f) cc.draw(c, ccGlass, ccAnim, ccInk);
        if (folderAnim > 0f) drawFolder(c);
        if (menuAnim > 0f) drawMenu(c);
        if (alertAnim > 0f) drawAlert(c);
        if (toastAnim > 0.01f) drawToast(c);
        if (setupAnim > 0f) setupFlow.draw(c, ccGlass, setupAnim, height);
        if (settingsAnim > 0f) settings.draw(c, settingsAnim, height);
        c.restore();

        if (animating) postInvalidateOnAnimation();
        a11y.drawn(); // what just opened has bounds now, for screen readers
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

    /** A home-screen tile: greyed in Hidden, named when focused below the dock. */
    private void drawHomeTile(Canvas c, int i, float offset) {
        boolean isHidden = i >= apps.size();
        drawTile(c, entry(i), editing && openFolder == null && i == focus, isHidden, i >= COLS || isHidden, offset);
    }

    private void drawTile(Canvas c, Apps.Entry e, boolean held, boolean isHidden, boolean named, float offset) {
        float f = e.focus, s = 1f + (FOCUS_SCALE - 1f) * f + (held ? 0.08f : 0f);
        float w = TILE_W * s, h = TILE_H * s;
        float cx = e.x + TILE_W / 2f, cy = e.y + TILE_H / 2f;
        if (cy + h + offset < 0f || cy - h + offset > height) return;
        r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        float radius = TILE_R * s;

        int save = c.save();
        if (held && motion) {
            // The picked-up app wiggles, as on tvOS.
            double t = SystemClock.uptimeMillis() / 1000.0;
            c.rotate((float) Math.sin(t * Math.PI * 2 * 3.2) * 1.4f, cx, cy);
        }
        if (f > 0.01f && shadowMask != null) {
            // Pre-blurred mask, scaled with the tile: no per-frame GPU blur.
            float pad = SHADOW_PAD * w / (TILE_W * FOCUS_SCALE), dy = SHADOW_DY * f;
            r2.set(r.left - pad, r.top - pad + dy, r.right + pad, r.bottom + pad + dy);
            shadow.setAlpha(Math.round(165 * f * tileFade));
            c.drawBitmap(shadowMask, null, r2, shadow);
        }
        if (e.folder != null) {
            drawFolderTile(c, e, r, radius, offset);
        } else {
            m.setScale(w / e.art.getWidth(), h / e.art.getHeight());
            m.postTranslate(r.left, r.top);
            e.shader.setLocalMatrix(m);
            Paint p = art;
            p.setAlpha(Math.round(255 * tileFade));
            if (isHidden) {
                p = hiddenArt;
                p.setAlpha(Math.round(255 * (0.45f + 0.45f * f) * tileFade));
            }
            p.setShader(e.shader);
            c.drawRoundRect(r, radius, radius, p);
        }
        if (f > 0.01f) {
            m.setScale(1f, h * 0.6f);
            m.postTranslate(0f, r.top);
            sheenGradient.setLocalMatrix(m);
            sheen.setAlpha(Math.round(255 * f * tileFade));
            c.drawRoundRect(r, radius, radius, sheen);
        }
        c.restoreToCount(save);

        // Names show under focused icons in the grid (not in the dock), as on tvOS.
        if (f > 0.01f && named) {
            int a = Math.round(240 * f * tileFade);
            labelText.setColor(Color.argb(a, 255, 255, 255));
            labelText.setShadowLayer(8f, 0f, 1f, Color.argb(a / 2, 0, 0, 0));
            if (e.shortLabel == null) {
                e.shortLabel = TextUtils.ellipsize(e.label, labelText, TILE_W * FOCUS_SCALE + GAP_X, TextUtils.TruncateAt.END).toString();
            }
            c.drawText(e.shortLabel, cx, r.bottom + 38f, labelText);
        }
    }

    /** A folder's tile, as on tvOS: frosted glass holding its first apps in miniature, three to a row. */
    private void drawFolderTile(Canvas c, Apps.Entry e, RectF box, float radius, float offset) {
        drawGlass(c, box, radius, offset, 0x33FFFFFF, 255);
        List<Apps.Entry> in = e.folder.apps;
        float w = box.width(), mw = w * 0.23f, mh = mw * TILE_H / TILE_W, gap = w * 0.04f;
        float padX = (w - 3 * mw - 2 * gap) / 2f, padY = (box.height() - 3 * mh - 2 * gap) / 2f;
        for (int k = 0; k < Math.min(9, in.size()); k++) {
            Apps.Entry a = in.get(k);
            float mx = box.left + padX + (k % 3) * (mw + gap), my = box.top + padY + (k / 3) * (mh + gap);
            r2.set(mx, my, mx + mw, my + mh);
            m.setScale(mw / a.art.getWidth(), mh / a.art.getHeight());
            m.postTranslate(mx, my);
            a.shader.setLocalMatrix(m);
            art.setShader(a.shader);
            c.drawRoundRect(r2, radius * 0.3f, radius * 0.3f, art);
        }
    }

    /**
     * An open folder, as on tvOS: the home screen out of focus behind it (the blurred backdrop over
     * everything), its name in a pill above a large glass panel, its apps four to a row.
     */
    private void drawFolder(Canvas c) {
        Apps.Entry tile = shownFolder;
        int alpha = Math.round(255 * folderAnim);
        if (alpha <= 2 || tile == null) return;
        // The home screen goes out of focus first, quickly, so it can stop being drawn under the
        // folder early on; the blur is darkened as it's drawn (one pass, not a blur and a dim).
        Backdrops.Backdrop b = backdrop;
        if (b != null) {
            r.set(0f, 0f, W, height);
            folderBlur.setAlpha(Math.round(255 * folderCover()));
            c.drawBitmap(b.blur, null, r, folderBlur);
        }
        c.save();
        float k = 0.94f + 0.06f * folderAnim, left = (W - FOLDER_W) / 2f, bottom = folderBottom();
        c.scale(k, k, W / 2f, height / 2f);
        r.set(left, FOLDER_TOP, left + FOLDER_W, bottom);
        drawFrost(c, r, FOLDER_R, 0x55FFFFFF, alpha); // over the blur already: no second blur
        drawFolderName(c, alpha);

        // Its apps, scrolled within the panel, each faded in as it opens (no offscreen layer). The
        // menu dims all but its app.
        c.save();
        c.clipRect(left, FOLDER_TOP, left + FOLDER_W, bottom);
        tileFade = folderAnim;
        List<Apps.Entry> in = tile.folder.apps;
        boolean open = tile == openFolder;
        for (int i = 0; i < in.size(); i++) if (!open || i != folderFocus) drawTile(c, in.get(i), false, false, true, -folderScroll);
        if (open && menuAnim > 0f) c.drawColor(Color.argb(Math.round(90 * menuAnim), 0, 0, 0));
        if (open && folderFocus >= 0 && folderFocus < in.size()) {
            drawTile(c, in.get(folderFocus), editing, false, true, -folderScroll);
        }
        tileFade = 1f;
        c.restore();
        c.restore();
    }

    /** How opaque the out-of-focus home screen behind a folder is: ahead of the folder itself. */
    private float folderCover() {
        return Math.min(1f, folderAnim * 1.6f);
    }

    /** Frosted glass over what's already blurred (an open folder's panel): its tint and rim, no second blur. */
    private void drawFrost(Canvas c, RectF rect, float radius, int tint, int alpha) {
        fill.setColor(tint);
        fill.setAlpha(Color.alpha(tint) * alpha / 255);
        c.drawRoundRect(rect, radius, radius, fill);
        m.setScale(1f, rect.height());
        m.postTranslate(0f, rect.top);
        rimGradient.setLocalMatrix(m);
        rim.setAlpha(alpha);
        c.drawRoundRect(rect, radius, radius, rim);
    }

    /** The folder's name: dark glass, or white while focused (select to rename it); a caret while typing. */
    private void drawFolderName(Canvas c, int alpha) {
        float f = folderNameFocus, grow = 1f + 0.08f * f, size = titleText.getTextSize();
        titleText.setTextSize(38f * grow);
        float tw = titleText.measureText(folderName), w = Math.max(tw, 40f) + 72f * grow, h = FOLDER_NAME_H * grow;
        r.set(W / 2f - w / 2f, FOLDER_NAME_Y - h / 2f, W / 2f + w / 2f, FOLDER_NAME_Y + h / 2f);
        if (f < 0.99f) drawGlass(c, r, h / 2f, 0f, 0x59000000, Math.round(alpha * (1f - f)));
        if (f > 0.01f) {
            fill.setColor(Color.argb(Math.round(alpha * f * 0.96f), 255, 255, 255));
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
        }
        int ink = blend(Color.WHITE, INK, f);
        titleText.setColor(ink);
        titleText.setAlpha(alpha);
        c.drawText(folderName, W / 2f, FOLDER_NAME_Y + 13f * grow, titleText);
        if (renaming && SystemClock.uptimeMillis() / 530 % 2 == 0) {
            fill.setColor(ink);
            fill.setAlpha(alpha);
            float x = W / 2f + tw / 2f + 4f;
            c.drawRect(x, FOLDER_NAME_Y - 20f * grow, x + 3f, FOLDER_NAME_Y + 20f * grow, fill);
        }
        titleText.setTextSize(size);
    }

    /** "Press up for full screen" above the dock, as tvOS words and places it, tinted by the picture behind. */
    private void drawHint(Canvas c) {
        float a = hint * (1f - expand);
        if (a <= 0.01f) return;
        int alpha = Math.round(255 * a), tint = hintTint & 0x00FFFFFF | alpha << 24;
        float dockTop = DOCK_ROW_Y - DOCK_PAD_Y;
        c.save();
        c.translate(W / 2f, dockTop - 60.5f);
        stroke.setStrokeWidth(6f);
        stroke.setColor(tint);
        if (luminance(hintTint) > 0.5f) stroke.setShadowLayer(10f, 0f, 1f, Color.argb(Math.round(alpha * 0.55f), 0, 0, 0));
        c.drawPath(hintChevron, stroke);
        stroke.clearShadowLayer();
        stroke.setStrokeWidth(3f);
        c.restore();
        // tvOS tints the text from each pixel behind it; one colour can't, so a soft shadow keeps it
        // legible over a busy or bright picture.
        boolean light = luminance(hintTint) > 0.5f;
        hintText.setColor(tint);
        hintText.setShadowLayer(10f, 0f, 1f, light ? Color.argb(Math.round(alpha * 0.55f), 0, 0, 0) : 0);
        c.drawText(fullScreenHint, W / 2f, dockTop - 23f, hintText);
    }

    /**
     * The full-screen shelf's paging controls, as tvOS draws them: dark glass circles with a chevron
     * at either side (where there's a page that way), and the page dots in a dark glass capsule.
     */
    private void drawPager(Canvas c) {
        int alpha = Math.round(255 * pagerAnim);
        float cy = height / 2f, rad = PAGER_ARROW_D / 2f;
        for (int side = -1; side <= 1; side += 2) {
            if (side < 0 ? page <= 0 : page >= pages.size() - 1) continue;
            float cx = side < 0 ? PAGER_ARROW_X : W - PAGER_ARROW_X;
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            drawGlass(c, r, rad, 0f, 0x80000000, alpha);
            c.save();
            c.translate(cx + side * 2f, cy);
            c.scale(side, 1f);
            stroke.setStrokeWidth(4f);
            stroke.setColor(Color.argb(alpha, 255, 255, 255));
            c.drawPath(pagerChevron, stroke);
            stroke.setStrokeWidth(3f);
            c.restore();
        }
        float w = (pages.size() - 1) * PAGER_DOT_STEP + 2f * PAGER_DOTS_H, cx = W / 2f;
        float bottom = height - PAGER_DOTS_BOTTOM;
        r.set(cx - w / 2f, bottom - PAGER_DOTS_H, cx + w / 2f, bottom);
        drawGlass(c, r, PAGER_DOTS_H / 2f, 0f, 0x80000000, alpha);
        for (int i = 0; i < pages.size(); i++) {
            float dx = cx + (i - (pages.size() - 1) / 2f) * PAGER_DOT_STEP;
            fill.setColor(Color.WHITE);
            fill.setAlpha(Math.round(alpha * (i == page ? 1f : 0.25f)));
            c.drawCircle(dx, bottom - PAGER_DOTS_H / 2f, PAGER_DOT / 2f, fill);
        }
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
            cc.drawStatus(c, cy, rowAlpha, EDGE - ControlCenter.WIDTH + 6f + timeWidth + 16f);
        }
        Bitmap face = avatar != null ? avatar : defaultAvatar;
        if (face != null) {
            float ax = EDGE - 8f - size, ay = cy - size / 2f;
            r.set(ax, ay, ax + size, ay + size);
            image.setAlpha(alpha);
            c.drawBitmap(face, null, r, image);
        }
    }

    /** Where card {@code i} of the row is, scaled by {@code k} about its centre (before the dock's offset). */
    private RectF rowCardRect(int i, float k, RectF out) {
        float x = ROW_X + i * ROW_STEP - rowScroll, w = Backdrops.ROW_CARD_W, h = Backdrops.ROW_CARD_H;
        float cx = x + w / 2f, cy = ROW_TOP + h / 2f;
        out.set(cx - w * k / 2f, cy - h * k / 2f, cx + w * k / 2f, cy + h * k / 2f);
        return out;
    }

    /**
     * The row shelf, as tvOS draws a Continue Watching row: 16:9 cards over the plain background,
     * each section's title above its first card (held at the left while its cards scroll by), a
     * progress bar on what's partly watched, and the focused card a little larger with its title
     * under it. It moves up with the dock as focus goes into the grid.
     */
    private void drawRow(Canvas c, float offset) {
        Backdrops.Backdrop b = backdrop;
        if (rowCards.isEmpty() || b == null || b.row != rowCards) return;
        int alpha = Math.round(255 * (1f - away));
        if (alpha <= 2) return;
        c.save();
        c.translate(0f, offset);
        float headerY = ROW_HEADER_Y - ROW_HEADER_LIFT * rowAnim;
        rowHeaderText.setColor(light ? 0xFF4D4C4E : 0xFF6A6A6A); // tvOS's, light or dark
        rowHeaderText.setAlpha(alpha);
        for (int i = 0; i < rowCards.size(); i++) {
            if (rowHeaders[i] == null) continue;
            float x = Math.max(ROW_X + 2f, ROW_X + 2f + i * ROW_STEP - rowScroll);
            for (int j = i + 1; j < rowCards.size(); j++) {
                if (rowHeaders[j] == null) continue;
                x = Math.min(x, ROW_X + j * ROW_STEP - rowScroll - rowHeaderWidths[i] - 48f); // the next section pushes it off
                break;
            }
            if (x + rowHeaderWidths[i] > 0f && x < W) c.drawText(rowHeaders[i], x, headerY, rowHeaderText);
        }
        for (int i = 0; i < rowCards.size(); i++) if (i != rowIndex) drawRowCard(c, i, alpha);
        if (rowIndex < rowCards.size()) drawRowCard(c, rowIndex, alpha); // the focused one on top
        c.restore();
    }

    private void drawRowCard(Canvas c, int i, int alpha) {
        Backdrops.Card card = rowCards.get(i);
        float f = rowFocus[i];
        RectF rect = rowCardRect(i, 1f + (ROW_FOCUS_SCALE - 1f) * f, r);
        if (rect.right < 0f || rect.left > W) return;
        float k = rect.width() / Backdrops.ROW_CARD_W;
        if (f > 0.01f && shadowMask != null) { // the focused card lifts on a soft drop shadow
            float pad = SHADOW_PAD * rect.width() / (TILE_W * FOCUS_SCALE), dy = SHADOW_DY * 2f * f;
            r2.set(rect.left - pad, rect.top - pad + dy, rect.right + pad, rect.bottom + pad + dy);
            shadow.setAlpha(Math.round(alpha * (light ? 0.55f : 0.85f) * f));
            c.drawBitmap(shadowMask, null, r2, shadow);
        }
        cardPaint.setAlpha(alpha);
        c.drawBitmap(card.picture(), null, rect, cardPaint); // its corners are rounded already
        if (f > 0.01f) { // and the same glass rim as the dock, the pill and the buttons, as on tvOS
            float radius = Backdrops.ROW_CARD_R * k;
            m.setScale(1f, rect.height());
            m.postTranslate(0f, rect.top);
            rimGradient.setLocalMatrix(m);
            rim.setAlpha(Math.round(alpha * f));
            c.drawRoundRect(rect, radius, radius, rim);
        }
        float p = card.item().progress();
        if (p > 0.01f) { // white on a lighter track, inset along the bottom
            float left = rect.left + ROW_BAR_INSET * k, right = rect.right - ROW_BAR_INSET * k;
            float bottom = rect.bottom - ROW_BAR_BOTTOM * k, h = ROW_BAR_H * k;
            fill.setColor(Color.WHITE);
            fill.setAlpha(Math.round(alpha * 0.55f));
            c.drawRoundRect(left, bottom - h, right, bottom, h / 2f, h / 2f, fill);
            fill.setAlpha(alpha);
            c.drawRoundRect(left, bottom - h, left + Math.max(h, (right - left) * p), bottom, h / 2f, h / 2f, fill);
        }
        if (f > 0.01f && !rowTitles[i].isEmpty()) {
            // White on the light background too, as tvOS does, with a shadow to stand off it.
            TextPaint text = rowTitleWidths[i] <= rect.width() ? rowTitleText : rowMarqueeText;
            text.setColor(Color.WHITE);
            text.setAlpha(Math.round(alpha * f * rowAnim));
            text.setShadowLayer(light ? 6f : 0f, 0f, 1f, light ? Color.argb(Math.round(alpha * 0.5f * f), 0, 0, 0) : 0);
            float y = rect.bottom + 31f;
            if (text == rowTitleText) {
                c.drawText(rowTitles[i], rect.centerX(), y, rowTitleText);
            } else { // too long: from the card's left edge, faded out at its right, and scrolling while focused
                float off = i == marqueeCard ? marqueeOffset : 0f;
                float left = rect.left - MARQUEE_FADE * (1f - Math.min(1f, off / MARQUEE_FADE)); // the left fade, once moving
                m.setScale(rect.right - left, 1f);
                m.postTranslate(left, 0f);
                marqueeFade.setLocalMatrix(m);
                c.save();
                c.clipRect(rect.left, y - 40f, rect.right, y + 14f);
                c.drawText(rowTitles[i], rect.left - off, y, rowMarqueeText);
                if (off > 0f) c.drawText(rowTitles[i], rect.left - off + rowTitleWidths[i] + MARQUEE_GAP, y, rowMarqueeText);
                c.restore();
            }
        }
    }

    /** "Long press (⌄) to enter the Hidden section", on glass at the bottom right, after an app moves there. */
    @SuppressWarnings("deprecation") // announceForAccessibility: a passing note has no node to be a live region of
    private void showHiddenToast() {
        toastShown = true;
        toastWidth = 2 * TOAST_PAD + toastText.measureText(toastBefore) + TOAST_KEY_GAP + TOAST_KEY + TOAST_KEY_GAP
                + toastText.measureText(toastAfter);
        removeCallbacks(hideToast);
        postDelayed(hideToast, TOAST_MS);
        announceForAccessibility(toastSpoken);
        kick();
    }

    private void drawToast(Canvas c) {
        int alpha = Math.round(255 * toastAnim);
        float bottom = height - CLOCK_TOP + 16f * (1f - toastAnim);
        r.set(EDGE - toastWidth, bottom - CLOCK_H, EDGE, bottom);
        drawGlass(c, r, CLOCK_H / 2f, 0f, 0x8C1C1C22, alpha);
        float x = r.left + TOAST_PAD, cy = r.centerY();
        toastText.setAlpha(alpha);
        c.drawText(toastBefore, x, cy + 9f, toastText);
        x += toastText.measureText(toastBefore) + TOAST_KEY_GAP;
        // The remote's Down key: a chevron in a ring
        stroke.setColor(Color.WHITE);
        stroke.setAlpha(alpha);
        stroke.setStrokeWidth(2.2f);
        c.drawCircle(x + TOAST_KEY / 2f, cy, TOAST_KEY / 2f - 1.1f, stroke);
        stroke.setStrokeWidth(2.6f);
        c.save();
        c.translate(x + TOAST_KEY / 2f, cy + 1.5f);
        c.drawPath(downChevron, stroke);
        c.restore();
        stroke.setStrokeWidth(3f);
        x += TOAST_KEY + TOAST_KEY_GAP;
        c.drawText(toastAfter, x, cy + 9f, toastText);
    }

    /** The full-screen shelf's details, laid out for the program shown (once per program, not per frame). */
    private void layOutDetails(Recommendations.Item item) {
        detailsFor = item;
        String title = item.title() == null ? "" : item.title();
        detailsTitle = TextUtils.ellipsize(title, detailsTitleText, DETAILS_W, TextUtils.TruncateAt.END).toString();
        detailsLine = item.details() == null ? null
                : TextUtils.ellipsize(item.details(), detailsLineText, DETAILS_W, TextUtils.TruncateAt.END).toString();
        String text = item.description();
        detailsBody = text == null ? null : StaticLayout.Builder.obtain(text, 0, text.length(), detailsBodyText, 780)
                .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6f, 1f).build();
        long minutes = (item.remainingMillis() + 59_999) / 60_000;
        detailsProgress = item.progress() > 0.01f && minutes > 0 ? item.progress() : 0f;
        detailsPlay = detailsProgress <= 0f ? playLabel
                : minutes >= 60 ? (minutes % 60 == 0 ? getContext().getString(R.string.remaining_h, minutes / 60)
                : getContext().getString(R.string.remaining_hm, minutes / 60, minutes % 60))
                : getContext().getString(R.string.remaining_m, minutes);
        detailsPlayWidth = PLAY_PAD + PLAY_ICON + PLAY_GAP + (detailsProgress > 0f ? PLAY_BAR + PLAY_GAP + 6f : 0f)
                + playText.measureText(detailsPlay) + PLAY_PAD + 6f;
    }

    /**
     * The full-screen shelf's details at the bottom left, as the Apple TV app shows them: the title,
     * a line of facts (the episode, or type, genre, year and length), the description, and Play,
     * over a shade so they read on any picture. Select plays it.
     */
    private void drawDetails(Canvas c) {
        Backdrops.Backdrop b = backdrop;
        Recommendations.Item item = b != null && b.row == null ? b.program : null;
        if (item == null) return;
        if (item != detailsFor) layOutDetails(item);
        int alpha = Math.round(255 * expand);
        detailsScrim.setAlpha(alpha);
        c.drawRect(0f, 0f, W, height, detailsScrim);
        float y = height - DETAILS_BOTTOM - PLAY_H;
        drawPlayButton(c, DETAILS_X, y, alpha);
        y -= 34f;
        if (detailsBody != null) {
            y -= detailsBody.getHeight();
            detailsBodyText.setColor(Color.argb(Math.round(alpha * 0.86f), 255, 255, 255));
            c.save();
            c.translate(DETAILS_X, y);
            detailsBody.draw(c);
            c.restore();
            y -= 22f;
        }
        if (detailsLine != null) {
            detailsLineText.setColor(Color.argb(Math.round(alpha * 0.72f), 255, 255, 255));
            c.drawText(detailsLine, DETAILS_X, y, detailsLineText);
            y -= 25f + 22f;
        }
        detailsTitleText.setColor(Color.argb(alpha, 255, 255, 255));
        c.drawText(detailsTitle, DETAILS_X - 2f, y, detailsTitleText);
    }

    /**
     * Play as the Apple TV app draws it: a white capsule with a black play.fill triangle and "Play";
     * for something partly watched, a progress bar and the time left instead of the word. Liquid
     * glass with white ink while focus is elsewhere (the clock pill, say).
     */
    private void drawPlayButton(Canvas c, float x, float y, int alpha) {
        float f = playFocus;
        r.set(x, y, x + detailsPlayWidth, y + PLAY_H);
        playRect.set(r);
        if (f < 0.99f) drawGlass(c, r, PLAY_H / 2f, 0f, 0x24FFFFFF, Math.round(alpha * (1f - f)));
        if (f > 0.01f) {
            fill.setColor(Color.argb(Math.round(alpha * f * 0.97f), 255, 255, 255));
            c.drawRoundRect(r, PLAY_H / 2f, PLAY_H / 2f, fill);
        }
        int level = Math.round(255 - (255 - 20) * f), ink = Color.argb(alpha, level, level, level);
        float ix = x + PLAY_PAD;
        c.save();
        c.translate(ix, y + (PLAY_H - PLAY_ICON) / 2f);
        fill.setColor(ink);
        fill.setStyle(Paint.Style.FILL_AND_STROKE);
        fill.setStrokeJoin(Paint.Join.ROUND);
        fill.setStrokeWidth(4f);
        c.drawPath(playTriangle, fill);
        fill.setStyle(Paint.Style.FILL);
        fill.setStrokeWidth(0f);
        c.restore();
        float tx = ix + PLAY_ICON + PLAY_GAP;
        if (detailsProgress > 0f) {
            float cy = y + PLAY_H / 2f, h = 6f;
            // The track: light grey on the white capsule, faint white on the glass.
            int track = Math.round(255 - (255 - 204) * f);
            fill.setColor(Color.argb(Math.round(alpha * (0.4f + 0.6f * f)), track, track, track));
            c.drawRoundRect(tx, cy - h / 2f, tx + PLAY_BAR, cy + h / 2f, h / 2f, h / 2f, fill);
            fill.setColor(ink);
            c.drawRoundRect(tx, cy - h / 2f, tx + Math.max(h, PLAY_BAR * detailsProgress), cy + h / 2f, h / 2f, h / 2f, fill);
            tx += PLAY_BAR + PLAY_GAP + 6f;
        }
        playText.setColor(ink);
        c.drawText(detailsPlay, tx, y + PLAY_H / 2f + 10.5f, playText);
    }

    /**
     * The long-press menu, as on tvOS: a glass list beside the tile (to its right, or left where
     * there's no room), the selected option a white capsule gliding between them (red on Delete
     * App). An option with a chevron opens a second list beside it, and keeps a faint capsule.
     */
    private void drawMenu(Canvas c) {
        Apps.Entry e = menuEntry;
        if (e == null || menuRows.isEmpty()) return;
        int alpha = Math.round(255 * menuAnim);
        boolean inFolder = openFolder != null && openFolder.folder.apps.contains(e);
        float offset = inFolder ? -folderScroll : lift() - scroll - bounce;
        float h = 2 * MENU_PAD + MENU_ITEM_H * menuRows.size(), w = menuWidth;
        float tcx = e.x + TILE_W / 2f, tcy = e.y + TILE_H / 2f + offset;
        float tileL = tcx - TILE_W * FOCUS_SCALE / 2f, tileR = tcx + TILE_W * FOCUS_SCALE / 2f;
        float tileT = tcy - TILE_H * FOCUS_SCALE / 2f;
        boolean right = tileR + 18f + w <= W - 30f;
        float left = (right ? tileR + 18f : tileL - 18f - w) + (1f - menuAnim) * (right ? -16f : 16f);
        float top = Math.max(30f, Math.min(tileT - 6f, height - 30f - h));
        menuBox.set(left, top, left + w, top + h);
        drawList(c, menuBox, menuRows, menuSelAnim, subOf, alpha, subOf < 0);

        if (subAnim > 0.01f && !subRows.isEmpty()) {
            float sw = subWidth, sh = 2 * MENU_PAD + MENU_ITEM_H * subRows.size();
            float rowTop = top + MENU_PAD + subFrom * MENU_ITEM_H;
            subLeft = menuBox.right - 24f + sw > W - 30f;
            float sl = (subLeft ? menuBox.left + 24f - sw : menuBox.right - 24f) + (1f - subAnim) * (subLeft ? 12f : -12f);
            float st = Math.max(30f, Math.min(rowTop - MENU_PAD, height - 30f - sh));
            subBox.set(sl, st, sl + sw, st + sh);
            drawList(c, subBox, subRows, subSelAnim, -1, Math.round(alpha * subAnim), subOf >= 0);
        }
    }

    /** One list of the menu. {@code openRow} is the option whose list is open (a faint capsule), or -1. */
    private void drawList(Canvas c, RectF box, List<MenuRow> rows, float selAnim, int openRow, int alpha, boolean active) {
        float pad = MENU_PAD, itemH = MENU_ITEM_H, radius = itemH / 2f;
        // Frosted glass whose corners follow the capsules: light over the dimmed grid, as on tvOS,
        // and darker over a bright backdrop in the dock so the white text still reads.
        int tint = Color.argb(Math.round(0x8C + (0x30 - 0x8C) * away), towardWhite(0x1C, away),
                towardWhite(0x1C, away), towardWhite(0x22, away));
        drawGlass(c, box, radius + pad, 0f, tint, alpha);
        if (openRow >= 0) {
            float y = box.top + pad + openRow * itemH;
            r.set(box.left + pad, y, box.right - pad, y + itemH);
            fill.setColor(Color.argb(Math.round(alpha * 0.16f), 255, 255, 255));
            c.drawRoundRect(r, radius, radius, fill);
        }
        if (active) {
            float y = box.top + pad + selAnim * itemH, danger = 0f;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).kind() == ROW_DANGER) danger += Math.max(0f, 1f - Math.abs(selAnim - i));
            }
            r.set(box.left + pad, y, box.right - pad, y + itemH);
            if (shadowMask != null) {
                float sx = r.width() * 0.12f, sh = r.height() * 0.3f;
                r2.set(r.left - sx, r.top - sh + 8f, r.right + sx, r.bottom + sh + 8f);
                shadow.setAlpha(Math.round(90 * alpha / 255f));
                c.drawBitmap(shadowMask, null, r2, shadow);
            }
            fill.setColor(blend(Color.WHITE, TV_RED, Math.min(1f, danger)));
            fill.setAlpha(Math.round(alpha * 0.98f));
            c.drawRoundRect(r, radius, radius, fill);
        }
        menuText.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < rows.size(); i++) {
            MenuRow row = rows.get(i);
            float cy = box.top + pad + i * itemH + itemH / 2f, size = 30f;
            float sel = active ? Math.max(0f, 1f - Math.abs(selAnim - i)) : 0f;
            int ink = row.kind() == ROW_DANGER ? blend(TV_RED, Color.WHITE, sel) : blend(Color.WHITE, Color.BLACK, sel);
            Drawable icon = row.checked() != null ? (row.checked() ? checkIcon : null) : row.icon();
            if (icon != null) drawTinted(c, icon, box.left + pad + 20f, cy - size / 2f, size, ink, alpha);
            menuText.setColor(ink);
            menuText.setAlpha(alpha);
            c.drawText(row.label(), box.left + pad + 66f, cy + 9f, menuText);
            if (row.kind() == ROW_SUBMENU) drawTinted(c, chevronIcon, box.right - pad - 16f - size, cy - size / 2f, size, ink, alpha);
        }
    }

    private static void drawTinted(Canvas c, Drawable d, float x, float y, float size, int color, int alpha) {
        d.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size));
        d.setTint(color | 0xFF000000);
        d.setAlpha(alpha);
        d.draw(c);
    }

    /** The Delete alert, as on tvOS: frosted glass in the middle, Cancel and Delete side by side. */
    private void drawAlert(Canvas c) {
        int alpha = Math.round(255 * alertAnim);
        if (alpha <= 2 || alertMessage == null) return;
        c.drawColor(Color.argb(Math.round(90 * alertAnim), 0, 0, 0));
        float w = ALERT_W, pad = ALERT_PAD, titleH = 38f;
        float h = pad + titleH + 14f + alertMessage.getHeight() + 34f + ALERT_BUTTON_H + pad;
        float left = (W - w) / 2f, top = (height - h) / 2f - 30f;
        c.save();
        float k = 0.92f + 0.08f * alertAnim;
        c.scale(k, k, W / 2f, top + h / 2f);
        alertBox.set(left, top, left + w, top + h);
        drawGlass(c, alertBox, 44f, 0f, 0x8C1C1C22, alpha);
        titleText.setColor(Color.WHITE);
        titleText.setAlpha(alpha);
        c.drawText(alertTitle, W / 2f, top + pad + 30f, titleText);
        bodyText.setColor(Color.argb(Math.round(alpha * 0.72f), 255, 255, 255));
        drawLayout(c, alertMessage, left + pad, top + pad + titleH + 14f);

        float by = top + h - pad - ALERT_BUTTON_H, gap = 20f, bw = (w - 2 * pad - gap) / 2f;
        menuText.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 2; i++) {
            boolean danger = i == 1;
            float sel = Math.max(0f, 1f - Math.abs(alertSelAnim - i)), grow = 1f + 0.04f * sel;
            float cx = left + pad + i * (bw + gap) + bw / 2f, cy = by + ALERT_BUTTON_H / 2f;
            r.set(cx - bw * grow / 2f, cy - ALERT_BUTTON_H * grow / 2f, cx + bw * grow / 2f, cy + ALERT_BUTTON_H * grow / 2f);
            float radius = r.height() / 2f;
            fill.setColor(danger ? TV_RED : Color.WHITE);
            fill.setAlpha(Math.round(alpha * (danger ? 0.28f : 0.16f)));
            c.drawRoundRect(r, radius, radius, fill);
            if (sel > 0.01f) {
                fill.setAlpha(Math.round(alpha * sel * 0.98f));
                c.drawRoundRect(r, radius, radius, fill);
            }
            menuText.setColor(danger ? blend(TV_RED, Color.WHITE, sel) : blend(Color.WHITE, Color.BLACK, sel));
            menuText.setAlpha(alpha);
            c.drawText(danger ? deleteLabel : cancelLabel, cx, cy + 9f * grow, menuText);
        }
        menuText.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    /** A colour channel moved a fraction {@code t} of the way to white. */
    private static int towardWhite(int a, float t) {
        return Math.round(a + (255 - a) * t);
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static void drawLayout(Canvas c, StaticLayout layout, float x, float y) {
        c.save();
        c.translate(x, y);
        layout.draw(c);
        c.restore();
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
    static Bitmap makeAvatar(int size) {
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

    // ---- Screen readers ----------------------------------------------------------------------

    /**
     * What TalkBack can reach: the open menu or Control Center on its own (they take all input),
     * otherwise the clock pill, the Play button when a shelf shows one, and the app tiles.
     */
    private final class Accessible implements A11y.Source {
        @Override
        public void items(List<A11y.Item> out) {
            Context c = getContext();
            if (setupOpen) {
                setupFlow.items(out, A11Y_SETUP);
                return;
            }
            if (settingsOpen) {
                settings.items(out, A11Y_SETTINGS);
                return;
            }
            if (alertEntry != null) { // Cancel reads out the question too
                float bw = (ALERT_W - 2 * ALERT_PAD - 20f) / 2f, by = alertBox.bottom - ALERT_PAD - ALERT_BUTTON_H;
                for (int i = 0; i < 2; i++) {
                    float bx = alertBox.left + ALERT_PAD + i * (bw + 20f);
                    out.add(new A11y.Item(A11Y_ALERT + i, i == 0 ? cancelLabel : deleteLabel, null,
                            i == 0 ? alertTitle + " " + alertMessage.getText() : null,
                            new RectF(bx, by, bx + bw, by + ALERT_BUTTON_H), i == alertSel));
                }
                return;
            }
            if (menuOpen) {
                rows(out, menuRows, menuBox, A11Y_MENU, subOf < 0 ? menuSel : -1);
                if (subOf >= 0) rows(out, subRows, subBox, A11Y_SUB, subSel);
                return;
            }
            if (openFolder != null) {
                float w = titleText.measureText(folderName) + 72f;
                out.add(new A11y.Item(A11Y_FOLDER, folderName, c.getString(R.string.a11y_folder), c.getString(R.string.a11y_rename_hint),
                        new RectF(W / 2f - w / 2f, FOLDER_NAME_Y - FOLDER_NAME_H / 2f, W / 2f + w / 2f, FOLDER_NAME_Y + FOLDER_NAME_H / 2f),
                        folderFocus < 0));
                List<Apps.Entry> in = openFolder.folder.apps;
                for (int i = 0; i < in.size(); i++) {
                    Apps.Entry e = in.get(i);
                    boolean current = i == folderFocus;
                    out.add(new A11y.Item(A11Y_FOLDER + 1 + i, e.label, editing && current ? c.getString(R.string.a11y_moving) : null,
                            editing && current ? c.getString(R.string.a11y_moving_hint) : null,
                            new RectF(e.x, e.y - folderScroll, e.x + TILE_W, e.y - folderScroll + TILE_H), current));
                }
                return;
            }
            if (ccOpen) {
                for (int id : ControlCenter.READING_ORDER) {
                    out.add(new A11y.Item(A11Y_CC + id, cc.label(id), cc.state(id), null, new RectF(cc.bounds(id)), id == cc.selected));
                }
                return;
            }
            if (inDock() || pillFocused || expanded || rowFocused) {
                float w = 22f + timeWidth + 12f + AVATAR + 8f;
                out.add(new A11y.Item(A11Y_PILL, c.getString(R.string.a11y_control_center), time, null,
                        new RectF(EDGE - w, CLOCK_TOP, EDGE, CLOCK_TOP + CLOCK_H), pillFocused));
            }
            Recommendations.Item program = backdrop != null ? backdrop.program : null;
            if (expanded && program != null) {
                out.add(new A11y.Item(A11Y_PLAY, c.getString(R.string.a11y_play, program.title() == null ? "" : program.title()),
                        pages.size() > 1 ? c.getString(R.string.a11y_page, page + 1, pages.size()) : null, program.description(),
                        new RectF(playRect), !pillFocused));
            }
            if ((inDock() || rowFocused) && !expanded && shelf() == ROW_SHELF) {
                for (int i = 0; i < rowCards.size() && i < A11Y_CC - A11Y_ROW; i++) {
                    Recommendations.Item item = rowCards.get(i).item();
                    String state = item.progress() > 0.01f ? c.getString(R.string.a11y_watched, Math.round(item.progress() * 100)) : null;
                    out.add(new A11y.Item(A11Y_ROW + i, item.title() == null ? "" : item.title(), state, item.details(),
                            rowCardRect(i, 1f, new RectF()), rowFocused && i == rowIndex && !pillFocused));
                }
            }
            float offset = lift() - scroll - bounce;
            boolean onApps = !pillFocused && !expanded && !rowFocused;
            for (int i = 0, n = total(); i < n; i++) {
                Apps.Entry e = entry(i);
                boolean current = onApps && i == focus, isHidden = i >= apps.size();
                String state = isHidden ? c.getString(R.string.a11y_hidden) : editing && current ? c.getString(R.string.a11y_moving)
                        : e.folder != null ? c.getString(R.string.a11y_folder) : null;
                String hint = null;
                if (current && editing) {
                    hint = c.getString(R.string.a11y_moving_hint);
                } else if (current && shelf() != NO_SHELF && program != null && program.title() != null) {
                    hint = c.getString(R.string.a11y_shelf_hint, program.title());
                } else if (current && canRevealHidden()) {
                    hint = c.getString(R.string.a11y_reveal_hint);
                }
                out.add(new A11y.Item(A11Y_APP + i, e.label, state, hint,
                        new RectF(e.x, e.y + offset, e.x + TILE_W, e.y + offset + TILE_H), current));
            }
        }

        /** A menu list's options, with a switch's state. */
        private void rows(List<A11y.Item> out, List<MenuRow> rows, RectF box, int base, int selected) {
            Context c = getContext();
            for (int i = 0; i < rows.size(); i++) {
                MenuRow row = rows.get(i);
                float top = box.top + MENU_PAD + i * MENU_ITEM_H;
                String state = row.checked() == null ? null : c.getString(row.checked() ? R.string.on : R.string.off);
                out.add(new A11y.Item(base + i, row.label(), state, null,
                        new RectF(box.left, top, box.right, top + MENU_ITEM_H), i == selected));
            }
        }

        @Override
        public boolean focus(int id) {
            if (id >= A11Y_APP) {
                int i = id - A11Y_APP;
                if (setupOpen || menuOpen || ccOpen || alertEntry != null || openFolder != null || i >= total()) return false;
                pillFocused = false;
                rowFocused = false;
                if (expanded) setExpanded(false);
                focus = i;
                focusChanged();
            } else if (id >= A11Y_FOLDER) {
                int i = id - A11Y_FOLDER - 1;
                if (openFolder == null || menuOpen || alertEntry != null || i >= openFolder.folder.apps.size()) return false;
                folderFocus = i;
            } else if (id >= A11Y_SETTINGS) {
                if (!settingsOpen || !settings.focus(id - A11Y_SETTINGS)) return false;
            } else if (id >= A11Y_SETUP) {
                if (!setupOpen || !setupFlow.focus(id - A11Y_SETUP)) return false;
            } else if (id >= A11Y_SUB) {
                if (!menuOpen || subOf < 0 || id - A11Y_SUB >= subRows.size()) return false;
                subSel = id - A11Y_SUB;
            } else if (id >= A11Y_MENU) {
                if (!menuOpen || id - A11Y_MENU >= menuRows.size()) return false;
                subOf = -1;
                menuSel = id - A11Y_MENU;
            } else if (id >= A11Y_CC) {
                if (!ccOpen || id - A11Y_CC >= ControlCenter.COUNT) return false;
                cc.selected = id - A11Y_CC;
            } else if (id >= A11Y_ROW) {
                if (setupOpen || menuOpen || ccOpen || alertEntry != null || openFolder != null || expanded
                        || shelf() != ROW_SHELF || id - A11Y_ROW >= rowCards.size()) return false;
                pillFocused = false;
                rowFocused = true;
                rowIndex = id - A11Y_ROW;
            } else if (id >= A11Y_ALERT) {
                if (alertEntry == null || id - A11Y_ALERT > 1) return false;
                alertSel = id - A11Y_ALERT;
            } else if (id == A11Y_PILL) {
                if (setupOpen || menuOpen || ccOpen || alertEntry != null || openFolder != null) return false;
                focusPill();
            } else if (id != A11Y_PLAY || !expanded) {
                return false;
            }
            kick();
            a11y.sync();
            return true;
        }

        @Override
        public boolean click(int id) {
            if (!focus(id)) return false;
            if (id >= A11Y_FOLDER) select();
            else if (id >= A11Y_SETTINGS) settings.select();
            else if (id >= A11Y_SETUP) setupFlow.select();
            else if (id >= A11Y_MENU) activateMenu();
            else if (id >= A11Y_ALERT && id < A11Y_ROW) activateAlert();
            else select();
            a11y.sync();
            return true;
        }

        @Override
        public float scale() {
            return scale;
        }
    }
}
