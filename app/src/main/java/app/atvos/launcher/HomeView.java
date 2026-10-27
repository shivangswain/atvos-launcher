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
import android.graphics.HardwareRenderer;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The home screen, drawn on one canvas. The layout is in design units (1920 wide), and the canvas is scaled to the
 * screen width.
 *
 * Each animation is an exponential approach, calculated for each frame. An animation can change its target at any time.
 * The view draws only while a value moves.
 */
@SuppressLint("ViewConstructor")
final class HomeView extends View {

    interface Host {
        void onFocusChanged(Apps.Entry entry, boolean inDock);
        void launch(Apps.Entry entry, Rect from);
        void showInfo(Apps.Entry entry);
        /** Saves the order and the hidden apps after Edit Home Screen. */
        void saveLayout(List<Apps.Entry> visible, List<Apps.Entry> hidden);
        void launchProgram(Recommendations.Item program, Rect from);
        /** Returns the full-screen top shelf pages of the app that are ready, best first. */
        List<Recommendations.Item> shelfPages(Apps.Entry entry);
        /** Shows one of these pages behind the dock and keeps it there. */
        void showShelfPage(Apps.Entry entry, Recommendations.Item page);
        /**
         * Returns the recommendation sources of the app that get a menu switch, as {@link Recommendations#WATCH_NEXT}
         * and {@link Recommendations#PREVIEWS} bits.
         */
        int shelfSources(Apps.Entry entry);
        boolean shelfSourceOn(Apps.Entry entry, int source);
        void setShelfSource(Apps.Entry entry, int source, boolean on);
        Controls.State controlState();
        /** Runs a Control Center action. Returns true to keep the Control Center open. */
        boolean control(int id);
        /** Removes the device admin, so that the user can uninstall the app. */
        void releasePower();
        /**
         * Returns the home screen configuration. It makes system calls, so it is read when the sheet opens, not for
         * each frame.
         */
        Controls.Setup setupState();
        /** Asks Android to make this app the home app. */
        void requestDefaultHome();
        /** Disables the locked home apps of this TV over self-adb. The user accepts the prompt on the screen. */
        void selfSetupHome(SelfSetup progress);
        /**
         * Gives the Home button back to the TV's home app: enables the home apps that self-adb disabled. Remote Buttons
         * turns off too, unless {@code keepRemoteButtons} (it then stands in for the TV's home app).
         */
        void restoreTvHome(SelfSetup progress, boolean keepRemoteButtons);
        /** Opens Accessibility, where the user turns on Remote Buttons. Setup continues when it is on. */
        void openRemoteButtons();
        void turnOffRemoteButtons();
        /** The setup steps closed. The first time, this ends the first-launch setup. */
        void setupDone();
        /** Opens the About screen of Settings. */
        void openAbout();
        /** Opens Developer options. */
        void openDeveloperOptions();
        /** Opens the account chooser of Android for the profile picture. */
        void chooseAccount();
        boolean hasAccount();
        /** Returns the address of the selected Google account, or null. */
        String account();
        /** Settings > Profile Picture: {@link Profile#PHOTO}, {@link Profile#INITIAL} or {@link Profile#NONE}. */
        String profilePicture();
        void setProfilePicture(String picture);
        /** Settings > Time Format: true for 24-hour time. The default is the setting of the TV. */
        boolean use24Hour();
        void setUse24Hour(boolean on);
        /**
         * The full-screen top shelf opened or closed. Shows its pictures with the details scrim or with the dock scrim.
         */
        void setFullScreen(boolean on);
        /** Shows the Hidden toast after an app moves to Hidden. */
        void showHiddenHint();
        /** Settings > Support: opens {@link SettingsScreen#STORE} or {@link SettingsScreen#APP_INFO}. */
        boolean openFromSettings(int what);
        /** Uninstalls an app after the user confirms the deletion. */
        void deleteApp(Apps.Entry entry);
        /** Settings > Appearance: true for light rows and Settings, else dark. */
        boolean lightAppearance();
        void setLightAppearance(boolean light);
        /** Returns the Google Play donation for Settings. */
        Donation donation();
        /** Shows the TV keyboard to edit {@code text} (a folder name) and sends each change to {@code edit}. */
        void editText(String text, TextEdit edit);
        /** Back with nothing to close. A home screen stays; atvOS opened as an app closes. */
        void backAtRoot();
    }

    /** Receives the text from the TV keyboard. UI thread. */
    interface TextEdit {
        void changed(String text);
        void done(String text);
    }

    /** Receives the progress of the on-TV setup for the sheet. UI thread. */
    interface SelfSetup {
        void status(int messageRes);
        void done(boolean ok, int messageRes);
        /** The TV shows its "Allow USB debugging" prompt for the key with this fingerprint (see SelfAdb). */
        default void prompt(String fingerprint) {
        }
    }

    // The layout in design units.
    static final float W = 1920f;
    static final float TILE_W = 250f, TILE_H = 150f;
    static final float FOCUS_SCALE = 1.2f;
    static final int COLS = 6; // the apps in a row; the first row is the dock
    private static final float TILE_R = 22f;
    private static final float GAP_X = 47f, ROW_PITCH = 224f;
    private static final float GRID_X = (W - COLS * TILE_W - (COLS - 1) * GAP_X) / 2f;
    private static final float DOCK_ROW_Y = 800f;
    private static final float DOCK_PAD_X = 48f, DOCK_PAD_Y = 36f;
    private static final float EDGE = W - GRID_X + DOCK_PAD_X;      // the right edge of the dock and the clock
    // Below the dock, the top row of apps goes up to GRID_TOP and the clock fades out. Lower rows scroll only to keep
    // the focused row above FOCUS_LINE.
    private static final float GRID_TOP = 95f, FOCUS_LINE = 640f;
    private static final float SHADOW_PAD = 60f, SHADOW_DY = 20f, SHADOW_BLUR = 30f;
    private static final float CLOCK_TOP = 50f, CLOCK_H = 56f, AVATAR = 40f;
    private static final float HIDDEN_GAP = 84f; // the space for the "Hidden" title above the hidden apps
    private static final int INK = 0xFF141416, TV_RED = 0xFFFF4245;
    // An open folder: a glass panel below its name, with four apps in each row.
    private static final int FOLDER_COLS = 4;
    private static final float FOLDER_W = 1200f, FOLDER_TOP = 150f, FOLDER_PAD = 48f, FOLDER_GAP = 35f;
    private static final int MAX_FOLDER_NAME = 48; // characters; a longer name is cut when the user types it
    private static final float FOLDER_R = 48f, FOLDER_NAME_Y = 78f, FOLDER_NAME_H = 84f;
    // The Delete alert.
    private static final float ALERT_W = 660f, ALERT_PAD = 40f, ALERT_BUTTON_H = 70f;
    private static final float PLAY_H = 64f, PLAY_PAD = 34f, PLAY_ICON = 22f, PLAY_GAP = 14f, PLAY_BAR = 88f;
    // The glass of the controls over the backdrop: the dock, the clock pill, Play, and the pager arrows and dots.
    // GLASS_TINT is its tint. The ink on the glass changes to dark between INK_FROM and INK_TO.
    private static final int GLASS_TINT = 0x1AFFFFFF, INK_DARK = 28;
    private static final float INK_FROM = 0.55f, INK_TO = 0.70f;
    // The page controls of the full-screen top shelf: arrows at the sides and page dots at the bottom. They show on
    // arrival and at each page change, then fade out.
    private static final float PAGER_ARROW_D = 80f, PAGER_ARROW_X = 130f, PAGER_DOT = 8f, PAGER_DOT_STEP = 21f,
            PAGER_DOTS_H = 26f, PAGER_DOTS_BOTTOM = 24f;
    private static final long PAGER_SHOWN_MS = 1600;
    // A row shelf (programs below Full HD).
    private static final float ROW_X = 90f, ROW_TOP = 180.5f, ROW_GAP = 60f, ROW_FOCUS_SCALE = 1.048f,
            ROW_HEADER_Y = 157f, ROW_HEADER_LIFT = 21f, ROW_BAR_INSET = 41f, ROW_BAR_BOTTOM = 28f, ROW_BAR_H = 6f;
    // A title that is too long for its focused card scrolls in place. The scroll starts one second after the focus
    // arrives, at 45 units per second, with a copy 44 units behind. Both edges fade. Each cycle starts with a rest of
    // two seconds.
    private static final float MARQUEE_SPEED = 45f, MARQUEE_GAP = 44f, MARQUEE_FADE = 24f;
    private static final long MARQUEE_DELAY_MS = 1000, MARQUEE_REST_MS = 2000;
    // The details of the full-screen shelf at the bottom left: the title, a line of facts, the description and Play.
    private static final float DETAILS_X = 92f, DETAILS_W = 820f, DETAILS_BOTTOM = 104f;
    /** The kind of top shelf of the focused app: none, a full-screen picture (Full HD), or a row of cards. */
    private static final int NO_SHELF = 0, FULL_SHELF = 1, ROW_SHELF = 2;

    private final Host host;
    private final List<Apps.Entry> apps = new ArrayList<>();   // visible, in order
    private final List<Apps.Entry> hidden = new ArrayList<>(); // greyed out below, during an edit or when Hidden is open
    private int focus;                                          // the index into apps, then into hidden when Hidden is open

    private float scale = 1f, height = 1080f;
    private long lastFrame;
    private boolean animating;

    // Animated values and the states that set their targets.
    private float scroll, expand, hint, menuAnim, pillAnim, ccAnim, editAnim, fade = 1f;
    private float rowAnim; // the row shelf has the focus (the section titles go up, the focused card shows its title)
    private float playFocus; // 1 when Play has the focus, 0 when Play is glass
    private float away; // 0 in the dock, 1 when the focus is in the grid: blurs and dims the backdrop
    private int dim; // the alpha of the black overlay when the focus is not in the dock
    // The ink of the clock and of the Control Center: 0 for white, 1 for dark (on bright images).
    private float clockInk, ccInk;
    private boolean expanded, menuOpen, longPressed, pillFocused, ccOpen;
    private boolean rowFocused; // a card in the row shelf has the focus (Up from the dock)
    private int rowIndex;          // the focused card
    private float rowScroll;       // the scroll of the row to the left, in design units
    private float[] rowFocus = new float[0]; // the focus value of each card
    private String[] rowHeaders = new String[0]; // the section title above each card that starts a section, else null
    private float[] rowHeaderWidths = new float[0];
    private float[] rowLefts = new float[0]; // the left edge of each card before the scroll (cards are 16:9 or square)
    private String[] rowTitles = new String[0]; // the title of each card
    private float[] rowTitleWidths = new float[0];
    private int marqueeCard = -1;  // the focused card with a title that is too long, or -1
    private long marqueeSince;     // the time when the focus came to it
    private float marqueeOffset;   // the scroll of its title
    private final Runnable marqueeWake = this::kick; // the end of a rest: the title scrolls again
    private List<Backdrops.Card> rowCards = Collections.emptyList(); // the row that shows
    // The details of the full-screen shelf, laid out when the program changes.
    private Recommendations.Item detailsFor;
    private String detailsTitle, detailsLine;
    private StaticLayout detailsBody;
    // The label of the Play button: "Play", or for a program that is partly watched, a progress bar and the time that
    // remains.
    private String detailsPlay = "";
    private float detailsProgress, detailsPlayWidth;
    private boolean motion = true; // true when animations are on (false for Remove animations or an animator scale of 0)
    // The pages of the full-screen top shelf (the programs of the focused app), the page that shows, and its controls.
    private List<Recommendations.Item> pages = Collections.emptyList();
    private int page;
    private boolean pagerShown;
    private float pagerAnim;
    private int hintTint = Color.WHITE; // the colour of the full-screen hint: the colour of the backdrop, lighter or darker
    private final Runnable hidePager = () -> {
        pagerShown = false;
        kick();
    };
    private boolean editing;          // Edit Home Screen: the focused app moves with the arrow keys
    private boolean editFromHidden;   // true if the moved app came from Hidden
    private boolean showHidden;       // the Hidden section is open (a long press of Down on the last row)
    private boolean downHeld, swallowDown; // Down is held on the last row, and its repeats after Hidden opens are ignored
    private final Runnable reveal = () -> { // the end of the hold: Hidden opens
        if (downHeld && !hidden.isEmpty()) revealHidden();
    };
    private long downAt, bounceAt;
    private float downAppear, downProgress, bounce, menuSelAnim;
    private Backdrops.Backdrop backdrop, previous;

