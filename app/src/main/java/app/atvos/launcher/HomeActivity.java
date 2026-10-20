package app.atvos.launcher;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.format.DateFormat;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** The home activity: owns the view, loads apps and backdrops off the UI thread, launches apps. */
public final class HomeActivity extends Activity implements HomeView.Host {

    /** The one SharedPreferences file every part of the launcher keeps its settings in. */
    static final String PREFS = "home";
    private static final String TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final String KEY_ASKED = "asked_tv_listings", KEY_SET_UP = "set_up";
    /** Settings > Appearance: the rows of programs and Settings light (else dark). Read by {@link Backdrops} too. */
    static final String KEY_LIGHT = "appearance_light";
    /** Settings > Time Format: 24-hour (true) or 12-hour; unset follows the TV. */
    private static final String KEY_24_HOUR = "time_24_hour";
    private static final String WEBSITE = "https://atvos.tv";
    /**
     * The system toast's colours on Android TV and Google TV, measured: a dark surface with light
     * text up to Android 15 (darker on 12 and 13), a light one with dark text from Android 16.
     */
    private static final int TOAST_FILL = Build.VERSION.SDK_INT >= 36 ? 0xFFEEEDF4 : Build.VERSION.SDK_INT >= 34 ? 0xFF2F3036 : 0xFF191C1E;
    private static final int TOAST_INK = Build.VERSION.SDK_INT >= 36 ? 0xFF1B1B21 : 0xFFE4E2E6;
    /** Android TV's stand-ins for apps a TV doesn't have (a browser): they only say there's no app. */
    private static final String NO_APP_STUBS = "com.android.tv.frameworkpackagestubs";
    private static final int REQUEST_LISTINGS = 8, REQUEST_HOME = 9;
    /** After the TV's "Allow debugging" prompt closes, how long adb gets to let us in before it counts as declined. */
    private static final long APPROVE_GRACE_MS = 5000;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor(); // recommendation prefetch
    private final AtomicBoolean prefetchQueued = new AtomicBoolean();
    private final Set<String> pagesQueued = ConcurrentHashMap.newKeySet(); // apps whose other shelf pages are fetched
    private Apps apps;
    private Backdrops backdrops;
    private Controls controls;
    private Profile profile;
    private Donation donation;
    private HomeView view;
    private boolean homeInstance;      // started by the Home button (else opened like an app)
    private EditText input;            // takes the TV keyboard's typing for the view (a folder's name)
    private HomeView.TextEdit textEdit; // ...while it's open
    private Toast hiddenHint;          // the last "Long press…" toast, so a new one replaces it

    // UI thread
    private List<Apps.Entry> current = Collections.emptyList();
    private Apps.Entry focused;
    private boolean focusInDock = true, resumed;
    private boolean appsDirty = true; // installed apps changed (or not loaded yet): re-query them on refresh
    private SelfAdb selfAdb;          // the on-TV setup in progress, if any
    private boolean adbApproving;     // ...waiting on the TV's "Allow debugging" prompt
    private final Runnable checkApproval = () -> {
        if (adbApproving && selfAdb != null) selfAdb.declined();
    };
    private volatile String wantedBackdrop = "";

    /** Installs, updates and removals; between them, resuming Home skips the PackageManager queries. */
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (resumed) refresh();
            view.refreshSetup(); // turning off the TV's own home (adb) shows on the sheet at once
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        homeInstance = getIntent().hasCategory(Intent.CATEGORY_HOME);
        if (handOffToHome()) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        apps = new Apps(this, dm.widthPixels);
        backdrops = new Backdrops(this, dm.widthPixels, dm.heightPixels);
        controls = new Controls(this);
        profile = new Profile(this, io, Math.round(40f * dm.widthPixels / HomeView.W));
        donation = new Donation(this); // talks to Google Play only while Settings is open
        view = new HomeView(this, this);
        view.setBackdrop(backdrops.cached(Backdrops.DEFAULT));
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        root.addView(input = textInput(), new FrameLayout.LayoutParams(1, 1));
        setContentView(root);
        view.requestFocus();
        profile.load(view::setAvatar);

