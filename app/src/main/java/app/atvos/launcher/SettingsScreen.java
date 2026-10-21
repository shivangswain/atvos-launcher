package app.atvos.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * atvOS Settings, with the layout of tvOS Settings. The page title is at the top, and the rows are at the right. The
 * atvOS logo is at the left, with a description of the focused row. The focused row is white. A row that opens a page
 * has a chevron.
 * <pre>
 *   Home Screen ›            (only when another app has the Home button)
 *   General ›                About › (Model, atvOS, Android TV) · Appearance › · Privacy ·
 *                            Acknowledgements · DATE &amp; TIME: Time Format
 *   Profiles and Accounts ›  Account · Profile Picture ›
 *   Uninstall ›              Restore Default Launcher · Remove Power Control
 *   Support ›                Website › · Open Play Store › · App Info › · Donate ›
 * </pre>
 * The colours are light or dark, as set in Appearance. The switches in Control Center open it. UI thread.
 */
final class SettingsScreen {

    interface Host {
        Controls.Setup setupState();
        /** Starts the setup steps that give the Home button to atvOS. This screen closes first. */
        void setUpHome();
        void restoreHome(HomeView.SelfSetup progress);
        void releasePower();
        /** Returns the Google account of the profile picture, or null. */
        String account();
        void chooseAccount();
        /** Returns {@link Profile#PHOTO}, {@link Profile#INITIAL} or {@link Profile#NONE}. */
        String picture();
        void setPicture(String picture);
        boolean light();
        void setLight(boolean light);
        boolean use24Hour();
        void setUse24Hour(boolean on);
        /** Opens {@link #WEBSITE}, {@link #STORE} or {@link #APP_INFO}. Returns false if the TV cannot open it. */
        boolean open(int what);
        /** Returns true if the TV has an app that can open it. */
        boolean canOpen(int what);
        Donation donation();
        void close();
        void sound(int effect);
        /** Draws the screen again and updates screen readers. */
        void changed();
    }

    /** The targets of {@link Host#open}: the atvOS website, the Google Play page of atvOS, and App Info for atvOS. */
    static final int WEBSITE = 0, STORE = 1, APP_INFO = 2;

    private static final int MAIN = 0, GENERAL = 1, ABOUT = 2, APPEARANCE = 3, PROFILES = 4, PICTURE = 5,
            UNINSTALL = 6, SUPPORT = 7;
    /**
     * Row kinds: NAV opens a page (with a chevron). ACTION does an action on the page (with its value). CHOICE is one
     * choice of a page (with a tick when selected). INFO is a fact (no pill). HEADER is the title of the section below
     * it.
     */
    private static final int NAV = 0, ACTION = 1, CHOICE = 2, INFO = 3, HEADER = 4;

    private static final float W = HomeView.W;
    // The row layout of tvOS Settings, in design units (1920 wide).
    private static final float ROW_X = 1063f, ROW_W = 773.5f, ROW_H = 65.5f, ROW_PITCH = 80f, ROW_TOP = 189f;
    private static final float HEADER_X = ROW_X + 29f, HEADER_BASELINE = 59.5f; // the left edge and the baseline of a section title
    private static final float ICON_X = 255f, ICON_Y = 237f, ICON_SIZE = 540f, NOTE_CX = 525f, NOTE_TOP = 832f, NOTE_W = 860f;
    private static final float TITLE_Y = 112f;

    /** A row. {@code about}: the description at the left while it is focused. {@code action}: the action of Select. */
    private record Row(int kind, String title, String value, String about, Runnable action, boolean enabled, boolean checked) {
        /** INFO rows are focusable too, so the remote and screen readers can move through a page of facts. */
        boolean focusable() {
            return kind != HEADER;
        }
    }