    // Screen readers: a tree of virtual items for the items on this view.
    private final A11y a11y;
    private static final int A11Y_PILL = 1, A11Y_PLAY = 2, A11Y_ALERT = 10, A11Y_ROW = 20, A11Y_CC = 100, A11Y_MENU = 200, A11Y_SUB = 250,
            A11Y_SETUP = 300, A11Y_SETTINGS = 350, A11Y_FOLDER = 400, A11Y_APP = 1000; // the folder name, then its apps

    // Control Center (opened from the clock pill).
    private final ControlCenter cc;
    private final ControlCenter.Glass ccGlass = (c, rect, radius, tint, alpha) -> drawGlass(c, rect, radius, 0f, tint, alpha, ccAnim > 0f);

    // Long-press menu: options next to the tile, and a second list next to them for the options that open one (Move
    // to…, Top Shelf…).
    private static final int ROW_PLAIN = 0, ROW_DANGER = 1, ROW_SUBMENU = 2;
    /** One option. {@code checked}: the state of a switch (in Top Shelf…), or null. */
    private record MenuRow(String label, Drawable icon, int kind, Runnable action, Boolean checked) {
    }
    private final List<MenuRow> menuRows = new ArrayList<>(), subRows = new ArrayList<>();
    private static final float MENU_ITEM_H = 66f, MENU_PAD = 12f;
    private final RectF menuBox = new RectF(), subBox = new RectF(); // the bounds at the last draw, for screen readers
    private final Drawable editIcon, newFolderIcon, deleteIcon, checkIcon, chevronIcon;
    private Apps.Entry menuEntry;            // the tile of the menu
    private int menuSel, subSel, subOf = -1; // subOf: the option whose list is open, or -1
    private float menuWidth, subWidth, subAnim, subSelAnim;
    private int subFrom;                     // the option that the second list opened from last (it fades out there)
    private boolean subLeft;                 // no space at the right: the second list opens to the left
    private Runnable subBuilder;             // builds the open list again (after a switch changes)

    // An open folder, over the home screen.
    private Apps.Entry openFolder, shownFolder; // shown: drawn while it fades out
    private int folderFocus;                    // the index into its apps, or -1 for its name
    private float folderAnim, folderNameFocus, folderScroll;
    private boolean renaming;                   // the user types its name on the TV keyboard
    private float tileFade = 1f;                // the fade of the tiles that draw now (the tiles of a folder as it opens)
    private String folderName = "";             // the name that shows (and that the user types)

    // The Delete alert.
    private Apps.Entry alertEntry;
    private int alertSel;                       // 0 Cancel, 1 Delete
    private float alertAnim, alertSelAnim;
    private String alertTitle = "";
    private StaticLayout alertMessage;
    private final String cancelLabel, deleteLabel;
    private final RectF alertBox = new RectF();

    // The setup steps: on the first launch, and from Settings when another app has the Home button.
    private final SetupFlow setupFlow;
    private boolean setupOpen;
    private float setupAnim;
    // Settings (the switches in Control Center), full screen.
    private final SettingsScreen settings;
    private boolean settingsOpen;
    private float settingsAnim;
    private boolean light; // Settings > Appearance: Light

    // Clock.
    private String time = "";
    /**
     * The hint in the clock pill, as on tvOS: "Hold [Home] for Control Center". It shows 10 s after the home screen
     * starts, then every 2 min, until the user first holds Home. Only while {@link RemoteKeys} is on.
     */
    private static final long HOLD_HINT_FIRST_MS = 10_000, HOLD_HINT_EVERY_MS = 120_000, HOLD_HINT_SHOW_MS = 5_000,
            HOLD_HINT_RETRY_MS = 10_000;
    private static final float HOLD_KEY = 34f; // the round Home button in the hint
    private final Drawable homeIcon;
    private final String holdBefore, holdAfter;
    private float holdBeforeW, holdW;
    private boolean holdLearned, holdShown;
    private float holdHint;    // 0: the time, 1: the hint
    private long holdNextAt;   // the uptime of the next hint, or 0 before the first
    private final Runnable showHoldHint = this::showHoldHint, hideHoldHint = () -> {
        holdShown = false;
        kick();
    };
    private float timeWidth;
    private final Runnable tick = this::tick;

    // Drawing objects, allocated one time.
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backdropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    // Behind an open folder: the blurred backdrop, darker.
    private final Paint folderBlur = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final LightingColorFilter[] dimFilters = new LightingColorFilter[256]; // by alpha, made when necessary
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
    private final TextPaint rowMarqueeText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path playTriangle = new Path();
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    // The glass: one pass for each shape. The tint and the dim are in the colour matrix of the blur, cached for each
    // tint and dim level. During a crossfade, the CPU mixes the two blurs into one small bitmap.
    private final int[] glassTints = new int[32], glassDims = new int[32];
    private final ColorFilter[] glassFilters = new ColorFilter[32];
    private int glassFilterNext;
    private Bitmap mixedBlur;
    private android.graphics.BitmapShader mixedShader;
    private int[] mixedPixels = new int[0];
    private Backdrops.Backdrop mixedFrom, mixedTo;
    private int mixedLevel = -1;
    // The home screen in a layer under the Control Center (see drawFrozenHome), and a fingerprint of the values that it
    // shows. A different fingerprint draws the layer again.
    private RenderNode homeNode;
    private final Paint homePaint = new Paint();
    private double homeSig, frozenSig = Double.NaN;
    // The home screen, small and blurred, for the glass of the Control Center (see snapHome). The UI thread records it,
    // not more than once in SNAP_MS, and the snapshot thread draws it offscreen. It is also made while the clock pill
    // has the focus and nothing moves.
    private Snapper snapper;
    private Bitmap homeBlur;
    private android.graphics.BitmapShader homeBlurShader;
    private double homeBlurSig = Double.NaN;
    private long homeBlurAt;
    private boolean snapUnavailable;
    private boolean snapBusy; // a snapshot is on the snapshot thread; the next one waits for it
    private static final long SNAP_MS = 120, SNAP_TIMEOUT_MS = 1000;
    private final Runnable snapAhead = this::snapAhead;
    private final LinearGradient rimGradient, sheenGradient;
    private LinearGradient marqueeFade;  // the fade of the title edges, made for the width of the focused card
    private float marqueeFadeFor;
    private int marqueeFadeColor; // the colour of the marquee text (light or dark rows)
    private final Path chevron = new Path(), hintChevron = new Path(), pagerChevron = new Path();
    private final RectF r = new RectF(), r2 = new RectF(), playRect = new RectF();
    private final Matrix m = new Matrix();
    private Bitmap defaultAvatar, avatar, shadowMask;

    private final String fullScreenHint, hiddenTitle, hiddenEmpty, playLabel;

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
        newFolderIcon = menuIcon(context, R.drawable.ic_add_circle);
        deleteIcon = menuIcon(context, R.drawable.ic_remove_circle);
        checkIcon = menuIcon(context, R.drawable.ic_check);
        chevronIcon = menuIcon(context, R.drawable.ic_chevron_right);
        homeIcon = menuIcon(context, R.drawable.ic_home);
        String hold = context.getString(R.string.hint_hold_home), slot = "%1$s";
        int at = hold.indexOf(slot);
        holdBefore = at >= 0 ? hold.substring(0, at) : hold;
        holdAfter = at >= 0 ? hold.substring(at + slot.length()) : "";
        cancelLabel = context.getString(R.string.cancel);
        deleteLabel = context.getString(R.string.delete);

        Typeface medium = Typeface.create(Typeface.DEFAULT, 500, false);
        Typeface semibold = Typeface.create(Typeface.DEFAULT, 600, false);
        clockText.setTypeface(semibold);
        clockText.setTextSize(27f);
        clockText.setColor(Color.WHITE);
        holdBeforeW = clockText.measureText(holdBefore);
        holdW = holdBeforeW + HOLD_KEY + 4f + clockText.measureText(holdAfter);
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
        // The play glyph: a triangle with soft corners (a round join).
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

