package app.atvos.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.text.LineBreaker;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Setup: one glass card that shows the steps that atvOS needs, one at a time. Each step has a picture of the Settings
 * screen to find and a button that opens it. Steps that are complete are skipped. When the user comes back from
 * Settings, the next step shows.
 * <pre>
 *   Welcome → Developer options → USB debugging → Allow → Profile picture → Done
 * </pre>
 * From Settings, it runs only the steps that the TV needs ({@link #openSteps}).
 */
final class SetupFlow {

    /** The data and actions that the flow gets from the home screen. UI thread. */
    interface Host {
        Controls.Setup state();
        boolean hasAccount();
        void openAbout();
        void openDeveloperOptions();
        void setUpHome(HomeView.SelfSetup progress);
        void requestDefaultHome();
        void chooseAccount();
        void close();
        void sound(int effect);
        /** Draws the card again and updates screen readers. */
        void changed();
    }

    // The steps. The numbers give their order.
    private static final int WELCOME = 0, DEVELOPER = 1, DEBUGGING = 2, ALLOW = 3, PICK_HOME = 4, ACCOUNT = 5,
            DONE = 6;
    private static final int ART_NONE = -1, ART_LOGO = 10, ART_AVATAR = 11, ART_CHECK = 12;

    private static final float W = HomeView.W, CARD_W = 1080f, PAD = 56f, CARD_R = 56f;
    private static final float ART_W = 720f, ART_H = 405f, ICON_H = 220f, BUTTON_H = 72f;
    private static final int INK = 0xFF141416, GREEN = 0xFF30D158, TEAL = 0xFF64D2FF, RED = 0xFFFF6961;

    private record Button(String label, Runnable action) {
    }

    private final Context context;
    private final Host host;
    private final SettingsArt art;
    private final Logo logo;
    private final TextPaint titleText = new TextPaint(Paint.ANTI_ALIAS_FLAG), bodyText = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            buttonText = new TextPaint(Paint.ANTI_ALIAS_FLAG), noteText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap avatar; // the default profile picture, made at the first draw
    private final Path check = new Path();
    private final RectF r = new RectF(), artBox = new RectF();
    /** The bounds of the card and its buttons at the last draw, for screen readers. */
    final RectF card = new RectF();
    private final RectF[] buttonRects = new RectF[6];

    private Controls.Setup setup = new Controls.Setup();
    private int step = WELCOME, origin = WELCOME, artKind = ART_NONE;
    private final List<Button> buttons = new ArrayList<>();
    private final List<Integer> dots = new ArrayList<>();
    private final List<Integer> journey = new ArrayList<>(); // the steps of this run, one dot each
    private boolean busy;      // the on-TV setup runs
    private int sel, dot = -1;
    private final float[] focus = new float[6]; // the focus highlight of each button
    private float fade = 1f;
    // Laid out once for each change: the text is wrapped and shortened to fit.
    private String title = "", note, summary = "";
    private int noteColor;
    private StaticLayout body;
    private float cardH;
    private final float[] buttonX = new float[6], buttonW = new float[6]; // the horizontal position and width of each button

    SetupFlow(Context context, Host host) {
        this.context = context;
        this.host = host;
        art = new SettingsArt(context);
        logo = new Logo(context);
        for (int i = 0; i < buttonRects.length; i++) buttonRects[i] = new RectF();
        titleText.setTypeface(Typeface.create(Typeface.DEFAULT, 700, false));
        titleText.setTextSize(40f);
        titleText.setTextAlign(Paint.Align.CENTER);
        bodyText.setTypeface(Typeface.DEFAULT);
        bodyText.setTextSize(25f);
        buttonText.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        buttonText.setTextSize(27f);
        buttonText.setTextAlign(Paint.Align.CENTER);
        noteText.setTypeface(Typeface.create(Typeface.DEFAULT, 500, false));
        noteText.setTextSize(23f);
        noteText.setTextAlign(Paint.Align.CENTER);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        check.moveTo(-26f, 2f);
        check.lineTo(-8f, 20f);
        check.lineTo(28f, -18f);
    }

    /** Opens the first-launch setup at Welcome. */
    void open() {
        origin = WELCOME;
        busy = false;
        journey.clear();
        setup = host.state();
        go(origin);
    }

    /** Opens only the steps that the TV needs to give the Home button to atvOS. Back on the first step closes it. */
    void openSteps() {
        busy = false;
        setup = host.state();
        startJourney();
        origin = DONE;
        for (int s : plan()) {
            if (s < ACCOUNT) {
                origin = s;
                break;
            }
        }
        go(origin);
    }

    /** Reads the TV state again and goes to the next step if this step is complete. */
    void refresh() {
        setup = host.state();
        if (busy) return;
        boolean done = switch (step) {
            case DEVELOPER -> setup.developerOn || setup.homeActive;
            case DEBUGGING -> setup.adbOn || setup.homeActive;
            case ALLOW, PICK_HOME -> setup.homeActive;
            case ACCOUNT -> host.hasAccount();
            default -> false;
        };
        if (done) next(); else layOut();
    }

    /** Returns true for a setup step (not Welcome or Done). */
    private boolean inSteps() {
        return step > WELCOME && step < DONE;
    }

    /**
     * Returns true if the Home key does not close it: the first-launch setup, and each step from Settings except Done.
     */
    boolean staysOnHome() {
        return origin == WELCOME || inSteps();
    }

    /** Returns the steps that remain, in order, from the current state of the TV. */
    private List<Integer> plan() {
        List<Integer> p = new ArrayList<>();
        Controls.Setup s = setup;
        if (!s.homeActive) {
            if (!s.lockedHomes.isEmpty()) {
                // The home app of the TV keeps the Home button until adb disables it.
                if (!s.developerOn) p.add(DEVELOPER);
                if (!s.adbOn) p.add(DEBUGGING);
                p.add(ALLOW);
            } else if (s.canPickHome) {
                p.add(PICK_HOME);
            }
        }
        if (!host.hasAccount()) p.add(ACCOUNT);
        p.add(DONE);
        return p;
    }

    private void next() {
        if (step == origin) startJourney();
        for (int s : plan()) {
            if (s > step) {
                go(s);
                return;
            }
        }
        go(DONE);
    }

    private void back() {
        if (busy) return;
        if (step == origin || step == DONE && origin != WELCOME) {
            host.close();
            return;
        }
        int prev = origin;
        for (int s : plan()) if (s < step && s > prev) prev = s;
        go(prev);
    }

    private void go(int to) {
        step = to;
        sel = 0;
        Arrays.fill(focus, 0f);
        focus[0] = 1f;
        note = null;
        fade = 0f;
        layOut();
    }

    // Input

    /**
     * Handles the arrow keys and Back. Returns false for other keys (for example, volume). {@link #select} handles
     * Select.
     */
    boolean key(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> move(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { } // one row of buttons
            case KeyEvent.KEYCODE_BACK -> back();
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

    private void move(int d) {
        int to = Math.max(0, Math.min(buttons.size() - 1, sel + d));
        if (to == sel) return;
        host.sound(d < 0 ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
        sel = to;
    }

    void select() {
        if (busy || sel >= buttons.size()) return;
        host.sound(SoundEffectConstants.CLICK);
        buttons.get(sel).action().run();
        host.changed();
    }

    /** Moves the selection to button {@code i} for a screen reader. Returns false if there is no such button. */
    boolean focus(int i) {
        if (busy || i < 0 || i >= buttons.size()) return false;
        sel = i;
        host.changed();
        return true;
    }

    // Steps

    /** Disables the home app of the TV over self-adb. The user accepts the prompt of the TV. */
    private void runSetUp() {
        busy = true;
        showNote(R.string.setup_tv_connecting, TEAL);
        host.setUpHome(new HomeView.SelfSetup() {
            @Override
            public void status(int messageRes) {
                showNote(messageRes, TEAL);
            }

            @Override
            public void done(boolean ok, int messageRes) {
                busy = false;
                setup = host.state();
                if (ok) {
                    next();
                } else {
                    showNote(messageRes, RED);
                }
            }
        });
    }

    private void showNote(int res, int color) {
        note = context.getString(res);
        noteColor = color;
        layOut();
    }

    /** Makes the text, the picture and the buttons of the step, and measures the card. */
    private void layOut() {
        buttons.clear();
        int bodyRes;
        switch (step) {
            case WELCOME -> {
                title = context.getString(R.string.setup_welcome);
                bodyRes = R.string.setup_welcome_body;
                artKind = ART_LOGO;
                add(R.string.setup_get_started, this::next);
                add(R.string.setup_not_now, host::close);
            }
            case DEVELOPER -> {
                title = context.getString(R.string.setup_developer_title);
                bodyRes = R.string.setup_developer_body;
                artKind = SettingsArt.ABOUT;
                add(R.string.setup_open_about, host::openAbout);
                add(R.string.setup_back, this::back);
            }
            case DEBUGGING -> {
                title = context.getString(R.string.setup_debugging_title);
                bodyRes = R.string.setup_debugging_body;
                artKind = SettingsArt.DEVELOPER;
                add(R.string.setup_open_developer, host::openDeveloperOptions);
                add(R.string.setup_back, this::back);
            }
            case ALLOW -> {
                title = context.getString(R.string.setup_allow_title);
                bodyRes = R.string.setup_allow_body;
                artKind = SettingsArt.PROMPT;
                if (!busy) {
                    add(note != null ? R.string.setup_try_again : R.string.setup_set_up, this::runSetUp);
                    add(R.string.setup_back, this::back);
                }
            }
            case PICK_HOME -> {
                title = context.getString(R.string.setup_pick_title);
                bodyRes = R.string.setup_pick_body;
                artKind = ART_LOGO;
                add(R.string.setup_pick_button, host::requestDefaultHome);
                add(R.string.setup_back, this::back);
            }
            case ACCOUNT -> {
                title = context.getString(R.string.setup_account_title);
                bodyRes = R.string.setup_account_body;
                artKind = ART_AVATAR;
                add(R.string.setup_choose_account, host::chooseAccount);
                add(R.string.setup_not_now, this::next);
            }
            default -> { // Done
                title = context.getString(R.string.setup_done_title);
                bodyRes = setup.homeActive ? R.string.setup_done_body : R.string.setup_done_body_not_home;
                artKind = ART_CHECK;
                add(R.string.setup_go_home, host::close);
            }
        }
        sel = Math.min(sel, Math.max(0, buttons.size() - 1));
        float inner = CARD_W - 2 * PAD;
        title = TextUtils.ellipsize(title, titleText, inner, TextUtils.TruncateAt.END).toString();
        String text = context.getString(bodyRes);
        body = StaticLayout.Builder.obtain(text, 0, text.length(), bodyText, Math.round(inner))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(6f, 1f)
                .setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED).build(); // balanced lines
        if (note != null) note = TextUtils.ellipsize(note, noteText, inner, TextUtils.TruncateAt.END).toString();

        // The dots: one for each step of this run, with this step lit. A run of one step has no dots.
        dots.clear();
        dot = journey.indexOf(step);
        if (inSteps() && dot >= 0 && journey.size() > 1) dots.addAll(journey);

        // The buttons: a centred pair, each as wide as its label.
        float total = 0f;
        for (int i = 0; i < buttons.size(); i++) {
            buttonW[i] = Math.max(240f, Math.min(inner / 2f - 10f, buttonText.measureText(buttons.get(i).label()) + 96f));
            total += buttonW[i] + (i > 0 ? 20f : 0f);
        }
        float x = (W - total) / 2f;
        for (int i = 0; i < buttons.size(); i++) {
            buttonX[i] = x;
            x += buttonW[i] + 20f;
        }
        float artH = artKind >= 0 && artKind < ART_LOGO ? ART_H : artKind == ART_NONE ? 0f : ICON_H;
        cardH = PAD + (dots.isEmpty() ? 0f : 40f) + (artH > 0f ? artH + 40f : 0f) + 50f + 16f + body.getHeight()
                + (note != null ? 46f : 0f) + 40f + (buttons.isEmpty() ? 0f : BUTTON_H) + PAD;
        StringBuilder all = new StringBuilder(title).append(". ");
        all.append(text);
        if (note != null) all.append(' ').append(note);
        summary = all.toString();
        host.changed();
    }

    private void add(int label, Runnable action) {
        if (buttons.size() < buttonRects.length) buttons.add(new Button(context.getString(label), action));
    }

    /** Records the steps at the start of the run. Each step keeps its dot when it is complete. */
    private void startJourney() {
        journey.clear();
        for (int s : plan()) if (s != DONE) journey.add(s);
    }

    // Animation and draw

    /** Moves the selection and the fade-in of the step forward by {@code dt}. Returns true while something moves. */
    boolean step(float dt) {
        boolean motion = ValueAnimator.areAnimatorsEnabled(); // false when animations are off
        float k = motion ? 1f - (float) Math.exp(-20f * dt) : 1f;
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
        if (fade < 1f) {
            fade = motion ? Math.min(1f, fade + dt * 5f) : 1f;
            moving = true;
        }
        return moving;
    }

    /** Draws the card over the dimmed home screen. {@code height}: the screen height in design units. */
    void draw(Canvas c, ControlCenter.Glass glass, float appear, float height) {
        int alpha = Math.round(255 * appear);
        if (alpha <= 2 || body == null) return;
        c.drawColor(Color.argb(Math.round(150 * appear), 0, 0, 0));
        float left = (W - CARD_W) / 2f, top = Math.max(24f, (height - cardH) / 2f);
        c.save();
        float k = 0.94f + 0.06f * appear;
        c.scale(k, k, W / 2f, height / 2f);
        card.set(left, top, left + CARD_W, top + cardH);
        glass.draw(c, card, CARD_R, 0x99141418, alpha);

        int a = Math.round(alpha * (0.35f + 0.65f * fade)); // the content of a new step fades in
        float y = top + PAD + (1f - fade) * 10f;
        if (!dots.isEmpty()) {
            float w = 0f;
            for (int i = 0; i < dots.size(); i++) w += (i == dot ? 36f : 12f) + (i > 0 ? 10f : 0f);
            float x = W / 2f - w / 2f;
            for (int i = 0; i < dots.size(); i++) {
                float dw = i == dot ? 36f : 12f;
                r.set(x, y, x + dw, y + 12f);
                fill.setColor(Color.WHITE);
                fill.setAlpha(Math.round(a * (i == dot ? 0.95f : 0.3f)));
                c.drawRoundRect(r, 6f, 6f, fill);
                x += dw + 10f;
            }
            y += 40f;
        }
        y = drawArt(c, y, a);
        titleText.setColor(Color.WHITE);
        titleText.setAlpha(a);
        c.drawText(title, W / 2f, y + 40f, titleText);
        y += 50f + 16f;
        bodyText.setColor(Color.argb(Math.round(a * 0.74f), 255, 255, 255));
        c.save();
        c.translate(left + PAD, y);
        body.draw(c);
        c.restore();
        y += body.getHeight();
        if (note != null) {
            y += 46f;
            noteText.setColor(noteColor);
            noteText.setAlpha(a);
            c.drawText(note, W / 2f, y, noteText);
        }
        y += 40f;
        drawButtons(c, y, a);
        c.restore();
    }

    /** Draws the picture of the step: a Settings screen of the TV, or a glyph. Returns the y below it. */
    private float drawArt(Canvas c, float y, int alpha) {
        if (artKind == ART_NONE) return y;
        if (artKind < ART_LOGO) {
            artBox.set(W / 2f - ART_W / 2f, y, W / 2f + ART_W / 2f, y + ART_H);
            fill.setColor(0x59000000);
            r.set(artBox.left - 2f, artBox.top + 6f, artBox.right + 2f, artBox.bottom + 12f); // the shadow below the picture
            fill.setAlpha(Math.round(alpha * 0.35f));
            c.drawRoundRect(r, 22f, 22f, fill);
            art.draw(c, artKind, artBox, 20f, alpha);
            return y + ART_H + 40f;
        }
        float cx = W / 2f, cy = y + ICON_H / 2f;
        if (artKind == ART_LOGO) {
            r.set(cx - ICON_H / 2f, y, cx + ICON_H / 2f, y + ICON_H);
            logo.draw(c, r, false, 0xFF1C1C1C, alpha);
        } else if (artKind == ART_AVATAR) {
            drawAvatar(c, cx, cy, 90f, alpha);
        } else {
            fill.setColor(GREEN);
            fill.setAlpha(alpha);
            c.drawCircle(cx, cy, 90f, fill);
            stroke.setColor(Color.WHITE);
            stroke.setAlpha(alpha);
            stroke.setStrokeWidth(14f);
            c.save();
            c.translate(cx, cy);
            c.drawPath(check, stroke);
            c.restore();
        }
        return y + ICON_H + 40f;
    }

    /** Draws the default profile picture: a grey disc with a person. */
    private void drawAvatar(Canvas c, float cx, float cy, float radius, int alpha) {
        if (avatar == null) avatar = HomeView.makeAvatar(Math.round(radius * 2f));
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        image.setAlpha(alpha);
        c.drawBitmap(avatar, null, r, image);
    }

    /** Draws the buttons: the selected button white with dark text and lifted, the others as faint glass. */
    private void drawButtons(Canvas c, float y, int alpha) {
        for (int i = 0; i < buttons.size(); i++) {
            float s = focus[i], grow = 1f + 0.04f * s;
            float cx = buttonX[i] + buttonW[i] / 2f, cy = y + BUTTON_H / 2f;
            float w = buttonW[i] * grow, h = BUTTON_H * grow;
            RectF b = buttonRects[i];
            b.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
            fill.setColor(Color.WHITE);
            fill.setAlpha(Math.round(alpha * (0.14f + 0.84f * s)));
            c.drawRoundRect(b, h / 2f, h / 2f, fill);
            buttonText.setColor(blend(Color.WHITE, INK, s));
            buttonText.setAlpha(alpha);
            c.drawText(buttons.get(i).label(), cx, cy + 10f * grow, buttonText);
        }
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    // Screen readers

    /** Adds the buttons to {@code out}. The first button also has the text of the card. */
    void items(List<A11y.Item> out, int base) {
        for (int i = 0; i < buttons.size(); i++) {
            out.add(new A11y.Item(base + i, buttons.get(i).label(), null, i == 0 ? summary : null,
                    new RectF(buttonRects[i]), i == sel));
        }
        if (buttons.isEmpty()) { // while setup runs: only the text
            out.add(new A11y.Item(base, title, note, summary, new RectF(card), true));
        }
    }
}