    private final Context context;
    private final Host host;
    private final Logo logo;
    private final TextPaint titleText = new TextPaint(Paint.ANTI_ALIAS_FLAG), rowText = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            valueText = new TextPaint(Paint.ANTI_ALIAS_FLAG), headerText = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            aboutText = new TextPaint(Paint.ANTI_ALIAS_FLAG), noteText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    // The drop shadow of the focused row: a mask, blurred one time and scaled with the row.
    private final Paint shadow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap shadowMask; // made when Settings first opens
    private final RectF shadowRect = new RectF();
    private static final float SHADOW_BLUR = 22f, SHADOW_PAD = 44f, SHADOW_DY = 8f, ROW_GROW_W = 1.016f, ROW_GROW_H = 1.06f;
    private final Path chevron = new Path(), tick = new Path();
    private final RectF r = new RectF(), logoRect = new RectF(ICON_X, ICON_Y, ICON_X + ICON_SIZE, ICON_Y + ICON_SIZE);
    private final List<Row> rows = new ArrayList<>();
    private final List<RectF> rowRects = new ArrayList<>(); // the bounds of each row at the last draw, for screen readers
    private float[] tops = new float[0];  // the top of each row before the scroll
    private String[] fitTitle = new String[0], fitValue = new String[0]; // fitted to the row once for each build
    private String noteFit, noteFitFor;
    private float[] focus = new float[0];
    private final ArrayDeque<int[]> under = new ArrayDeque<>(); // the pages that Back goes to: {page, focused row}
    private int page = MAIN, sel = -1;  // sel: the focused row, or -1 on a page of facts
    private float scroll, fade = 1f;
    private boolean busy;               // Restore runs over self-adb
    private String note;                // a status message below the description of the focused row
    private int noteRow = -1, notePage = -1;
    private StaticLayout about;         // the description of the focused row, laid out when it changes
    private String aboutFor;
    private String title = "";
    // The colours of this frame (light or dark).
    private int ink, valueInk, dimInk, offInk;

    SettingsScreen(Context context, Host host) {
        this.context = context;
        this.host = host;
        shadow.setColor(Color.BLACK);
        logo = new Logo(context);
        titleText.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        titleText.setTextSize(60f);
        titleText.setTextAlign(Paint.Align.CENTER);
        rowText.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        rowText.setTextSize(36f);
        valueText.setTypeface(Typeface.DEFAULT);
        valueText.setTextSize(36f);
        valueText.setTextAlign(Paint.Align.RIGHT);
        headerText.setTypeface(Typeface.create(Typeface.DEFAULT, 600, false));
        headerText.setTextSize(27f);
        aboutText.setTypeface(Typeface.DEFAULT);
        aboutText.setTextSize(27f);
        noteText.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        noteText.setTextSize(27f);
        noteText.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(3.5f);
        chevron.moveTo(-5f, -11f);
        chevron.lineTo(5f, 0f);
        chevron.lineTo(-5f, 11f);
        tick.moveTo(-10f, 1f); // a thin checkmark with a long stroke up to the right
        tick.lineTo(-3.5f, 8f);
        tick.lineTo(10f, -10f);
    }

    void open() {
        if (shadowMask == null) shadowMask = makeShadowMask();
        under.clear();
        page = MAIN;
        busy = false;
        enter(-1);
    }

    /** Ends the connection to Google Play. */
    void closed() {
        host.donation().end();
    }

    /** Builds the page again with the current values. */
    void refresh() {
        build();
        host.changed();
    }

    // Pages