        // Glass: the blurred backdrop, more saturated (see glassFilter), with a light rim that is brighter at the top.
        rimGradient = new LinearGradient(0, 0, 0, 1, new int[]{0x73FFFFFF, 0x14FFFFFF, 0x2EFFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f);
        rim.setShader(rimGradient);

        // The highlight across the top of a focused icon.
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
        hintChevron.moveTo(-24.5f, 4f); // wide and shallow
        hintChevron.lineTo(0f, -4f);
        hintChevron.lineTo(24.5f, 4f);
        pagerChevron.moveTo(-5f, -10f); // points right (mirrored for the left arrow)
        pagerChevron.lineTo(5f, 0f);
        pagerChevron.lineTo(-5f, 10f);
        a11y = new A11y(this, new Accessible());
        setupFlow = new SetupFlow(context, new SetupFlow.Host() {
            @Override public Controls.Setup state() { return HomeView.this.host.setupState(); }
            @Override public boolean hasAccount() { return HomeView.this.host.hasAccount(); }
            @Override public void openAbout() { HomeView.this.host.openAbout(); }
            @Override public void openDeveloperOptions() { HomeView.this.host.openDeveloperOptions(); }
            @Override public void setUpHome(SelfSetup progress) { HomeView.this.host.selfSetupHome(progress); }
            @Override public void openRemoteButtons() { HomeView.this.host.openRemoteButtons(); }
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
            @Override public void setUpHome(int way) { closeSettings(); openSetupSteps(way); }
            @Override public void restoreHome(SelfSetup progress, boolean keepRemoteButtons) {
                HomeView.this.host.restoreTvHome(progress, keepRemoteButtons);
            }
            @Override public void turnOffRemoteButtons() { HomeView.this.host.turnOffRemoteButtons(); }
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

    /** Sets the appearance (Settings > Appearance) of the rows of programs and of Settings. */
    void setLight(boolean on) {
        light = on;
        kick();
    }

    // State from the activity

    /** Sets the apps. Returns false during an edit. Then the caller tries again later. */
    boolean setApps(List<Apps.Entry> visible, List<Apps.Entry> hiddenApps) {
        if (editing || renaming) return false;
        Apps.Entry was = focused(), wasInFolder = folderEntry();
        String folderKey = openFolder != null ? openFolder.pkg : null;
        apps.clear();
        apps.addAll(visible);
        hidden.clear();
        hidden.addAll(hiddenApps);
        if (apps.isEmpty() && !hidden.isEmpty()) {
            // If all apps are hidden, one app stays visible, so that the focus has a target.
            apps.add(hidden.remove(0));
            host.saveLayout(new ArrayList<>(apps), new ArrayList<>(hidden));
        }
        if (hidden.isEmpty()) showHidden = false;
        focus = Math.max(0, Math.min(focus, apps.size() - 1));
        if (was != null) {
            // Keeps the focus on the same app, also on the hidden apps while Hidden is open.
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

    /** After a reload: opens the same folder (a new object) on the same app, or closes it if it is gone. */
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

    /** Returns true if a tile is on the home screen, in Hidden or in a folder. */
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
        // A full-screen picture does not set the hint colour, because its details scrim makes the hint darker as it
        // fades out.
        if (!b.details) hintTint = vibrant(b.hintColor);
        if (expanded) page = pageOf(b.program);
        setRow(b.row != null ? b.row : Collections.<Backdrops.Card>emptyList());
        kick();
    }

    /**
     * Sets a new row of cards. The focus stays on the same card if it is in the new row, else it goes to the first
     * card. The section titles are calculated here.
     */
    private void setRow(List<Backdrops.Card> cards) {
        if (cards == rowCards) return;
        String was = rowIndex < rowCards.size() ? rowCards.get(rowIndex).item().image() : null;
        boolean same = !cards.isEmpty() && !rowCards.isEmpty() && cards.get(0).item().pkg().equals(rowCards.get(0).item().pkg());
        rowCards = cards;
        rowFocus = new float[cards.size()];
        rowHeaders = new String[cards.size()];
        rowHeaderWidths = new float[cards.size()];
        rowLefts = new float[cards.size()];
        for (int i = 0, n = cards.size(); i < n; i++) rowLefts[i] = i == 0 ? ROW_X : rowLefts[i - 1] + cards.get(i - 1).width() + ROW_GAP;
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
        if (!same) {
            marqueeCard = -1; // a new row with more cards keeps a title that scrolls
            rowIndex = 0;
            rowScroll = 0f;
        } else {
            rowIndex = at;
            if (rowFocused) rowFocus[at] = 1f;
        }
    }

    /** The top shelf of the focused app has more pages ready. */
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

    /** Shows the page controls. They fade out after PAGER_SHOWN_MS. */
    private void showPager() {
        pagerShown = true;
        removeCallbacks(hidePager);
        postDelayed(hidePager, PAGER_SHOWN_MS);
        kick();
    }

    /**
     * Returns the text colour over the backdrop: the colour behind, much lighter and a little less saturated. Over a
     * bright picture, it is much darker.
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

    /** Sets the round avatar of the account in the clock pill. */
    void setAvatar(Bitmap round) {
        avatar = round;
        kick();
    }

    /** The Home key on the home screen: goes to the top row and closes all overlays. */
    void goHome() {
        if (setupOpen && !setupFlow.staysOnHome()) closeSetup(); // setup stays open until the user goes back to it
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
        cancelHold();
        swallowDown = false;
        if (!apps.isEmpty()) focus = Math.min(focus, apps.size() - 1) % COLS;
        focusChanged();
    }

    /** Ends a hold of Down on the last row before Hidden opens. */
    private void cancelHold() {
        downHeld = false;
        removeCallbacks(reveal);
    }

    /**
     * Returns how long Down is held on the last row before Hidden opens: two system long presses, 800 ms by default.
     * It follows the touch and hold delay in the accessibility settings of the TV.
     */
    private static long holdMs() {
        return 2L * ViewConfiguration.getLongPressTimeout();
    }

    /** Opens the Control Center. */
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
        removeCallbacks(snapAhead);
        cancelHold();
        releaseSnap();
        if (homeNode != null) homeNode.discardDisplayList();
        frozenSig = Double.NaN;
        super.onDetachedFromWindow();
    }

    void setClockRunning(boolean run) {
        removeCallbacks(tick);
        if (run) tick();
        removeCallbacks(showHoldHint);
        removeCallbacks(hideHoldHint);
        holdShown = false;
        if (!run) {
            holdHint = 0f; // another app is in front: no animation
            return;
        }
        if (holdLearned) return;
        // 10 s after the home screen shows, and not sooner than 2 min after the last hint.
        long now = SystemClock.uptimeMillis();
        holdNextAt = Math.max(holdNextAt, now + HOLD_HINT_FIRST_MS);
        postDelayed(showHoldHint, holdNextAt - now);
    }

    /** The user has held Home: the hint doesn't show again. */
    void setHoldHintLearned(boolean learned) {
        holdLearned = learned;
        if (!learned) return;
        removeCallbacks(showHoldHint);
        removeCallbacks(hideHoldHint);
        holdShown = false;
        kick();
    }

    /** Shows the hint for 5 s if the home screen is quiet, and plans the next one. */
    private void showHoldHint() {
        if (holdLearned) return;
        if (!RemoteKeys.isOn()) { // holding Home does nothing without the service
            postDelayed(showHoldHint, HOLD_HINT_EVERY_MS);
            return;
        }
        if (!holdHintFits()) {
            postDelayed(showHoldHint, HOLD_HINT_RETRY_MS);
            return;
        }
        holdShown = true;
        holdNextAt = SystemClock.uptimeMillis() + HOLD_HINT_EVERY_MS;
        postDelayed(hideHoldHint, HOLD_HINT_SHOW_MS);
        postDelayed(showHoldHint, HOLD_HINT_EVERY_MS);
        kick();
    }

    /** The hint shows only on the quiet home screen: the clock pill shows and nothing is open over it. */
    private boolean holdHintFits() {
        return !setupOpen && !settingsOpen && !ccOpen && !pillFocused && !menuOpen && !editing && openFolder == null
                && alertEntry == null && !expanded && inDock();
    }

    /** Opens Control Center for a held Home button. Returns false while setup shows. */
    boolean openControlCenterFromRemote() {
        if (setupOpen) return false;
        openControlCenter();
        return true;
    }

    /** Settings > Time Format changed. */
    void timeFormatChanged() {
        removeCallbacks(tick);
        tick();
    }

    private void tick() {
        // "9:41 AM" in the language and conventions of the TV, 12-hour or 24-hour as set in Settings.
        Locale locale = Locale.getDefault();
        String pattern = DateFormat.getBestDateTimePattern(locale, host.use24Hour() ? "Hm" : "hm");
        SimpleDateFormat format = new SimpleDateFormat(pattern, locale); // made once a minute
        format.setTimeZone(TimeZone.getDefault()); // applies time zone changes
        // AM and PM in capital letters, in all languages.
        DateFormatSymbols symbols = format.getDateFormatSymbols();
        String[] ampm = symbols.getAmPmStrings();
        for (int i = 0; i < ampm.length; i++) ampm[i] = ampm[i].toUpperCase(locale);
        symbols.setAmPmStrings(ampm);
        format.setDateFormatSymbols(symbols);
        time = format.format(new Date());
        timeWidth = clockText.measureText(time);
        if (ccOpen) cc.setState(host.controlState()); // sleep timer countdown, network changes
        kick();
        long now = System.currentTimeMillis();
        postDelayed(tick, 60_000 - now % 60_000 + 20);
    }

    // Input

    /** Each key can move the focus or change the screen, so screen readers get an update after each key. */
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
        if (downHeld && code != KeyEvent.KEYCODE_DPAD_DOWN) cancelHold(); // another key cancels the long press
        if (setupOpen) {
            if (isSelect(code)) return true; // acts on release
            return setupFlow.key(code) || super.onKeyDown(code, e);
        }
        if (settingsOpen) {
            if (isSelect(code)) return true; // acts on release
            return settings.key(code) || super.onKeyDown(code, e);
        }
        if (alertEntry != null) {
            if (isSelect(code)) return true; // acts on release
            return alertKey(code) || super.onKeyDown(code, e);
        }
        if (menuOpen) {
            // Select acts on release (see onKeyUp). The repeats of a held key do not select a menu option when the long
            // press opens the menu.
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
                case KeyEvent.KEYCODE_DPAD_DOWN: // back to the origin of Up: the Play button of the shelf, or the dock
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
                    else if (rowIndex + d >= 0 && rowIndex + d < rowCards.size()) { // along the row, with no wrap
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
                if (swallowDown && e.getRepeatCount() > 0) return true; // Down is still held from the reveal of Hidden
                if (canRevealHidden()) {
                    // Down held on the last row: an arrow shows, a ring fills around it, then Hidden opens.
                    if (e.getRepeatCount() == 0) {
                        downHeld = true;
                        downAt = SystemClock.uptimeMillis();
                        removeCallbacks(reveal);
                        postDelayed(reveal, holdMs());
                        kick();
                    }
                    return true;
                }
                move(0, 1);
                return true;
            case KeyEvent.KEYCODE_MENU: if (!editing) openMenu(); return true;
            case KeyEvent.KEYCODE_BACK:
                if (!back()) host.backAtRoot();
                return true;
            default: return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
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
                if (!longPressed) activateMenu(); // the release of the long press that opened the menu selects nothing
            } else if (e.isTracking() && !e.isCanceled() && !longPressed) {
                select();
            }
            longPressed = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && (downHeld || swallowDown)) {
            cancelHold();
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
     * Moves the focus in the grid with the D-pad. The hidden apps are a second section below the visible apps. It shows
     * while an app moves or after a reveal. An app that moves between the sections becomes hidden or visible.
     */
    private void move(int dx, int dy) {
        int v = apps.size(), total = total();
        if (total == 0) { // no tile to focus: Up goes to the clock pill
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
                // Into the hidden section. An app that moves there becomes hidden. At least one app stays visible.
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
            // Up from the hidden section to the last row of apps. A held app becomes visible.
            int lastRow = v == 0 ? 0 : ((v - 1) / COLS) * COLS;
            if (editing) {
                int to = Math.min(lastRow + col, v);
                apps.add(to, hidden.remove(local));
                focus = to;
            } else if (v > 0) {
                focus = Math.min(lastRow + col, v - 1);
                showHidden = false; // Hidden closes
            } else {
                return;
            }
        } else if (!editing) {
            // Up from the dock: the full-screen top shelf, the card of a row shelf, else the clock pill.
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

    /** Plays the navigation sound of the system. It is silent when System sounds are off in Settings. */
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
                if (!renaming) startRename();
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
            finishEditing(); // drops the app and ends the edit
            return;
        }
        if (e.folder != null) {
            openFolder(e, 0);
            return;
        }
        Recommendations.Item program = rowFocused && rowIndex < rowCards.size() ? rowCards.get(rowIndex).item()
                : backdrop != null ? backdrop.program : null;
        if ((expanded || rowFocused) && program != null && program.pkg().equals(e.pkg)) {
            // Play on the top shelf: the launch animation starts from the picture or from the focused card.
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

    /** Returns the bounds of a focused tile on the screen, for the launch animation of the app. */
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

    /** Returns false if there is nothing to close: the focus is already in the dock. */
    private boolean back() {
        if (editing) {
            finishEditing();
        } else if (focus >= apps.size() && focus < total()) {
            // Closes Hidden and goes back to the dock.
            showHidden = false;
            focus = Math.max(0, Math.min((focus - apps.size()) % COLS, apps.size() - 1));
            focusChanged();
        } else if (focus >= COLS) {
            focus %= COLS;
            focusChanged();
        } else {
            return false;
        }
        return true;
    }

    /**
     * Opens or closes the full-screen top shelf: the dock and the clock move off the screen and the picture fills the
     * screen. If there are pages, the arrows and page dots show for a short time. Select plays the program.
     */
    private void setExpanded(boolean on) {
        if (on == expanded) {
            kick(); // the caller can change the focus around it: draw again
            return;
        }
        expanded = on;
        host.setFullScreen(on); // its pictures have the details scrim
        if (on) {
            Apps.Entry e = focused();
            pages = e != null ? host.shelfPages(e) : Collections.emptyList();
            page = pageOf(backdrop != null ? backdrop.program : null);
            playRect.setEmpty(); // set at the first draw: screen readers wait
            showPager();
        } else {
            removeCallbacks(hidePager);
            pagerShown = false;
        }
        kick();
    }

    /** Left or Right on the full-screen shelf: the previous or next page of the app, with no wrap. */
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

    /** Returns the kind of top shelf of the focused dock app, when its backdrop shows. */
    private int shelf() {
        Apps.Entry e = focused();
        Backdrops.Backdrop b = backdrop;
        if (e == null || b == null || b.program == null || !inDock() || !b.program.pkg().equals(e.pkg)) return NO_SHELF;
        return b.row != null ? ROW_SHELF : FULL_SHELF;
    }

    /**
     * Starts Edit Home Screen. The selected app moves with the arrow keys, also into or out of Hidden. Select or Back
     * drops it and ends the edit.
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
        // An app dropped in the hidden section keeps Hidden open. Else, Hidden closes.
        showHidden = focus >= apps.size() && !hidden.isEmpty();
        if (showHidden && !editFromHidden) host.showHiddenHint();
        if (focus >= total()) focus = Math.max(0, apps.size() - 1);
        focusChanged();
    }

    /** Opens the Hidden section after a long press of Down on the last row, with a small bounce. */
    private void revealHidden() {
        downHeld = false;
        swallowDown = true;
        showHidden = true;
        focus = apps.size() + Math.min(focus % COLS, hidden.size() - 1);
        bounceAt = SystemClock.uptimeMillis();
        navSound(0, 1);
        focusChanged();
        a11y.sync(); // the long press ends between key events
    }

    private boolean canRevealHidden() {
        int v = apps.size();
        return !editing && !showHidden && !hidden.isEmpty() && focus < v && focus / COLS == (v - 1) / COLS;
    }

    // Folders

    /** Opens a folder over the home screen, with the focus on one of its apps. */
    private void openFolder(Apps.Entry tile, int at) {
        openFolder = shownFolder = tile;
        folderName = tile.label;
        folderFocus = Math.max(0, Math.min(at, tile.folder.apps.size() - 1));
        folderScroll = 0f;
        for (Apps.Entry e : tile.folder.apps) e.x = Float.NaN; // laid out again in the folder
        closeMenu();
        kick();
        a11y.sync();
    }

    private void closeFolder() {
        if (editing) finishEditing();
        openFolder = null;
        focusChanged();
    }

    /** Returns the focused app in the open folder, or null if no folder is open or its name has the focus. */
    private Apps.Entry folderEntry() {
        return openFolder != null && folderFocus >= 0 && folderFocus < openFolder.folder.apps.size()
                ? openFolder.folder.apps.get(folderFocus) : null;
    }

    /** Handles the keys in an open folder. {@link #select} handles Select on release. */
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

    /**
     * Moves the focus in a folder: four apps in each row, with the name above them. A held app moves with the focus.
     */
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

    /** Starts the rename of a folder: the user types a new name on the TV keyboard, and the name updates in place. */
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
                if (name.length() > MAX_FOLDER_NAME) name = name.substring(0, MAX_FOLDER_NAME).trim();
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

    // Move to…

    /**
     * Removes an app from its location: the home screen, Hidden or a folder. An empty folder is removed too. Returns
     * the index of the app in that list.
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

    /** New Folder: puts a new folder that contains the app at the position of the app, and opens it. */
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

    /** Adds the app to the end of a folder and opens the folder on it. */
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
     * Returns true if {@code e} can move to Hidden and one tile stays on the home screen. It cannot be the last tile,
     * or the only app of a folder that is the last tile.
     */
    private boolean canHide(Apps.Entry e) {
        if (hidden.contains(e)) return false;
        if (apps.size() > 1) return true;
        if (apps.isEmpty() || apps.contains(e)) return false;
        Apps.Entry last = apps.get(0);
        return last.folder != null && last.folder.apps.size() > 1;
    }

    /** Moves the app to the end of Hidden, greyed out. At least one tile stays on the home screen. */
    private void moveToHidden(Apps.Entry e) {
        if (!canHide(e)) return;
        Apps.Entry folder = openFolder;
        detach(e);
        hidden.add(e);
        saveLayout();
        host.showHiddenHint();
        if (folder != null) {
            if (!apps.contains(folder)) {
                openFolder = null; // the folder had no other app
            } else {
                folderFocus = Math.min(folderFocus, folder.folder.apps.size() - 1);
            }
        }
        focus = Math.max(0, Math.min(focus, apps.size() - 1));
        focusChanged();
    }

    /**
     * Moves the app back to the home screen. From a folder, the app goes next to the folder (or to its position if it
     * was the last app). From Hidden, the app goes to the end. The app is then held, so that the user can move it.
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

    // Long-press menu

    private void openMenu() {
        Apps.Entry e = openFolder != null ? folderEntry() : focused();
        if (e == null) return;
        menuEntry = e;
        buildMenu(e);
        menuSel = 0;
        menuSelAnim = 0f;
        subOf = -1;
        subAnim = 0f;
        menuBox.setEmpty(); // set at the first draw: screen readers wait (see A11y.drawn)
        subBox.setEmpty();
        menuOpen = true;
        kick();
    }

    private void closeMenu() {
        menuOpen = false;
        subOf = -1;
        kick();
    }

    /**
     * Builds the menu: Edit Home Screen and Move to…, then the two top shelf sources for apps that publish them. Then
     * App Info, and Delete App for apps that the user can uninstall. A folder has only Move to….
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

    /**
     * Builds the Move to… list: the home screen (from a folder or Hidden), a new folder, the other folders, and Hidden.
     */
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

    /**
     * Builds the Top Shelf… list: one switch for each source. The shelf shows one, both or none (then the logo of the
     * app).
     */
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

    /** Returns the width for the longest option: the icon column, the text and a chevron. */
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
        subBox.setEmpty();
        kick();
    }

    /** Handles the keys while the menu is open. Returns false for other keys (for example, volume). */
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
                // Right opens the second list of an option with a chevron, on either side of the menu. In the second
                // list, the arrow toward the menu closes the list. Other arrows close the menu.
                boolean right = code == KeyEvent.KEYCODE_DPAD_RIGHT;
                if (sub) {
                    if (right == subLeft) subOf = -1;
                } else if (right && menuRows.get(menuSel).kind() == ROW_SUBMENU) {
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

    private void activateMenu() {
        playSoundEffect(SoundEffectConstants.CLICK);
        if (subOf >= 0) {
            MenuRow row = subRows.get(subSel);
            if (row.checked() != null) {
                row.action().run();
                subBuilder.run(); // a switch: shows its new state and stays open
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

    // Delete alert

    /** Opens the alert "Delete “App”?": Cancel first and focused, Delete in red. */
    private void openAlert(Apps.Entry e) {
        alertEntry = e;
        alertSel = 0;
        alertSelAnim = 0f;
        alertBox.setEmpty();
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

    // Setup and Settings

    /** Opens the first-launch setup at Welcome. */
    void openSetup() {
        closeAll();
        setupOpen = true;
        setupFlow.open();
        kick();
    }

    /** Opens the setup steps for {@code way}: a {@link SetupFlow} WAY constant. */
    private void openSetupSteps(int way) {
        closeAll();
        setupOpen = true;
        setupFlow.openSteps(way);
        kick();
        a11y.sync();
    }

    /** Opens Settings full screen. */
    void openSettings() {
        closeAll();
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

    /** Closes the overlays and resets the focus state before a full-screen flow opens. */
    private void closeAll() {
        closeMenu();
        alertEntry = null;
        cancelHold();
        swallowDown = false;
        ccOpen = false;
        pillFocused = false;
        if (editing) finishEditing();
        setExpanded(false);
        rowFocused = false;
    }

    /** Updates the setup after Settings, the home or account chooser of Android, or a package change. */
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

    /**
     * Returns the number of tiles on the screen: the visible apps, and the hidden apps during an edit or after a
     * reveal.
     */
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
        // The integer division gives the row number.
        if (i < apps.size()) return DOCK_ROW_Y + (i / COLS) * ROW_PITCH;
        return hiddenTop() + ((i - apps.size()) / COLS) * ROW_PITCH;
    }

    /** Returns the top of the hidden section: below the last row of apps, with space for its title. */
    private float hiddenTop() {
        int rows = Math.max(1, (apps.size() + COLS - 1) / COLS);
        return DOCK_ROW_Y + rows * ROW_PITCH + HIDDEN_GAP;
    }

    // Animation

    /** Starts the frame loop. If the view was idle, it resets the frame time, so that the first step is small. */
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
        // A shelf that is gone (for example, its source is off) cannot keep the focus.
        int shelf = shelf();
        if (expanded && shelf != FULL_SHELF) setExpanded(false);
        if (rowFocused && shelf != ROW_SHELF) rowFocused = false;
        expand = ease(expand, expanded ? 1f : 0f, 9f, dt, 0.001f);
        rowAnim = ease(rowAnim, rowFocused && !pillFocused && !ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        playFocus = ease(playFocus, expanded && !pillFocused && !ccOpen && !setupOpen && !settingsOpen ? 1f : 0f, 14f, dt, 0.002f);
        for (int i = 0; i < rowFocus.length; i++) {
            rowFocus[i] = ease(rowFocus[i], rowFocused && i == rowIndex && !pillFocused && !ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        }
        // The focused card stays in the second position (the first card in the first position). The scroll continues
        // after the focus goes back to the dock.
        if (rowIndex < rowLefts.length) rowScroll = ease(rowScroll, rowLefts[Math.max(0, rowIndex - 1)] - ROW_X, 10f, dt, 0.2f);
        stepMarquee();
        boolean pager = expanded && pagerShown && pages.size() > 1 && !pillFocused && !ccOpen && !menuOpen && !setupOpen;
        pagerAnim = ease(pagerAnim, pager ? 1f : 0f, pager ? 14f : 5f, dt, 0.002f); // fades out slowly
        editAnim = ease(editAnim, editing || showHidden ? 1f : 0f, 10f, dt, 0.002f);
        long now = SystemClock.uptimeMillis();
        if (downHeld) {
            // The ring fills during the hold. The reveal runnable opens Hidden at the end, outside the draw.
            downProgress = Math.min(1f, (now - downAt) / (float) holdMs());
            animating = true;
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
        holdHint = ease(holdHint, holdShown && holdHintFits() ? 1f : 0f, 7f, dt, 0.002f);
        ccAnim = ease(ccAnim, ccOpen ? 1f : 0f, 14f, dt, 0.002f);
        if (ccAnim > 0f && cc.step(dt)) animating = true;
        // Bright backdrops get dark text. The calculation includes the layers over the image at each location.
        float shade = 1f - 0.39f * away;
        Backdrops.Backdrop b = backdrop;
        float clockLight = b == null ? 0f : (b.clockLight * shade) * 0.9f + 0.1f;   // plus the white tint of the pill
        float ccLight = b == null ? 0f : b.controlsLight * 0.86f + 0.14f;            // plus the white tint of the tiles
        clockInk = ease(clockInk, clockLight > 0.6f ? 1f : 0f, 6f, dt, 0.002f);
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
        if (editing && motion) animating = true; // the held app wiggles
        homeSig = homeFingerprint();
    }

    /**
     * Returns a hash of all values that the home screen under the overlays shows. It changes when one of them changes.
     * Each value is mixed in by its bits and its position, so two changes cannot cancel out, as they can in a sum (two
     * tiles that swap places, say). The long is widened to a double, which is never NaN, the mark of "no layer".
     */
    private double homeFingerprint() {
        long h = mix(mix(mix(mix(mix(mix(mix(1L, scroll), away), expand), rowAnim), playFocus), rowScroll), pagerAnim);
        h = mix(mix(mix(mix(mix(mix(mix(h, editAnim), downProgress), downAppear), bounce), hint), fade), marqueeOffset);
        h = mix(mix(h, System.identityHashCode(backdrop)), System.identityHashCode(previous));
        h = mix(h, focus * 8 + (light ? 4 : 0) + (menuAnim > 0f ? 2 : 0) + (folderCover() > 0.999f ? 1 : 0));
        for (float v : rowFocus) h = mix(h, v);
        for (int i = 0, n = total(); i < n; i++) {
            Apps.Entry e = entry(i);
            h = mix(mix(mix(h, e.x), e.y), e.focus);
        }
        return h;
    }

    private static long mix(long h, float v) {
        return mix(h, Float.floatToIntBits(v));
    }

    /** Mixes {@code v} into the hash {@code h} (a multiply by the 64-bit golden ratio, as in Fibonacci hashing). */
    private static long mix(long h, int v) {
        return (h ^ v) * 0x9E3779B97F4A7C15L;
    }

    /**
     * Scrolls the title of the focused card if it is too long. The cycle is one second of rest, one full scroll, two
     * seconds of rest at the start, then again. Frames draw only while it moves. A delayed callback ends each rest.
     */
    private void stepMarquee() {
        int card = rowFocused && !pillFocused && !ccOpen && rowIndex < rowTitles.length
                && rowTitleWidths[rowIndex] > rowCards.get(rowIndex).width() * ROW_FOCUS_SCALE ? rowIndex : -1;
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

    /** Returns the downward offset of the UI for the full-screen top shelf: the dock moves fully off the screen. */
    private float lift() {
        return expand * (height - (DOCK_ROW_Y - DOCK_PAD_Y) + 40f);
    }

    // Drawing

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        scale = w / W;
        height = h / scale;
        frozenSig = homeBlurSig = Double.NaN; // the frozen home and its snapshot have the old size
        // The details scrim of the full-screen shelf: darkest at the bottom-left corner, in one pass.
        detailsScrim.setShader(new RadialGradient(0f, height, Backdrops.DETAILS_SCRIM_RADIUS, Backdrops.DETAILS_SCRIM,
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
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
        a11y.invalidate(); // items can move: screen readers list them again

        c.save();
        c.scale(scale, scale);
        // An open folder covers the home screen with its blurred backdrop, and Settings covers all. Nothing below them
        // shows.
        if (settingsAnim > 0.999f) {
            settings.draw(c, 1f, height);
            c.restore();
            if (animating) postInvalidateOnAnimation();
            a11y.drawn();
            return;
        }
        boolean covered = folderCover() > 0.999f;
        if (ccAnim > 0f && c.isHardwareAccelerated()) {
            drawFrozenHome(c, covered);
        } else {
            drawHome(c, covered);
            if (!Double.isNaN(frozenSig) && homeNode != null) homeNode.discardDisplayList(); // and its layer
            frozenSig = Double.NaN;
        }
        if (!covered) drawClock(c);
        if (ccAnim > 0f) cc.draw(c, ccGlass, ccAnim, ccInk);
        if (folderAnim > 0f) drawFolder(c);
        if (menuAnim > 0f) drawMenu(c);
        if (alertAnim > 0f) drawAlert(c);
        if (setupAnim > 0f) setupFlow.draw(c, ccGlass, setupAnim, height);
        if (settingsAnim > 0f) settings.draw(c, settingsAnim, height);
        c.restore();

        if (animating) postInvalidateOnAnimation();
        else if (pillFocused && ccAnim == 0f && homeBlurSig != homeSig && !snapUnavailable && c.isHardwareAccelerated()) {
            removeCallbacks(snapAhead);
            post(snapAhead);
        }
        a11y.drawn(); // an item that opened now has bounds, for screen readers
    }

    /**
     * Draws the home screen under the Control Center from a layer. The layer is recorded one time and drawn each frame,
     * dimmed by its paint. It is recorded again only when the fingerprint changes.
     */
    private void drawFrozenHome(Canvas c, boolean covered) {
        if (homeNode == null) homeNode = new RenderNode("home");
        boolean first = Double.isNaN(frozenSig); // the first frame of this Control Center
        if (homeSig != frozenSig) {
            homeNode.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = homeNode.beginRecording();
            rc.scale(scale, scale);
            drawHome(rc, covered);
            homeNode.endRecording();
            frozenSig = homeSig;
        }
        // A new snapshot on the first frame if the home changed. Until it arrives, the glass shows the backdrop, not a
        // snapshot of a different home. During an animation the snapshot from this Control Center stays, and a new one
        // waits until nothing moves.
        if (first && homeBlurSig != frozenSig) homeBlurShader = null;
        if (homeBlurSig != frozenSig && !snapUnavailable && (first || homeBlurShader == null || !animating)) {
            long wait = first ? 0 : SNAP_MS - (SystemClock.uptimeMillis() - homeBlurAt);
            if (wait <= 0) snapHome(covered, frozenSig);
            else postInvalidateDelayed(wait);
        }
        homePaint.setColorFilter(dimFilter(Math.round(110 * ccAnim))); // the dim of the Control Center
        homeNode.setUseCompositingLayer(true, homePaint);
        c.save();
        c.scale(1f / scale, 1f / scale);
        c.drawRenderNode(homeNode);
        c.restore();
    }

    /**
     * Makes a blurred snapshot of the home screen under the Control Center, cards included, for its glass. The UI
     * thread records the home screen into a new node; snapDraw draws, reads back and blurs it on the snapshot thread.
     * One snapshot at a time. It runs while the clock pill has the focus and nothing moves, or when the Control Center
     * opens, and again when the home screen changes. {@code sig}: the fingerprint of the home screen that it draws.
     */
    private void snapHome(boolean covered, double sig) {
        long now = SystemClock.uptimeMillis();
        if (snapBusy && now - homeBlurAt < SNAP_TIMEOUT_MS) return; // the result asks for the next snapshot
        homeBlurAt = now; // the next try waits SNAP_MS
        int w = Backdrops.BLUR_W, h = Math.max(1, Math.round(w * height / W));
        // The UI thread does not change this node after endRecording, so the snapshot thread can draw it.
        RenderNode node = new RenderNode("home snapshot");
        node.setPosition(0, 0, w, h);
        RecordingCanvas rc = node.beginRecording();
        rc.scale(w / W, h / height);
        drawHome(rc, covered);
        node.endRecording();
        if (snapper == null) snapper = new Snapper();
        Snapper s = snapper;
        snapBusy = true;
        s.handler.post(() -> snapDraw(s, node, w, h, sig));
    }

    /**
     * Draws the snapshot offscreen, reads it back and blurs it, then gives it to the glass on the UI thread. Snapshot
     * thread. {@code s}: the snapshot thread that runs it.
     */
    private void snapDraw(Snapper s, RenderNode node, int w, int h, double sig) {
        Bitmap blurred = null;
        boolean failed = false;
        try {
            if (s.reader == null || s.reader.getWidth() != w || s.reader.getHeight() != h) {
                s.close();
                s.reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                s.renderer = new HardwareRenderer();
                s.renderer.setSurface(s.reader.getSurface());
            }
            s.renderer.setContentRoot(node);
            s.renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            try (Image image = s.reader.acquireLatestImage()) {
                HardwareBuffer buffer = image != null ? image.getHardwareBuffer() : null;
                if (buffer != null) {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, null);
                    buffer.close();
                    if (hardware != null) {
                        Bitmap pixels = hardware.copy(Bitmap.Config.ARGB_8888, true); // mutable: blurred in place
                        hardware.recycle();
                        if (pixels != null) blurred = Backdrops.boxBlur(pixels);
                    }
                }
            }
        } catch (OutOfMemoryError e) {
            // No snapshot this time.
        } catch (RuntimeException e) {
            // No offscreen renderer: the glass shows the backdrop only.
            failed = true;
            s.close();
        }
        Bitmap result = blurred;
        boolean unavailable = failed;
        post(() -> {
            if (s != snapper) return; // the snapshot thread was released
            snapBusy = false;
            if (unavailable) {
                snapUnavailable = true;
                releaseSnap();
                return;
            }
            // A result of a different home before this Control Center is dropped. drawFrozenHome asks for a new one.
            boolean stale = !Double.isNaN(frozenSig) && sig != frozenSig && homeBlurShader == null;
            if (result != null && !stale) {
                homeBlur = result;
                homeBlurShader = new android.graphics.BitmapShader(result, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                homeBlurSig = sig;
            }
            if (result != null) invalidate();
        });
    }

    /** Makes the snapshot of the home screen while nothing moves, before the Control Center opens. */
    private void snapAhead() {
        if (animating || !pillFocused || ccAnim > 0f || homeBlurSig == homeSig || !isAttachedToWindow()) return;
        snapHome(folderCover() > 0.999f, homeSig);
    }

    /** Stops the snapshot thread after it closes its renderer. A result that comes later is ignored. */
    private void releaseSnap() {
        snapBusy = false;
        if (snapper == null) return;
        Snapper s = snapper;
        snapper = null;
        s.handler.post(s::close);
        s.thread.quitSafely();
    }

    /** The offscreen renderer of the snapshot and its thread. Only that thread uses the renderer and the reader. */
    private static final class Snapper {
        final HandlerThread thread = new HandlerThread("atvos-snap");
        final Handler handler;
        HardwareRenderer renderer;
        ImageReader reader;

        Snapper() {
            thread.start();
            handler = new Handler(thread.getLooper());
        }

        void close() {
            if (renderer != null) renderer.destroy();
            if (reader != null) reader.close();
            renderer = null;
            reader = null;
        }
    }

    /** Draws the home screen under the overlays: the backdrop, the row or details, the dock and grid, and the pager. */
    private void drawHome(Canvas c, boolean covered) {
        if (!covered) drawBackground(c);

        float offset = lift() - scroll - bounce;
        if (!covered) {
            drawRow(c, offset);
            if (expand > 0.01f) drawDetails(c);
        }
        // The dock and the grid, unless a folder covers them or they are off the screen for the full-screen shelf.
        if (!covered && expand < 0.999f) {
            c.save();
            c.translate(0, offset);
            drawDock(c, offset);
            drawHiddenSection(c);
            int n = total();
            for (int i = 0; i < n; i++) if (i != focus) drawHomeTile(c, i, offset);
            // The menu dims all items except its tile.
            if (menuAnim > 0f && openFolder == null) c.drawColor(Color.argb(Math.round(100 * menuAnim), 0, 0, 0));
            if (focus < n) drawHomeTile(c, focus, offset);
            drawHint(c);
            drawRevealHint(c);
            c.restore();
        }
        if (pagerAnim > 0.01f && !covered) drawPager(c);
    }

    /**
     * Draws the backdrop and, during a crossfade, the previous backdrop. Layers that are not visible are skipped. The
     * grid dim is a colour filter on the image draw. The scrims are in the pictures (see {@link Host#setFullScreen}).
     */
    private void drawBackground(Canvas c) {
        dim = Math.round(away * 100);
        backdropPaint.setColorFilter(dimFilter(dim));
        boolean crossfading = previous != null && fade < 0.98f;
        if (crossfading) drawBackdrop(c, previous, 255);
        if (backdrop != null) drawBackdrop(c, backdrop, crossfading ? Math.round(255 * fade) : 255);
    }

    /** Draws the backdrop sharp, blurred, or during a transition the blurred copy over the sharp one. */
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

    /** Returns a filter that darkens by {@code alpha}/255, as a black overlay with that alpha does. */
    private ColorFilter dimFilter(int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        if (a == 0) return null;
        if (dimFilters[a] == null) {
            int k = 255 - a;
            dimFilters[a] = new LightingColorFilter(Color.rgb(k, k, k), 0);
        }
        return dimFilters[a];
    }

    /**
     * Draws a glass shape: the blurred backdrop, more saturated, with its tint and the screen dim, then a rim. The
     * glass is one pass for any number of backdrops. {@code offsetY}: the current y translation of the canvas.
     */
    private void drawGlass(Canvas c, RectF rect, float radius, float offsetY, int tint, int alpha) {
        drawGlass(c, rect, radius, offsetY, tint, alpha, false);
    }

    /**
     * {@code overHome}: glass over the full home screen (the Control Center). It shows the snapshot of the home screen,
     * not only the backdrop.
     */
    private void drawGlass(Canvas c, RectF rect, float radius, float offsetY, int tint, int alpha, boolean overHome) {
        Bitmap blur = null;
        android.graphics.BitmapShader shader = null;
        if (overHome && homeBlurShader != null) {
            blur = homeBlur;
            shader = homeBlurShader;
        } else if (previous != null && backdrop != null && mixBlurs()) {
            blur = mixedBlur;
            shader = mixedShader;
        } else if (backdrop != null) {
            blur = backdrop.blur;
            shader = backdrop.blurShader;
        }
        if (shader != null) {
            m.setScale(W / blur.getWidth(), height / blur.getHeight());
            m.postTranslate(0f, -offsetY);
            shader.setLocalMatrix(m);
            glass.setShader(shader);
            glass.setColorFilter(glassFilter(tint, overHome && blur == homeBlur ? 0 : dim)); // the snapshot is already dimmed
            glass.setAlpha(alpha);
            c.drawRoundRect(rect, radius, radius, glass);
        }
        m.setScale(1f, rect.height());
        m.postTranslate(0f, rect.top);
        rimGradient.setLocalMatrix(m);
        rim.setAlpha(alpha);
        c.drawRoundRect(rect, radius, radius, rim);
    }

    /**
     * Returns the grey for text and glyphs on the glass over {@code rect}: white, and darker as the glass becomes
     * brighter.
     */
    private int glassInk(RectF rect) {
        float light = lightUnder(backdrop, rect);
        if (previous != null) light = light * fade + lightUnder(previous, rect) * (1f - fade);
        float ta = Color.alpha(GLASS_TINT) / 255f;
        light = light * (1f - ta) + ta; // under the white tint of the glass
        float t = Math.max(0f, Math.min(1f, (light - INK_FROM) / (INK_TO - INK_FROM)));
        t = t * t * (3f - 2f * t);
        return Math.round(255 - (255 - INK_DARK) * t);
    }

    /**
     * Returns the mean luminance (0 to 1) of the blur of a backdrop under {@code rect}, in design units on the screen.
     */
    private float lightUnder(Backdrops.Backdrop b, RectF rect) {
        if (b == null) return 0f;
        int bw = b.blur.getWidth(), bh = b.blur.getHeight();
        int x0 = Math.max(0, Math.min(bw - 1, (int) (rect.left / W * bw)));
        int x1 = Math.max(x0 + 1, Math.min(bw, (int) Math.ceil(rect.right / W * bw)));
        int y0 = Math.max(0, Math.min(bh - 1, (int) (rect.top / height * bh)));
        int y1 = Math.max(y0 + 1, Math.min(bh, (int) Math.ceil(rect.bottom / height * bh)));
        long red = 0, green = 0, blue = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = b.blurPixels[y * bw + x];
                red += (p >> 16) & 255;
                green += (p >> 8) & 255;
                blue += p & 255;
            }
        }
        return (0.2126f * red + 0.7152f * green + 0.0722f * blue) / (255f * (x1 - x0) * (y1 - y0));
    }

    /**
     * Returns the colour filter of the glass. The filter makes the blur 30% more saturated, then adds the tint and the
     * black dim as one fill: out = blur × (1 − a) + tint × its alpha. It is one matrix.
     */
    private ColorFilter glassFilter(int tint, int dimAlpha) {
        int level = dimAlpha / 4; // the dim in steps of 4
        for (int i = 0; i < glassFilters.length; i++) {
            if (glassFilters[i] != null && glassTints[i] == tint && glassDims[i] == level) return glassFilters[i];
        }
        float ta = Color.alpha(tint) / 255f, da = level * 4 / 255f;
        float a = ta + da * (1f - ta), k = 1f - a;
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.3f);
        cm.postConcat(new ColorMatrix(new float[]{
                k, 0, 0, 0, Color.red(tint) * ta,
                0, k, 0, 0, Color.green(tint) * ta,
                0, 0, k, 0, Color.blue(tint) * ta,
                0, 0, 0, 1, 0}));
        int i = glassFilterNext;
        glassFilterNext = (i + 1) % glassFilters.length;
        glassTints[i] = tint;
        glassDims[i] = level;
        glassFilters[i] = new ColorMatrixColorFilter(cm);
        return glassFilters[i];
    }

    /**
     * Mixes the blurs of the two backdrops into {@link #mixedBlur} for the progress of the crossfade, so that the glass
     * draws one texture. It runs once a frame. Returns false if the blurs do not have the same size.
     */
    private boolean mixBlurs() {
        int[] from = previous.blurPixels, to = backdrop.blurPixels;
        Bitmap b = backdrop.blur;
        if (from.length != to.length || previous.blur.getWidth() != b.getWidth()) return false;
        if (mixedBlur == null || mixedBlur.getWidth() != b.getWidth() || mixedBlur.getHeight() != b.getHeight()) {
            mixedBlur = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
            mixedShader = new android.graphics.BitmapShader(mixedBlur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mixedPixels = new int[to.length];
            mixedLevel = -1;
        }
        int t = Math.round(fade * 256f);
        if (t == mixedLevel && mixedFrom == previous && mixedTo == backdrop) return true;
        int u = 256 - t;
        for (int i = 0; i < to.length; i++) {
            int p = from[i], q = to[i];
            int r = (((p >> 16) & 255) * u + ((q >> 16) & 255) * t) >> 8;
            int g = (((p >> 8) & 255) * u + ((q >> 8) & 255) * t) >> 8;
            int bl = ((p & 255) * u + (q & 255) * t) >> 8;
            mixedPixels[i] = 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
        mixedBlur.setPixels(mixedPixels, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        mixedLevel = t;
        mixedFrom = previous;
        mixedTo = backdrop;
        return true;
    }

    private void drawDock(Canvas c, float offset) {
        r.set(GRID_X - DOCK_PAD_X, DOCK_ROW_Y - DOCK_PAD_Y, EDGE, DOCK_ROW_Y + TILE_H + DOCK_PAD_Y);
        if (r.bottom + offset < 0f || r.top + offset > height) return;
        drawGlass(c, r, TILE_R + DOCK_PAD_Y, offset, GLASS_TINT, 255);
    }

    /** Draws a home screen tile: greyed out in Hidden, with its name when it has the focus below the dock. */
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
            // The held app wiggles.
            double t = SystemClock.uptimeMillis() / 1000.0;
            c.rotate((float) Math.sin(t * Math.PI * 2 * 3.2) * 1.4f, cx, cy);
        }
        if (f > 0.01f && shadowMask != null) {
            // A blurred mask, scaled with the tile.
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

        // The name shows below a focused icon in the grid, not in the dock.
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

    /** Draws the tile of a folder: frosted glass with its first apps in miniature, three in each row. */
    private void drawFolderTile(Canvas c, Apps.Entry e, RectF box, float radius, float offset) {
        drawGlass(c, box, radius, offset, 0x33FFFFFF, 255);
        List<Apps.Entry> in = e.folder.apps;
        float w = box.width(), mw = w * 0.23f, mh = mw * TILE_H / TILE_W, gap = w * 0.04f;
        float padX = (w - 3 * mw - 2 * gap) / 2f, padY = (box.height() - 3 * mh - 2 * gap) / 2f;
        art.setAlpha(Math.round(255 * tileFade)); // else the alpha of the last tile drawn, from this frame or the last
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
     * Draws an open folder: the blurred backdrop over the home screen, and the name in a pill above a large glass
     * panel. The panel shows the apps with four in each row.
     */
    private void drawFolder(Canvas c) {
        Apps.Entry tile = shownFolder;
        int alpha = Math.round(255 * folderAnim);
        if (alpha <= 2 || tile == null) return;
        // The blur of the home screen covers it first, quickly, so that the home screen stops early. The blur is
        // darkened in the same draw.
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
        drawFrost(c, r, FOLDER_R, 0x55FFFFFF, alpha); // over the blur: no second blur
        drawFolderName(c, alpha);

        // The apps scroll in the panel and each fades in as the folder opens. The menu dims all except its app.
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

    /** Returns the opacity of the blurred home screen behind a folder. It leads the folder animation. */
    private float folderCover() {
        return Math.min(1f, folderAnim * 1.6f);
    }

    /** Draws frosted glass over a blur (the panel of an open folder): its tint and rim, with no second blur. */
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

    /** Draws the folder name: dark glass, or white when it has the focus. A caret shows during the rename. */
    private void drawFolderName(Canvas c, int alpha) {
        float f = folderNameFocus, grow = 1f + 0.08f * f, size = titleText.getTextSize();
        titleText.setTextSize(38f * grow);
        String shown = shownFolderName();
        float tw = titleText.measureText(shown), w = Math.max(tw, 40f) + 72f * grow, h = FOLDER_NAME_H * grow;
        r.set(W / 2f - w / 2f, FOLDER_NAME_Y - h / 2f, W / 2f + w / 2f, FOLDER_NAME_Y + h / 2f);
        if (f < 0.99f) drawGlass(c, r, h / 2f, 0f, 0x59000000, Math.round(alpha * (1f - f)));
        if (f > 0.01f) {
            fill.setColor(Color.argb(Math.round(alpha * f * 0.96f), 255, 255, 255));
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
        }
        int ink = blend(Color.WHITE, INK, f);
        titleText.setColor(ink);
        titleText.setAlpha(alpha);
        c.drawText(shown, W / 2f, FOLDER_NAME_Y + 13f * grow, titleText);
        if (renaming && SystemClock.uptimeMillis() / 530 % 2 == 0) {
            fill.setColor(ink);
            fill.setAlpha(alpha);
            float x = W / 2f + tw / 2f + 4f;
            c.drawRect(x, FOLDER_NAME_Y - 20f * grow, x + 3f, FOLDER_NAME_Y + 20f * grow, fill);
        }
        titleText.setTextSize(size);
    }

    /**
     * Returns the folder name as it shows: cut to the width of the folder, at its end, or at its start while the user
     * types it. It uses the size that {@link #titleText} has now.
     */
    private String shownFolderName() {
        return TextUtils.ellipsize(folderName, titleText, FOLDER_W - 160f,
                renaming ? TextUtils.TruncateAt.START : TextUtils.TruncateAt.END).toString();
    }

    /** Draws "Press up for full screen" above the dock, in the tint from the picture behind. */
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
        // One colour for all of the text, so a soft shadow keeps it legible over a busy or bright picture.
        boolean light = luminance(hintTint) > 0.5f;
        hintText.setColor(tint);
        hintText.setShadowLayer(10f, 0f, 1f, light ? Color.argb(Math.round(alpha * 0.55f), 0, 0, 0) : 0);
        c.drawText(fullScreenHint, W / 2f, dockTop - 23f, hintText);
    }

    /**
     * Draws the page controls of the full-screen shelf. A glass circle with a chevron shows at each side that has a
     * page. The page dots are in a glass capsule.
     */
    private void drawPager(Canvas c) {
        int alpha = Math.round(255 * pagerAnim);
        float cy = height / 2f, rad = PAGER_ARROW_D / 2f;
        for (int side = -1; side <= 1; side += 2) {
            if (side < 0 ? page <= 0 : page >= pages.size() - 1) continue;
            float cx = side < 0 ? PAGER_ARROW_X : W - PAGER_ARROW_X;
            r.set(cx - rad, cy - rad, cx + rad, cy + rad);
            drawGlass(c, r, rad, 0f, GLASS_TINT, alpha);
            int ink = glassInk(r);
            c.save();
            c.translate(cx + side * 2f, cy);
            c.scale(side, 1f);
            stroke.setStrokeWidth(4f);
            stroke.setColor(Color.argb(alpha, ink, ink, ink));
            c.drawPath(pagerChevron, stroke);
            stroke.setStrokeWidth(3f);
            c.restore();
        }
        float w = (pages.size() - 1) * PAGER_DOT_STEP + 2f * PAGER_DOTS_H, cx = W / 2f;
        float bottom = height - PAGER_DOTS_BOTTOM;
        r.set(cx - w / 2f, bottom - PAGER_DOTS_H, cx + w / 2f, bottom);
        drawGlass(c, r, PAGER_DOTS_H / 2f, 0f, GLASS_TINT, alpha);
        int ink = glassInk(r);
        for (int i = 0; i < pages.size(); i++) {
            float dx = cx + (i - (pages.size() - 1) / 2f) * PAGER_DOT_STEP;
            fill.setColor(Color.rgb(ink, ink, ink));
            fill.setAlpha(Math.round(alpha * (i == page ? 1f : 0.25f)));
            c.drawCircle(dx, bottom - PAGER_DOTS_H / 2f, PAGER_DOT / 2f, fill);
        }
    }

    /**
     * Draws the clock: a glass pill, white when it has the focus. When the Control Center is open, it is the status
     * row: the time at the left, the status glyphs, the Control Center buttons and the profile.
     */
    private void drawClock(Canvas c) {
        int alpha = Math.round(255 * Math.max(Math.max((1f - expand) * (1f - away), pillAnim), ccAnim));
        if (alpha <= 2) return;
        float h = CLOCK_H, pad = 22f, gap = 12f, size = AVATAR, cy = CLOCK_TOP + h / 2f;

        int pillAlpha = Math.round(alpha * (1f - ccAnim));
        if (pillAlpha > 2) {
            float grow = 1f + 0.05f * pillAnim;
            // The hint widens the pill to the left. The time and the hint stay at the right, next to the avatar.
            float content = timeWidth + (Math.max(holdW, timeWidth) - timeWidth) * holdHint;
            float w = (pad + content + gap + size + 8f) * grow, gh = h * grow;
            r.set(EDGE - w, cy - gh / 2f, EDGE, cy + gh / 2f);
            drawGlass(c, r, gh / 2f, 0f, GLASS_TINT, pillAlpha);
            if (pillAnim > 0.01f) {
                fill.setColor(Color.argb(Math.round(pillAlpha * pillAnim * 0.96f), 255, 255, 255));
                c.drawRoundRect(r, gh / 2f, gh / 2f, fill);
            }
            int ink = Math.round(255 - (255 - 28) * Math.max(clockInk, Math.min(1f, pillAnim * 1.4f)));
            float end = r.left + (pad + content) * grow; // the right end of the text
            float timeFade = 1f - Math.min(1f, holdHint * 2.5f);
            if (timeFade > 0.01f) {
                clockText.setColor(Color.argb(Math.round(pillAlpha * timeFade), ink, ink, ink));
                c.drawText(time, end - timeWidth * grow, cy + 9.5f, clockText);
            }
            float hintFade = Math.max(0f, (holdHint - 0.35f) / 0.65f);
            if (hintFade > 0.01f) drawHoldHint(c, end - holdW, cy, ink, Math.round(pillAlpha * hintFade * hintFade));
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

    /**
     * Draws "Hold [Home] for Control Center" from {@code x}, clipped to the pill in {@link #r}: the pill reveals it as
     * it widens. The Home button is a filled circle in the colour of the text, with the house in the opposite colour.
     */
    private void drawHoldHint(Canvas c, float x, float cy, int ink, int alpha) {
        c.save();
        c.clipRect(r.left + r.height() / 4f, r.top, r.right, r.bottom);
        clockText.setColor(Color.argb(alpha, ink, ink, ink));
        c.drawText(holdBefore, x, cy + 9.5f, clockText);
        float kx = x + holdBeforeW + 2f;
        fill.setColor(Color.argb(Math.round(alpha * 0.92f), ink, ink, ink));
        c.drawCircle(kx + HOLD_KEY / 2f, cy, HOLD_KEY / 2f, fill);
        int glyph = ink > 128 ? 28 : 255;
        if (homeIcon != null) {
            float g = 20f;
            drawTinted(c, homeIcon, kx + (HOLD_KEY - g) / 2f, cy - g / 2f - 0.5f, g, Color.rgb(glyph, glyph, glyph), alpha);
        }
        c.drawText(holdAfter, kx + HOLD_KEY + 2f, cy + 9.5f, clockText);
        c.restore();
    }

    /**
     * Returns the bounds of card {@code i} of the row, scaled by {@code k} about its centre, before the dock offset.
     */
    private RectF rowCardRect(int i, float k, RectF out) {
        float x = rowLefts[i] - rowScroll, w = rowCards.get(i).width(), h = Backdrops.ROW_CARD_H;
        float cx = x + w / 2f, cy = ROW_TOP + h / 2f;
        out.set(cx - w * k / 2f, cy - h * k / 2f, cx + w * k / 2f, cy + h * k / 2f);
        return out;
    }

    /**
     * Draws the row shelf: 16:9 or square cards over the plain background. The title of each section is above its first
     * card, and stays at the left while its cards scroll. A program that is partly watched has a progress bar. The
     * focused card is larger and has its title below it. The row moves up with the dock when the focus goes into the
     * grid.
     */
    private void drawRow(Canvas c, float offset) {
        Backdrops.Backdrop b = backdrop;
        if (rowCards.isEmpty() || b == null || b.row != rowCards) return;
        int alpha = Math.round(255 * (1f - away));
        if (alpha <= 2) return;
        c.save();
        c.translate(0f, offset);
        float headerY = ROW_HEADER_Y - ROW_HEADER_LIFT * rowAnim;
        rowHeaderText.setColor(light ? 0xFF3F3F41 : 0xFF8A8A8E); // 4.5:1 or more on the light and the dark row
        rowHeaderText.setAlpha(alpha);
        for (int i = 0; i < rowCards.size(); i++) {
            if (rowHeaders[i] == null) continue;
            float x = Math.max(ROW_X + 2f, rowLefts[i] + 2f - rowScroll);
            for (int j = i + 1; j < rowCards.size(); j++) {
                if (rowHeaders[j] == null) continue;
                x = Math.min(x, rowLefts[j] - rowScroll - rowHeaderWidths[i] - 48f); // the next section pushes it off
                break;
            }
            if (x + rowHeaderWidths[i] > 0f && x < W) c.drawText(rowHeaders[i], x, headerY, rowHeaderText);
        }
        for (int i = 0; i < rowCards.size(); i++) if (i != rowIndex) drawRowCard(c, i, alpha);
        if (rowIndex < rowCards.size()) drawRowCard(c, rowIndex, alpha); // the focused card last, on top
        c.restore();
    }

    private void drawRowCard(Canvas c, int i, int alpha) {
        Backdrops.Card card = rowCards.get(i);
        float f = rowFocus[i];
        RectF rect = rowCardRect(i, 1f + (ROW_FOCUS_SCALE - 1f) * f, r);
        if (rect.right < 0f || rect.left > W) return;
        float k = rect.height() / Backdrops.ROW_CARD_H; // by height: a square card has the same bar and corners as a wide card
        if (f > 0.01f && shadowMask != null) { // a soft drop shadow below the focused card
            // The edge of the mask has the size for a focused tile, so it is scaled to the card on each axis.
            float padX = SHADOW_PAD * rect.width() / (TILE_W * FOCUS_SCALE), padY = SHADOW_PAD * rect.height() / (TILE_H * FOCUS_SCALE);
            float dy = SHADOW_DY * 2f * f;
            r2.set(rect.left - padX, rect.top - padY + dy, rect.right + padX, rect.bottom + padY + dy);
            shadow.setAlpha(Math.round(alpha * (light ? 0.55f : 0.85f) * f));
            c.drawBitmap(shadowMask, null, r2, shadow);
        }
        cardPaint.setAlpha(alpha);
        c.drawBitmap(card.picture(), null, rect, cardPaint); // the picture has round corners
        if (f > 0.01f) { // the same glass rim as the dock, the pill and the buttons
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
            // White on the dark row, and dark on the light row: white there has too little contrast.
            TextPaint text = rowTitleWidths[i] <= rect.width() ? rowTitleText : rowMarqueeText;
            text.setColor(light ? INK : Color.WHITE);
            text.setAlpha(Math.round(alpha * f * rowAnim));
            float y = rect.bottom + 31f;
            if (text == rowTitleText) {
                c.drawText(rowTitles[i], rect.centerX(), y, rowTitleText);
            } else { // too long: starts at the left edge of the card, fades out at its right, and scrolls while focused
                float off = i == marqueeCard ? marqueeOffset : 0f;
                fitMarqueeFade(card.width() * ROW_FOCUS_SCALE, light ? INK : Color.WHITE); // the shader sets the colour
                float left = rect.left - MARQUEE_FADE * (1f - Math.min(1f, off / MARQUEE_FADE)); // the left fade, while it moves
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

    /**
     * Makes the edge fades of the marquee, MARQUEE_FADE wide, for a focused card of this width. It makes them again
     * only when the width changes.
     */
    private void fitMarqueeFade(float width, int color) {
        if (width == marqueeFadeFor && color == marqueeFadeColor) return;
        float edge = MARQUEE_FADE / width; // across the card, 0 to 1
        int clear = color & 0x00FFFFFF;
        marqueeFade = new LinearGradient(0, 0, 1, 0, new int[]{clear, color, color, clear},
                new float[]{0f, edge, 1f - edge, 1f}, Shader.TileMode.CLAMP);
        rowMarqueeText.setShader(marqueeFade);
        marqueeFadeFor = width;
        marqueeFadeColor = color;
    }

    /** Lays out the details of the full-screen shelf for the program, once for each program. */
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
     * Draws the details of the full-screen shelf at the bottom left, over the scrim. The details are the title, a line
     * of facts, the description and Play. Select plays the program.
     */
    private void drawDetails(Canvas c) {
        Backdrops.Backdrop b = backdrop;
        Recommendations.Item item = b != null && b.row == null ? b.program : null;
        if (item == null) return;
        if (item != detailsFor) layOutDetails(item);
        int alpha = Math.round(255 * expand);
        // The details scrim is in the full-screen picture. This draws only the part that the pictures on the screen do
        // not have yet (for example, before the full-screen picture is ready).
        float baked = (b.details ? 1f : 0f) * (previous != null ? fade : 1f)
                + (previous != null && previous.details ? 1f - fade : 0f);
        int scrim = Math.round(255 * Math.max(0f, expand - baked));
        if (scrim > 2) {
            detailsScrim.setAlpha(scrim);
            c.drawRect(0f, 0f, W, height, detailsScrim);
        }
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
     * Draws Play: a white capsule with a black play triangle and "Play". For a program that is partly watched, a
     * progress bar and the time that remains replace the word. It is glass with adaptive ink when the focus is not on
     * it.
     */
    private void drawPlayButton(Canvas c, float x, float y, int alpha) {
        float f = playFocus;
        r.set(x, y, x + detailsPlayWidth, y + PLAY_H);
        playRect.set(r);
        int glassInk = 255;
        if (f < 0.99f) {
            drawGlass(c, r, PLAY_H / 2f, 0f, GLASS_TINT, Math.round(alpha * (1f - f)));
            glassInk = glassInk(r);
        }
        if (f > 0.01f) {
            fill.setColor(Color.argb(Math.round(alpha * f * 0.97f), 255, 255, 255));
            c.drawRoundRect(r, PLAY_H / 2f, PLAY_H / 2f, fill);
        }
        int level = Math.round(glassInk + (20 - glassInk) * f), ink = Color.argb(alpha, level, level, level);
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
            // The track: light grey on the white capsule, faint ink on the glass.
            int track = Math.round(glassInk + (204 - glassInk) * f);
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
     * Draws the long-press menu: a glass list next to the tile (at its right, or at its left if there is no space). The
     * selected option is a white capsule that moves between the options (red on Delete App). An option with a chevron
     * opens a second list next to it and keeps a faint capsule.
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

    /** Draws one list of the menu. {@code openRow}: the option whose list is open (a faint capsule), or -1. */
    private void drawList(Canvas c, RectF box, List<MenuRow> rows, float selAnim, int openRow, int alpha, boolean active) {
        float pad = MENU_PAD, itemH = MENU_ITEM_H, radius = itemH / 2f;
        // Frosted glass with corners that follow the capsules. It is light over the dimmed grid, and darker over a
        // bright backdrop in the dock, so that the white text is legible.
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

    /** Draws the Delete alert: frosted glass at the centre, with Cancel and Delete next to each other. */
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

    /** Returns a colour channel moved by the fraction {@code t} toward white. */
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
     * Draws the hint for a long press of Down on the last row: an arrow below the row, with a ring that closes during
     * the press. When the ring closes, the focus goes into the Hidden section.
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
        c.scale(0.8f, -0.8f); // the up chevron of the hint, turned down
        c.drawPath(chevron, stroke);
        c.restore();
    }

    /** Draws the Hidden section: its title, and a drop target during an edit when no app is hidden. */
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

    /**
     * Returns a soft drop shadow for a focused tile, blurred one time in software. The alpha of the paint sets its
     * strength.
     */
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

    /** Returns the default profile picture: a grey disc with a person glyph. */
    static Bitmap makeAvatar(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, size, 0xFFA9A9AF, 0xFF6C6C72, Shader.TileMode.CLAMP));
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setShader(null);
        p.setColor(0xF2FFFFFF);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)); // clips the glyph to the disc
        c.drawCircle(size / 2f, size * 0.39f, size * 0.19f, p);
        c.drawOval(new RectF(size * 0.17f, size * 0.66f, size * 0.83f, size * 1.30f), p);
        return b;
    }

    // Screen readers

    /**
     * The items for TalkBack. An open menu or Control Center gives its items only, because it takes all input. Else,
     * the items are the clock pill, the Play button of a shelf, and the app tiles.
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
            if (alertEntry != null) { // Cancel also reads the question
                float bw = (ALERT_W - 2 * ALERT_PAD - 20f) / 2f, by = alertBox.bottom - ALERT_PAD - ALERT_BUTTON_H;
                for (int i = 0; i < 2; i++) {
                    float bx = alertBox.left + ALERT_PAD + i * (bw + 20f);
                    out.add(new A11y.Item(A11Y_ALERT + i, i == 0 ? cancelLabel : deleteLabel, null,
                            i == 0 ? alertTitle + " " + alertMessage.getText() : null,
                            alertBox.isEmpty() ? new RectF() : new RectF(bx, by, bx + bw, by + ALERT_BUTTON_H), i == alertSel));
                }
                return;
            }
            if (menuOpen) {
                rows(out, menuRows, menuBox, A11Y_MENU, subOf < 0 ? menuSel : -1);
                if (subOf >= 0) rows(out, subRows, subBox, A11Y_SUB, subSel);
                return;
            }
            if (openFolder != null) {
                // The width of the pill as drawn (38 px text, cut to fit), without the focus growth.
                float size = titleText.getTextSize();
                titleText.setTextSize(38f);
                float w = Math.max(titleText.measureText(shownFolderName()), 40f) + 72f;
                titleText.setTextSize(size);
                out.add(new A11y.Item(A11Y_FOLDER, folderName, c.getString(R.string.a11y_folder), c.getString(R.string.a11y_rename_hint),
                        new RectF(W / 2f - w / 2f, FOLDER_NAME_Y - FOLDER_NAME_H / 2f, W / 2f + w / 2f, FOLDER_NAME_Y + FOLDER_NAME_H / 2f),
                        folderFocus < 0));
                List<Apps.Entry> in = openFolder.folder.apps;
                for (int i = 0; i < in.size(); i++) {
                    Apps.Entry e = in.get(i);
                    boolean current = i == folderFocus;
                    out.add(new A11y.Item(A11Y_FOLDER + 1 + i, e.label, editing && current ? c.getString(R.string.a11y_moving) : null,
                            editing && current ? c.getString(R.string.a11y_moving_hint) : null,
                            new RectF(e.x, e.y - folderScroll, e.x + TILE_W, e.y - folderScroll + TILE_H), current, true, !editing));
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
                String hold = RemoteKeys.isOn() ? c.getString(R.string.a11y_hint_hold_home) : null;
                out.add(new A11y.Item(A11Y_PILL, c.getString(R.string.a11y_control_center), time, hold,
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
                        new RectF(e.x, e.y + offset, e.x + TILE_W, e.y + offset + TILE_H), current, true, !editing));
            }
        }

        /** Adds the options of a menu list to {@code out}, with the state of each switch. */
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
                if (editing) return i == focus; // the D-pad moves the held app
                pillFocused = false;
                rowFocused = false;
                if (expanded) setExpanded(false);
                focus = i;
                focusChanged();
            } else if (id >= A11Y_FOLDER) {
                int i = id - A11Y_FOLDER - 1;
                if (openFolder == null || menuOpen || alertEntry != null || i >= openFolder.folder.apps.size()) return false;
                if (editing) return i == folderFocus; // the D-pad moves the held app
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
            } else {
                pillFocused = false; // Play, not the clock pill, gets Select
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

        /** Opens the long-press menu of an app on the home screen or in a folder. */
        @Override
        public boolean longClick(int id) {
            if (id <= A11Y_FOLDER || editing || !focus(id)) return false; // apps only: the folder name has no menu
            openMenu();
            a11y.sync();
            return menuOpen;
        }

        @Override
        public float scale() {
            return scale;
        }
    }
}