        IntentFilter changes = new IntentFilter();
        changes.addAction(Intent.ACTION_PACKAGE_ADDED);
        changes.addAction(Intent.ACTION_PACKAGE_REMOVED);
        changes.addAction(Intent.ACTION_PACKAGE_CHANGED);
        changes.addAction(Intent.ACTION_PACKAGE_REPLACED);
        changes.addDataScheme("package");
        registerReceiver(packages, changes); // protected system broadcasts only, so no export flag needed

        // First run: the setup steps, then (as it closes) the recommendations prompt.
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SET_UP, false)) askForListings();
        else view.openSetup();
    }

    /** Asks once for app recommendations; without them the top shelf shows apps' own art. */
    private void askForListings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED && !prefs.getBoolean(KEY_ASKED, false)) {
            prefs.edit().putBoolean(KEY_ASKED, true).apply();
            requestPermissions(new String[]{TV_LISTINGS}, REQUEST_LISTINGS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQUEST_LISTINGS) refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
        profile.refresh(view::setAvatar); // at most once a day
        view.setClockRunning(true);
        view.refreshSetup(); // back from Android's home picker or Settings
        // Back from the TV's "Allow debugging" prompt. adb answers a declined prompt with silence, so
        // if it hasn't let us in shortly after, stop waiting.
        if (adbApproving) view.postDelayed(checkApproval, APPROVE_GRACE_MS);
    }

    @Override
    protected void onPause() {
        finishTextEdit();
        view.removeCallbacks(checkApproval); // posted again on the next resume
        resumed = false;
        view.setClockRunning(false);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (view == null) return; // handed off in onCreate: on its way out
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeInstance = true; // Android sent Home here: this is the Home copy now, whatever opened it
            view.goHome();
            // Setup finished in a copy opened like an app hands off to this one: ask here instead.
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SET_UP, false)) askForListings();
            return;
        }
        handOffToHome();
    }

    /**
     * This copy was opened like any app (Play Store's Open, an app list, adb) and atvOS is the home
     * screen: Android keeps such a copy apart from the Home one, with caches of its own. Go to the
     * Home one instead, and close this. False for the Home copy (whatever it's sent later), or
     * while atvOS isn't the home screen.
     */
    private boolean handOffToHome() {
        if (homeInstance || !isDefaultHome()) return false;
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            return false;
        }
        finish();
        return true;
    }

    /** Whether the Home button opens atvOS (from where it resolves, not the role: see {@link Controls#setup}). */
    @SuppressWarnings("deprecation") // the int-flag overload works on every version from Android 12
    private boolean isDefaultHome() {
        android.content.pm.ResolveInfo home = getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && getPackageName().equals(home.activityInfo.packageName);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Profile.REQUEST_ACCOUNT) {
            profile.onActivityResult(resultCode, data, view::setAvatar);
            view.refreshSetup(); // the profile picture step moves on
        }
        if (requestCode == REQUEST_HOME) view.refreshSetup();
    }

    @Override
    protected void onDestroy() {
        if (view == null) { // handed off to Home before anything was set up
            super.onDestroy();
            return;
        }
        view.removeCallbacks(checkApproval);
        checkApproval.run(); // still waiting on the TV's prompt: stop (nothing has run yet)
        unregisterReceiver(packages);
        io.shutdownNow();
        net.shutdownNow();
        donation.end();
        super.onDestroy();
    }

    /**
     * Re-reads images and recommendations, and the installed apps only when they changed (the
     * package receiver says so): resuming Home is then just a provider query and a directory listing.
     */
    private void refresh() {
        List<Apps.Entry> previous = current;
        boolean reload = appsDirty || previous.isEmpty();
        appsDirty = false;
        pagesQueued.clear(); // apps may have new pages since: fetch again as they're focused
        io.execute(() -> {
            backdrops.scan(this);
            Apps.Layout layout = reload ? apps.load(previous) : null;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (layout != null) {
                    // Mid-edit, or arranged since this load read the saved layout: apply on the next refresh instead.
                    if (!apps.isLatest(layout) || !view.setApps(layout.visible(), layout.hidden())) {
                        appsDirty = true;
                    } else {
                        current = all(layout.visible(), layout.hidden());
                        apps.saveLoaded(layout);
                    }
                }
                updateBackdrop();
            });
            if (prefetchQueued.compareAndSet(false, true)) {
                net.execute(() -> {
                    prefetchQueued.set(false);
                    backdrops.prefetch(() -> runOnUiThread(this::shelfReady));
                });
            }
        });
    }

    /** A recommendation image just finished downloading: show it if its app is focused. */
    private void shelfReady() {
        if (!isDestroyed()) updateBackdrop();
    }

    private void updateBackdrop() {
        // Below the dock the backdrop stays on what the dock last showed (blurred and dimmed, as on
        // tvOS): no image switch, so no costly crossfade on top of the blur transition.
        if (!focusInDock && !wantedBackdrop.isEmpty()) return;
        // A folder shows its first app's backdrop, as on tvOS.
        Apps.Entry shown = focused != null && focused.folder != null
                ? (focused.folder.apps.isEmpty() ? null : focused.folder.apps.get(0)) : focused;
        String key = shown != null ? backdrops.backdropKey(shown.pkg) : Backdrops.DEFAULT;
        Recommendations.Item program = shown != null ? backdrops.recommendation(shown.pkg) : null;
        if (key.equals(wantedBackdrop)) return;
        wantedBackdrop = key;
        Backdrops.Backdrop cached = backdrops.cached(key);
        if (cached != null) {
            view.setBackdrop(cached);
            return;
        }
        io.execute(() -> {
            if (!key.equals(wantedBackdrop)) return; // focus already moved on
            Backdrops.Backdrop loaded = backdrops.load(key, program);
            runOnUiThread(() -> {
                if (loaded != null) backdrops.put(key, loaded);
                if (key.equals(wantedBackdrop)) view.setBackdrop(loaded != null ? loaded : backdrops.cached(Backdrops.DEFAULT));
            });
        });
    }

    // ---- HomeView.Host -----------------------------------------------------------------------

    @Override
    public void onFocusChanged(Apps.Entry entry, boolean inDock) {
        focused = entry;
        focusInDock = inDock;
        updateBackdrop();
        // An app with a top shelf in the dock: fetch its other pages now, so Up can page through them.
        if (inDock && entry != null && entry.folder == null && backdrops.recommendation(entry.pkg) != null
                && pagesQueued.add(entry.pkg)) {
            String pkg = entry.pkg;
            net.execute(() -> backdrops.prefetchPages(pkg, () -> runOnUiThread(() -> {
                if (isDestroyed()) return;
                view.shelfPagesChanged();
                updateBackdrop(); // a row shows the new card
            })));
        }
    }

    @Override
    public List<Recommendations.Item> shelfPages(Apps.Entry entry) {
        return backdrops.pages(entry.pkg);
    }

    @Override
    public void showShelfPage(Apps.Entry entry, Recommendations.Item page) {
        backdrops.choose(entry.pkg, page);
        updateBackdrop();
    }

    @Override
    public int shelfSources(Apps.Entry entry) {
        return backdrops.sources(entry.pkg);
    }

    @Override
    public boolean shelfSourceOn(Apps.Entry entry, int source) {
        return backdrops.sourceOn(entry.pkg, source);
    }

    @Override
    public void setShelfSource(Apps.Entry entry, int source, boolean on) {
        backdrops.setSource(entry.pkg, source, on);
        refresh(); // re-reads recommendations without it, and swaps the backdrop
    }

    @Override
    public void launch(Apps.Entry entry, Rect from) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(entry.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        start(intent, zoomFrom(from), entry.label);
    }

    /**
     * Opens the program shown in the top shelf. The intent comes from another app's data, so it's
     * treated as untrusted: it may only open the app that published it, with no selector and no
     * URI permission grants.
     */
    @Override
    public void launchProgram(Recommendations.Item program, Rect from) {
        if (getPackageName().equals(program.pkg())) return; // never aim another app's intent at ourselves
        Intent intent;
        try {
            intent = Intent.parseUri(program.intentUri(), Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException e) { // malformed extras can throw too
            Toast.makeText(this, getString(R.string.cannot_open, program.title()), Toast.LENGTH_SHORT).show();
            return;
        }
        intent.setSelector(null);
        intent.setClipData(null);
        intent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        ComponentName target = intent.getComponent();
        if (target != null && !target.getPackageName().equals(program.pkg())) intent.setComponent(null);
        intent.setPackage(program.pkg());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(intent, zoomFrom(from), program.title());
    }

    @Override
    public void showInfo(Apps.Entry entry) {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", entry.pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, entry.label);
    }

    @Override
    public void saveLayout(List<Apps.Entry> visible, List<Apps.Entry> hidden) {
        current = all(visible, hidden);
        apps.saveLayout(visible, hidden);
    }

    /** Every tile and app, folders' apps too: what the next load can reuse. A copy, taken on the UI thread. */
    private static List<Apps.Entry> all(List<Apps.Entry> visible, List<Apps.Entry> hidden) {
        List<Apps.Entry> all = new ArrayList<>(visible);
        for (Apps.Entry e : visible) if (e.folder != null) all.addAll(e.folder.apps);
        all.addAll(hidden);
        return all;
    }

    @Override
    public Controls.State controlState() {
        return controls.state();
    }

    @Override
    public boolean control(int id) {
        return switch (id) {
            case ControlCenter.PROFILE -> {
                profile.choose();
                yield false;
            }
            case ControlCenter.SETTINGS -> {
                controls.openSettings(Settings.ACTION_SETTINGS);
                yield false;
            }
            case ControlCenter.POWER -> {
                controls.powerOff();
                yield false;
            }
            case ControlCenter.WIFI -> {
                controls.openSettings(Settings.ACTION_WIFI_SETTINGS);
                yield false;
            }
            case ControlCenter.DND -> {
                controls.toggleDnd();
                yield true;
            }
            case ControlCenter.BLUETOOTH -> {
                controls.openSettings(Controls.ACCESSORIES);
                yield false;
            }
            case ControlCenter.SLEEP -> {
                controls.cycleSleepTimer();
                yield true;
            }
            case ControlCenter.ACCESSIBILITY -> {
                controls.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                yield false;
            }
            case ControlCenter.SYSTEM -> {
                controls.openSettings(Controls.SYSTEM);
                yield false;
            }
            case ControlCenter.LAUNCHER_SETTINGS -> {
                view.openSettings();
                yield false;
            }
            case ControlCenter.INPUTS, ControlCenter.SEARCH -> {
                if (!controls.open(id == ControlCenter.INPUTS ? Controls.INPUTS : Intent.ACTION_ASSIST)) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                yield false;
            }
            default -> true;
        };
    }

    @Override
    public void releasePower() {
        controls.releasePower();
    }

    @Override
    public Controls.Setup setupState() {
        return controls.setup();
    }

    @Override
    public void requestDefaultHome() {
        if (!controls.requestDefaultHome(REQUEST_HOME)) {
            Toast.makeText(this, R.string.setup_home_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void selfSetupHome(HomeView.SelfSetup progress) {
        runSelfAdb(new ArrayList<>(controls.setup().lockedHomes), SelfAdb.Job.HOME, progress, controls::rememberDisabled,
                SelfAdb.Result.OK.messageRes);
    }

    @Override
    public void restoreTvHome(HomeView.SelfSetup progress) {
        runSelfAdb(new ArrayList<>(controls.setup().disabledHomes), SelfAdb.Job.RESTORE, progress, controls::forgetDisabled,
                R.string.setup_tv_restored);
    }

    /**
     * Turns the TV's own home apps off (or back on) over its own adb. {@code record} hears which
     * packages it changed, even when a later one fails, so none is lost track of; {@code okMessage}
     * is shown when all worked. On a thread of its own, not {@link #io}: it can wait over a minute on
     * the TV's prompt, and the home screen's loading mustn't queue up behind it.
     */
    private void runSelfAdb(List<String> packages, SelfAdb.Job job, HomeView.SelfSetup progress,
                            java.util.function.Consumer<List<String>> record, int okMessage) {
        if (!controls.adbEnabled()) {
            // We can't turn debugging on ourselves; send the user to the screen that does.
            controls.openDeveloperSettings();
            progress.done(false, R.string.setup_tv_enable_debug);
            return;
        }
        // A run still going means the sheet was closed and opened again mid-run, and this is a retry.
        // If that run is still waiting on the prompt, it stops (nothing has run yet); if it's already
        // working, it finishes unheard.
        checkApproval.run();
        SelfAdb adb = new SelfAdb();
        selfAdb = adb;
        adbApproving = false;
        new Thread(() -> {
            List<String> changed = new ArrayList<>();
            SelfAdb.Result r = adb.run(packages, job, step -> runOnUiThread(() -> {
                if (selfAdb != adb || isDestroyed()) return; // replaced by a retry, or gone
                adbApproving = step == SelfAdb.Listener.APPROVE;
                progress.status(stepMessage(step));
            }), changed);
            if (!changed.isEmpty()) record.accept(changed);
            boolean ok = r == SelfAdb.Result.OK;
            int message = ok ? okMessage : r.messageRes;
            runOnUiThread(() -> {
                if (selfAdb != adb || isDestroyed()) return;
                selfAdb = null;
                adbApproving = false;
                progress.done(ok, message);
            });
        }, "self-adb").start();
    }

    private static int stepMessage(int step) {
        return switch (step) {
            case SelfAdb.Listener.APPROVE -> R.string.setup_tv_approve;
            case SelfAdb.Listener.WORKING -> R.string.setup_tv_working;
            default -> R.string.setup_tv_connecting;
        };
    }

    @Override
    public void openAbout() {
        controls.openAbout();
    }

    @Override
    public void openDeveloperOptions() {
        controls.openDeveloperSettings();
    }

    @Override
    public void chooseAccount() {
        profile.choose();
    }

    @Override
    public boolean lightAppearance() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_LIGHT, false);
    }

    /** Settings > Appearance: saved, then the row behind the dock is drawn again in it. */
    @Override
    public void setLightAppearance(boolean light) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LIGHT, light).apply();
        view.setLight(light);
        updateBackdrop(); // a row's key includes the appearance
    }

    @Override
    public Donation donation() {
        return donation;
    }

    @Override
    public boolean hasAccount() {
        return profile.hasAccount();
    }

    @Override
    public String account() {
        return profile.account();
    }

    @Override
    public String profilePicture() {
        return profile.picture();
    }

    @Override
    public void setProfilePicture(String picture) {
        profile.setPicture(picture, view::setAvatar);
    }

    @Override
    public boolean use24Hour() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        return prefs.contains(KEY_24_HOUR) ? prefs.getBoolean(KEY_24_HOUR, false) : DateFormat.is24HourFormat(this);
    }

    @Override
    public void setUse24Hour(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_24_HOUR, on).apply();
        view.timeFormatChanged();
    }

    /**
     * After an app moves to Hidden: a toast with the remote's Down key as its icon. A text toast
     * would carry the app's own icon (Android 12+ draws those itself), so this one has its own
     * view, in the system toast's looks; screen readers read it as they do any toast.
     */
    @Override
    @SuppressWarnings("deprecation") // setView: still shown for the app in front, as Home always is when this runs
    public void showHiddenHint() {
        if (hiddenHint != null) hiddenHint.cancel();
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(Math.round(16 * dp), Math.round(12 * dp), Math.round(20 * dp), Math.round(12 * dp));
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(TOAST_FILL);
        frame.setCornerRadius(100 * dp); // a pill, as the system's is
        view.setBackground(frame);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_down_circle);
        icon.setImageTintList(ColorStateList.valueOf(TOAST_INK));
        view.addView(icon, new LinearLayout.LayoutParams(Math.round(24 * dp), Math.round(24 * dp)));
        TextView text = new TextView(this);
        text.setText(R.string.hidden_toast);
        text.setTextColor(TOAST_INK);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.setMarginStart(Math.round(12 * dp));
        view.addView(text, gap);
        hiddenHint = new Toast(this);
        hiddenHint.setView(view);
        hiddenHint.setDuration(Toast.LENGTH_LONG);
        hiddenHint.show();
    }

    /** Settings > Support: atvOS's website, its page on Google Play, or Android's App Info for it. */
    private Intent supportIntent(int what) {
        return switch (what) {
            case SettingsScreen.WEBSITE -> new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE)).addCategory(Intent.CATEGORY_BROWSABLE);
            case SettingsScreen.STORE -> new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName()));
            default -> new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
        };
    }

    /** Whether the TV has an app for it. Android TV answers web links it has no browser for with a stub that only says so. */
    @Override
    public boolean canOpenFromSettings(int what) {
        ComponentName handler = supportIntent(what).resolveActivity(getPackageManager());
        return handler != null && !handler.getPackageName().equals(NO_APP_STUBS);
    }

    @Override
    public boolean openFromSettings(int what) {
        if (!canOpenFromSettings(what)) return false;
        Intent intent = supportIntent(what);
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false; // no browser, say: Settings says where to go instead
        }
    }

    @Override
    public void setupDone() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean first = !prefs.getBoolean(KEY_SET_UP, false);
        if (first) prefs.edit().putBoolean(KEY_SET_UP, true).apply();
        // Setup run from the TV's app list just made atvOS the home screen: carry on in the Home copy.
        if (handOffToHome()) return;
        if (first) askForListings();
    }

    /**
     * Deletes an app the user confirmed in the alert, with Android's own uninstall screen (never
     * over adb). Only apps Android itself lets the user uninstall; the package receiver then
     * refreshes Home.
     */
    @Override
    public void deleteApp(Apps.Entry entry) {
        if (!entry.removable) return;
        start(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, entry.label);
    }

    /** The TV's keyboard, typing into a hidden field that reports to the view as it changes. */
    @Override
    public void editText(String text, HomeView.TextEdit edit) {
        finishTextEdit();
        input.setText(text);
        input.setSelection(input.length());
        textEdit = edit; // after setText, which isn't a change the user made
        input.setVisibility(View.VISIBLE);
        input.requestFocus();
        input.post(() -> getSystemService(InputMethodManager.class).showSoftInput(input, 0)); // the user asked to type
    }

    private EditText textInput() {
        EditText field = new EditText(this) {
            @Override
            public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                // Back keeps what's typed, as Menu does on tvOS.
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    finishTextEdit();
                    return true; // done with: it mustn't go on to the home screen, now focused
                }
                return super.onKeyPreIme(keyCode, event);
            }
        };
        field.setVisibility(View.GONE);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                if (textEdit != null) textEdit.changed(s.toString());
            }
        });
        field.setOnEditorActionListener((v, action, event) -> {
            finishTextEdit();
            return true;
        });
        field.setOnFocusChangeListener((v, has) -> {
            if (!has) finishTextEdit();
        });
        return field;
    }

    private void finishTextEdit() {
        HomeView.TextEdit edit = textEdit;
        if (edit == null) return;
        textEdit = null;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.setVisibility(View.GONE);
        view.requestFocus();
        edit.done(input.getText().toString());
    }

    private Bundle zoomFrom(Rect from) {
        return ActivityOptions.makeScaleUpAnimation(view, from.left, from.top, from.width(), from.height()).toBundle();
    }

    private void start(Intent intent, Bundle options, String label) {
        try {
            startActivity(intent, options);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, getString(R.string.cannot_open, label), Toast.LENGTH_SHORT).show();
        }
    }
}