    private void build() {
        rows.clear();
        switch (page) {
            case GENERAL -> {
                title = s(R.string.settings_general);
                rows.add(nav(R.string.settings_about, null, null, () -> go(ABOUT)));
                rows.add(nav(R.string.settings_appearance, s(host.light() ? R.string.settings_light : R.string.settings_dark),
                        s(R.string.settings_appearance_about), () -> go(APPEARANCE)));
                rows.add(action(R.string.settings_privacy, null, s(R.string.settings_privacy_about), null));
                rows.add(action(R.string.settings_acknowledgements, null, s(R.string.settings_acknowledgements_about), null));
                rows.add(new Row(HEADER, s(R.string.settings_date_time).toUpperCase(Locale.getDefault()), null, null, null, true, false));
                rows.add(action(R.string.settings_time_format, s(host.use24Hour() ? R.string.settings_24_hour : R.string.settings_12_hour),
                        s(R.string.settings_time_format_about), () -> host.setUse24Hour(!host.use24Hour())));
            }
            case ABOUT -> {
                title = s(R.string.settings_about);
                String model = Build.MODEL.startsWith(Build.MANUFACTURER) ? Build.MODEL : Build.MANUFACTURER + " " + Build.MODEL;
                rows.add(info(R.string.settings_model, model));
                rows.add(info(R.string.settings_atvos, version()));
                rows.add(info(R.string.settings_android_tv, Build.VERSION.RELEASE));
            }
            case APPEARANCE -> {
                title = s(R.string.settings_appearance);
                String words = s(R.string.settings_appearance_about);
                rows.add(choice(s(R.string.settings_light), host.light(), words, () -> host.setLight(true)));
                rows.add(choice(s(R.string.settings_dark), !host.light(), words, () -> host.setLight(false)));
            }
            case PROFILES -> {
                title = s(R.string.settings_profiles);
                String account = host.account();
                rows.add(action(R.string.settings_account, account != null ? account : s(R.string.settings_none),
                        s(R.string.settings_account_about), host::chooseAccount));
                rows.add(nav(R.string.settings_picture, pictureLabel(host.picture()), s(R.string.settings_picture_about), () -> go(PICTURE)));
            }
            case PICTURE -> {
                title = s(R.string.settings_picture);
                String words = s(R.string.settings_picture_about), chosen = host.picture();
                for (String p : new String[]{Profile.PHOTO, Profile.INITIAL, Profile.NONE}) {
                    rows.add(choice(pictureLabel(p), p.equals(chosen), words, () -> {
                        host.setPicture(p);
                        if (!p.equals(Profile.NONE) && host.account() == null) host.chooseAccount(); // a picture needs an account
                    }));
                }
            }
            case UNINSTALL -> {
                title = s(R.string.settings_uninstall);
                Controls.Setup st = host.setupState();
                boolean restore = !st.disabledHomes.isEmpty() || busy;
                rows.add(new Row(ACTION, s(R.string.settings_restore), null,
                        s(restore ? R.string.settings_restore_about : R.string.settings_restore_not_needed),
                        restore ? this::restore : null, restore, false));
                rows.add(new Row(ACTION, s(R.string.settings_release_power), null,
                        s(st.holdsPower ? R.string.settings_release_power_about : R.string.power_not_held),
                        st.holdsPower ? host::releasePower : null, st.holdsPower, false));
            }
            case SUPPORT -> {
                title = s(R.string.settings_support);
                if (host.canOpen(WEBSITE)) {
                    rows.add(nav(R.string.settings_website, s(R.string.settings_website_address), s(R.string.settings_website_about),
                            () -> open(WEBSITE, R.string.not_available)));
                } else { // no browser: the address only
                    rows.add(action(R.string.settings_website, s(R.string.settings_website_address), s(R.string.settings_website_elsewhere), null));
                }
                rows.add(nav(R.string.settings_store, null, s(R.string.settings_store_about), () -> open(STORE, R.string.not_available)));
                rows.add(nav(R.string.settings_app_info, null, s(R.string.settings_app_info_about), () -> open(APP_INFO, R.string.not_available)));
                Donation d = host.donation();
                String price = d.price();
                String words = switch (d.state()) {
                    case READY -> context.getString(R.string.settings_donate_about, price);
                    case CONNECTING -> s(R.string.settings_donate_connecting);
                    case PENDING -> s(R.string.settings_donate_pending);
                    case THANKS -> s(R.string.settings_donate_thanks);
                    default -> s(R.string.settings_donate_unavailable);
                };
                boolean ready = d.state() == Donation.State.READY;
                rows.add(nav(R.string.settings_donate, ready ? price : null, words,
                        ready || d.state() == Donation.State.THANKS ? d::give : null));
            }
            default -> {
                title = s(R.string.settings_title);
                Controls.Setup st = host.setupState();
                if (!st.homeActive) { // only when another app has the Home button
                    String home = st.currentHome != null ? st.currentHome : s(R.string.settings_home_other);
                    rows.add(nav(R.string.settings_home, home, context.getString(R.string.settings_home_about, home), host::setUpHome));
                }
                rows.add(nav(R.string.settings_general, null, null, () -> go(GENERAL)));
                rows.add(nav(R.string.settings_profiles, null, null, () -> go(PROFILES)));
                rows.add(nav(R.string.settings_uninstall, null, null, () -> go(UNINSTALL)));
                rows.add(nav(R.string.settings_support, null, null, () -> go(SUPPORT)));
            }
        }
        int n = rows.size();
        tops = new float[n];
        fitTitle = new String[n];
        fitValue = new String[n];
        for (int i = 0; i < n; i++) {
            tops[i] = ROW_TOP + i * ROW_PITCH; // a section title uses the space of one row
            fit(i);
        }
        if (focus.length != n) focus = new float[n];
        while (rowRects.size() < n) rowRects.add(new RectF());
        sel = nearest(sel);
        layOut();
        title = TextUtils.ellipsize(title, titleText, W - 200f, TextUtils.TruncateAt.END).toString();
    }

    /** Fits the title and the value of row {@code i} to the width of the row when it is not focused. */
    private void fit(int i) {
        Row row = rows.get(i);
        if (row.kind() == HEADER) {
            fitTitle[i] = TextUtils.ellipsize(row.title(), headerText, ROW_W - 40f, TextUtils.TruncateAt.END).toString();
            return;
        }
        boolean mark = row.kind() == NAV || row.kind() == CHOICE && row.checked();
        float x = ROW_X + 32f, right = ROW_X + ROW_W - (mark ? 74f : 34f), textRight = right;
        if (row.value() != null && !row.value().isEmpty()) {
            // The value gets the width that the title does not use, and not less than 45% of the row.
            float room = Math.max(ROW_W * 0.45f, right - x - rowText.measureText(row.title()) - 40f);
            fitValue[i] = TextUtils.ellipsize(row.value(), valueText, room, TextUtils.TruncateAt.END).toString();
            textRight = right - valueText.measureText(fitValue[i]) - 24f;
        }
        fitTitle[i] = TextUtils.ellipsize(row.title(), rowText, Math.max(60f, textRight - x), TextUtils.TruncateAt.END).toString();
    }

    /** Sets the bounds of each row before the first draw, for screen readers. */
    private void layOut() {
        for (int i = 0; i < rows.size(); i++) {
            float top = tops[i] - scroll;
            rowRects.get(i).set(ROW_X, top, ROW_X + ROW_W, top + (rows.get(i).kind() == HEADER ? ROW_PITCH : ROW_H));
        }
    }

    private Row nav(int title, String value, String about, Runnable action) {
        return new Row(NAV, s(title), value, about, action, true, false);
    }

    private Row action(int title, String value, String about, Runnable action) {
        return new Row(ACTION, s(title), value, about, action, true, false);
    }

    private Row choice(String title, boolean checked, String about, Runnable action) {
        return new Row(CHOICE, title, null, about, action, true, checked);
    }

    private Row info(int title, String value) {
        return new Row(INFO, s(title), value, null, null, true, false);
    }

    private String s(int res) {
        return context.getString(res);
    }

    private String version() {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return ""; // this app is installed
        }
    }

    private String pictureLabel(String picture) {
        return s(switch (picture) {
            case Profile.INITIAL -> R.string.settings_picture_initial;
            case Profile.NONE -> R.string.settings_none;
            default -> R.string.settings_picture_photo;
        });
    }

    /** Opens a page above this page. The focus goes to its selected choice, else to its first row. */
    private void go(int to) {
        under.push(new int[]{page, sel});
        page = to;
        enter(-1);
        if (to == SUPPORT) host.donation().start(this::refresh); // connects to Google Play when the Support page opens
    }

    private void enter(int at) {
        note = null;
        fade = 0f;
        focus = new float[0];
        sel = -1;
        build();
        sel = at >= 0 ? nearest(at) : first();
        if (sel >= 0) focus[sel] = 1f;
        scroll = wantScroll();
        layOut();
    }

    /** Returns the selected choice, else the first focusable row, or -1. */
    private int first() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).kind() == CHOICE && rows.get(i).checked()) return i;
        return nearest(0);
    }

    /** Returns {@code i}, or the nearest focusable row after it (else before it), or -1. */
    private int nearest(int i) {
        if (i < 0) return -1;
        for (int j = Math.min(i, rows.size() - 1); j < rows.size() && j >= 0; j++) if (rows.get(j).focusable()) return j;
        for (int j = Math.min(i, rows.size() - 1); j >= 0; j--) if (rows.get(j).focusable()) return j;
        return -1;
    }

    private void restore() {
        busy = true;
        noteHere(context.getString(R.string.setup_tv_connecting));
        host.restoreHome(progress());
    }

    /** Returns a listener that shows the self-adb progress below the description of the focused row. */
    private HomeView.SelfSetup progress() {
        int row = sel, on = page;
        return new HomeView.SelfSetup() {
            @Override
            public void status(int messageRes) {
                note = context.getString(messageRes);
                noteRow = row;
                notePage = on;
                host.changed();
            }

            @Override
            public void done(boolean ok, int messageRes) {
                busy = false;
                build();
                if (messageRes != 0) {
                    note = context.getString(messageRes);
                    noteRow = row;
                    notePage = on;
                } else if (notePage == on && noteRow == row) {
                    note = null;
                }
                host.changed();
            }
        };
    }

    private void open(int what, int failed) {
        if (!host.open(what)) note(failed);
    }

    private void note(int res) {
        build();
        noteHere(context.getString(res));
        host.changed();
    }

    private void noteHere(String text) {
        note = text;
        noteRow = sel;
        notePage = page;
    }

    // Input

    /**
     * Handles the arrow keys, Back and Menu. Returns false for other keys (for example, volume). {@link #select}
     * handles Select.
     */
    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                int step = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                for (int i = sel + step; sel >= 0 && i >= 0 && i < rows.size(); i += step) {
                    if (!rows.get(i).focusable()) continue;
                    host.sound(step < 0 ? SoundEffectConstants.NAVIGATION_UP : SoundEffectConstants.NAVIGATION_DOWN);
                    sel = i;
                    break;
                }
            }
            case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> { } // one column
            case KeyEvent.KEYCODE_BACK -> {
                if (busy) return true;
                if (under.isEmpty()) {
                    host.close();
                } else {
                    int[] back = under.pop();
                    page = back[0];
                    enter(back[1]);
                }
            }
            case KeyEvent.KEYCODE_MENU -> {
                if (!busy) host.close();
            }
            default -> {
                return false;
            }
        }
        host.changed();
        return true;
    }

    void select() {
        if (busy || sel < 0 || sel >= rows.size()) return;
        Row row = rows.get(sel);
        if (row.action() == null || !row.enabled()) return;
        int was = page;
        host.sound(SoundEffectConstants.CLICK);
        row.action().run();
        if (!busy && page == was) build(); // a new page builds itself
        host.changed();
    }

    /** Moves the focus to row {@code i} for a screen reader. Returns false if the row cannot have the focus. */
    boolean focus(int i) {
        if (busy || i < 0 || i >= rows.size() || !rows.get(i).focusable()) return false;
        sel = i;
        host.changed();
        return true;
    }

    // Animation and draw

    /** Returns the scroll that keeps the focused row on the screen, one row height above the bottom edge. */
    private float wantScroll() {
        return sel < 0 ? 0f : Math.max(0f, tops[sel] + ROW_H + ROW_PITCH - 1000f);
    }

    /**
     * Moves the focus highlights, the scroll and the fade-in of a new page forward by {@code dt}. Returns true while
     * something moves.
     */
    boolean step(float dt) {
        boolean motion = ValueAnimator.areAnimatorsEnabled(); // false when animations are off
        float k = motion ? 1f - (float) Math.exp(-18f * dt) : 1f;
        boolean moving = false;
        for (int i = 0; i < focus.length; i++) {
            float target = i == sel ? 1f : 0f, d = target - focus[i];
            if (Math.abs(d) > 0.002f) {
                focus[i] += d * k;
                moving = true;
            } else {
                focus[i] = target;
            }
        }
        float want = wantScroll();
        if (Math.abs(want - scroll) > 0.5f) {
            scroll += (want - scroll) * k;
            moving = true;
        } else {
            scroll = want;
        }
        if (fade < 1f) {
            fade = motion ? Math.min(1f, fade + dt * 6f) : 1f;
            moving = true;
        }
        return moving;
    }

    /** Draws the screen. {@code height}: the screen height in design units. */
    void draw(Canvas c, float appear, float height) {
        int alpha = Math.round(255 * appear);
        if (alpha <= 2 || rows.isEmpty()) return;
        boolean light = host.light();
        // the colours of tvOS Settings
        int bg = light ? 0xFFB1B0B1 : 0xFF1C1C1C, pill = light ? 0xFFC1C0C1 : 0xFF333333;
        ink = light ? Color.BLACK : Color.WHITE;
        valueInk = light ? 0xFF747475 : 0xFF8E8E93;
        dimInk = light ? 0xFF6B6A6B : 0xFF606060; // page and section titles
        offInk = light ? 0xFF8C8B8C : 0xFF6E6E70; // rows that are disabled
        int words = light ? 0xFF474748 : 0xFF98989D;
        fill.setColor(bg);
        fill.setAlpha(alpha);
        c.drawRect(0f, 0f, W, height, fill);
        int a = Math.round(alpha * (0.3f + 0.7f * fade));

        titleText.setColor(dimInk);
        titleText.setAlpha(a);
        c.drawText(title, W / 2f, TITLE_Y, titleText);

        logo.draw(c, logoRect, light, bg, alpha);
        String text = sel >= 0 && sel < rows.size() ? rows.get(sel).about() : null;
        if (text != null && !text.equals(aboutFor)) {
            about = StaticLayout.Builder.obtain(text, 0, text.length(), aboutText, Math.round(NOTE_W))
                    .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(8f, 1f).setMaxLines(5)
                    .setEllipsize(TextUtils.TruncateAt.END).build();
            aboutFor = text;
        }
        float noteY = NOTE_TOP + 20f;
        if (text != null && about != null) {
            aboutText.setColor(words);
            aboutText.setAlpha(a);
            c.save();
            c.translate(NOTE_CX - NOTE_W / 2f, NOTE_TOP);
            about.draw(c);
            c.restore();
            noteY = NOTE_TOP + about.getHeight() + 52f;
        }
        if (note != null && noteRow == sel && notePage == page) {
            if (!note.equals(noteFitFor)) {
                noteFit = TextUtils.ellipsize(note, noteText, NOTE_W, TextUtils.TruncateAt.END).toString();
                noteFitFor = note;
            }
            noteText.setColor(light ? 0xFF0A5BC4 : 0xFF64D2FF);
            noteText.setAlpha(a);
            c.drawText(noteFit, NOTE_CX, noteY, noteText);
        }

        c.save();
        c.clipRect(ROW_X - 40f, ROW_TOP - 30f, W, height);
        for (int i = 0; i < rows.size(); i++) if (i != sel) drawRow(c, i, a, pill);
        if (sel >= 0) drawRow(c, sel, a, pill); // drawn last, so its shadow is above the other rows
        c.restore();
    }

    private void drawRow(Canvas c, int i, int alpha, int pill) {
        Row row = rows.get(i);
        float top = tops[i] - scroll;
        if (row.kind() == HEADER) {
            rowRects.get(i).set(ROW_X, top, ROW_X + ROW_W, top + ROW_PITCH);
            headerText.setColor(dimInk);
            headerText.setAlpha(alpha);
            c.drawText(fitTitle[i], HEADER_X, top + HEADER_BASELINE, headerText);
            return;
        }
        float f = row.kind() == INFO || i >= focus.length ? 0f : focus[i]; // an INFO row has no focus highlight
        float cx = ROW_X + ROW_W / 2f, cy = top + ROW_H / 2f;
        float w = ROW_W * (1f + (ROW_GROW_W - 1f) * f), h = ROW_H * (1f + (ROW_GROW_H - 1f) * f);
        r.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        rowRects.get(i).set(r);
        if (r.bottom < ROW_TOP - 30f || r.top > 1080f) return;
        if (row.kind() != INFO) { // an INFO row has no pill
            if (f > 0.01f && shadowMask != null) {
                float padX = SHADOW_PAD * w / (ROW_W * ROW_GROW_W), padY = SHADOW_PAD * h / (ROW_H * ROW_GROW_H);
                shadowRect.set(r.left - padX, r.top - padY + SHADOW_DY, r.right + padX, r.bottom + padY + SHADOW_DY);
                shadow.setAlpha(Math.round(alpha * 0.28f * f));
                c.drawBitmap(shadowMask, null, shadowRect, shadow);
            }
            fill.setColor(blend(pill, Color.WHITE, f));
            fill.setAlpha(alpha);
            c.drawRoundRect(r, h / 2f, h / 2f, fill);
        }
        int text = row.enabled() ? blend(ink, Color.BLACK, f) : blend(offInk, 0xFFA1A1A6, f);
        int soft = blend(valueInk, 0xFF8A8A8E, f);
        boolean mark = row.kind() == NAV || row.kind() == CHOICE && row.checked(); // a chevron or a tick
        float right = r.right - (mark ? 74f : 34f);
        if (fitValue[i] != null) {
            valueText.setColor(soft);
            valueText.setAlpha(alpha);
            c.drawText(fitValue[i], right, cy + 13f, valueText);
        }
        rowText.setColor(text);
        rowText.setAlpha(alpha);
        c.drawText(fitTitle[i], r.left + 32f, cy + 13f, rowText);
        if (mark) {
            stroke.setColor(row.kind() == CHOICE ? soft : blend(valueInk, 0xFF5A5A5E, f));
            stroke.setStrokeWidth(row.kind() == CHOICE ? 3f : 3.5f);
            stroke.setAlpha(alpha);
            c.save();
            c.translate(r.right - (row.kind() == CHOICE ? 44f : 42f), cy);
            c.drawPath(row.kind() == CHOICE ? tick : chevron, stroke);
            c.restore();
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // Screen readers

    /** Adds the rows (not the section titles) to {@code out}, each with its value and its description. */
    void items(List<A11y.Item> out, int base) {
        for (int i = 0; i < rows.size() && i < rowRects.size(); i++) {
            Row row = rows.get(i);
            if (row.kind() == HEADER) continue;
            String state = row.kind() == CHOICE ? (row.checked() ? s(R.string.a11y_selected) : null) : row.value();
            String hint = row.about();
            if (i == sel && note != null && noteRow == sel && notePage == page) hint = hint == null ? note : hint + " " + note;
            boolean clickable = row.kind() != INFO && row.action() != null && row.enabled(); // INFO rows are not buttons
            out.add(new A11y.Item(base + i, row.title(), state, hint, new RectF(rowRects.get(i)), i == sel, clickable));
        }
    }

    /** Returns the shadow mask: the pill of a fully focused row, blurred, in design units. */
    private static Bitmap makeShadowMask() {
        float w = ROW_W * ROW_GROW_W, h = ROW_H * ROW_GROW_H;
        int bw = Math.round(w + 2 * SHADOW_PAD), bh = Math.round(h + 2 * SHADOW_PAD);
        Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setMaskFilter(new BlurMaskFilter(SHADOW_BLUR, BlurMaskFilter.Blur.NORMAL));
        new Canvas(b).drawRoundRect(new RectF(SHADOW_PAD, SHADOW_PAD, bw - SHADOW_PAD, bh - SHADOW_PAD), h / 2f, h / 2f, p);
        return b;
    }
}
