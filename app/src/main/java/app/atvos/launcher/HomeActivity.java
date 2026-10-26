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
import android.os.Process;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** The home activity. It owns the view, loads the apps and the backdrops on background threads, and launches apps. */
public final class HomeActivity extends Activity implements HomeView.Host {

    /** The SharedPreferences file for all launcher settings. */
    static final String PREFS = "home";
    private static final String TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final String KEY_ASKED = "asked_tv_listings", KEY_SET_UP = "set_up";
    /** Settings > Appearance: true for light rows and Settings, else dark. {@link Backdrops} reads it too. */
    static final String KEY_LIGHT = "appearance_light";
    /** {@link RemoteKeys} sets this on the Home intent of a held Home button: Control Center opens. */
    static final String EXTRA_CONTROL_CENTER = "app.atvos.launcher.extra.CONTROL_CENTER";
    /**
     * {@link RemoteKeys} sets this when it stands in for the TV's home app: the intent is a press of Home, though it
     * isn't a Home intent.
     */
    static final String EXTRA_HOME_BUTTON = "app.atvos.launcher.extra.HOME_BUTTON";
    /** The user has held Home for Control Center, so the hint doesn't show again. */
    private static final String KEY_HOLD_LEARNED = "hold_home_learned";
    /** Settings > Time Format: true for 24-hour, false for 12-hour. If it is not set, the setting of the TV applies. */
    private static final String KEY_24_HOUR = "time_24_hour";
    /**
     * The colours of the system toast: a dark surface with light text up to Android 15, and a light surface with dark
     * text from Android 16.
     */
    private static final int TOAST_FILL = Build.VERSION.SDK_INT >= 36 ? 0xFFEEEDF4 : Build.VERSION.SDK_INT >= 34 ? 0xFF2F3036 : 0xFF191C1E;
    private static final int TOAST_INK = Build.VERSION.SDK_INT >= 36 ? 0xFF1B1B21 : 0xFFE4E2E6;
    /** The Android TV package that answers intents for apps that the TV does not have (for example, a browser). */
    private static final String NO_APP_STUBS = "com.android.tv.frameworkpackagestubs";
    private static final int REQUEST_LISTINGS = 8, REQUEST_HOME = 9;
    /**
     * The time that adb gets to accept the key after the "Allow debugging" prompt closes. After this time, the prompt
     * counts as declined.
     */
    private static final long APPROVE_GRACE_MS = 5000;

    // Background threads. A task that fails is logged and the thread continues. Tasks after onDestroy are dropped.
    private final ExecutorService io = new Background("atvos-io",
            Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
    private final ExecutorService net = new Background("atvos-net", Process.THREAD_PRIORITY_BACKGROUND); // downloads
    // The backdrop of the focused app. It does not wait for a scan of the apps and recommendations on io.
    private final ExecutorService decode = new Background("atvos-decode",
            Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE);
    private final AtomicBoolean prefetchQueued = new AtomicBoolean();
    /** Remote Buttons started or stopped: setup and Settings read the state again. */
    private final Runnable keysChanged = () -> this.view.refreshSetup();
    /** The apps in the dock (a folder: its first app). Only they show a top shelf, so only their images download. */
    private volatile Set<String> dockPkgs = Set.of();
    private final Set<String> pagesQueued = ConcurrentHashMap.newKeySet(); // apps whose other shelf pages are downloaded
    private Apps apps;
    private Backdrops backdrops;
    private Controls controls;
    private Profile profile;
    private Donation donation;
    private HomeView view;
    private boolean homeInstance;      // true if the Home button started this instance
    private EditText input;            // receives the text from the TV keyboard (a folder name)
    private HomeView.TextEdit textEdit; // the receiver of that text while the keyboard is open
    private Toast hiddenHint;          // the last Hidden toast, so that a new one replaces it

    // UI thread
    private List<Apps.Entry> current = Collections.emptyList();
    private Apps.Entry focused;
    private boolean focusInDock = true, resumed;
    private boolean appsDirty = true; // the installed apps changed, or are not loaded: query them at the next refresh
    private SelfAdb selfAdb;          // the on-TV setup that runs, or null
    private boolean adbApproving;     // true while the TV shows the "Allow debugging" prompt
    private final Runnable checkApproval = () -> {
        if (adbApproving && selfAdb != null) selfAdb.declined();
    };
    private volatile String wantedBackdrop = "";
    private boolean fullScreen;        // the full-screen top shelf shows: its pictures have the details scrim
    /**
     * A focus move within this time after the last move keeps the backdrop until the focus stops. Then only one
     * crossfade starts.
     */
    private static final long SETTLE_MS = 150;
    private long focusMovedAt;
    private final Runnable settled = this::updateBackdrop;

    /** Installs, updates and removals. Without them, a refresh does not query PackageManager. */
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (resumed) refresh();
            view.refreshSetup(); // the setup sheet shows a disabled home app immediately
        }
    };

    /** A new time or time zone, while the home screen shows: the clock changes now, not at the next minute. */
    private final BroadcastReceiver clock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            view.timeFormatChanged();
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
        profile = new Profile(this, io, net, Math.round(40f * dm.widthPixels / HomeView.W));
        donation = new Donation(this); // connects to Google Play only while Settings is open
        view = new HomeView(this, this);
        view.setBackdrop(backdrops.cached(Backdrops.DEFAULT));
        FrameLayout root = new FrameLayout(this);
        root.addView(view);
        root.addView(input = textInput(), new FrameLayout.LayoutParams(1, 1));
        setContentView(root);
        view.requestFocus();
        view.setHoldHintLearned(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_HOLD_LEARNED, false));
        profile.load(view::setAvatar);

        IntentFilter changes = new IntentFilter();
        changes.addAction(Intent.ACTION_PACKAGE_ADDED);
        changes.addAction(Intent.ACTION_PACKAGE_REMOVED);
        changes.addAction(Intent.ACTION_PACKAGE_CHANGED);
        changes.addAction(Intent.ACTION_PACKAGE_REPLACED);
        changes.addDataScheme("package");
        registerReceiver(packages, changes); // only protected system broadcasts, so no export flag
        RemoteKeys.listen(keysChanged); // setup continues when Remote Buttons starts

        // First launch: the setup steps, then the recommendations prompt when setup closes.
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SET_UP, false)) askForListings();
        else view.openSetup();
        if (getIntent().getBooleanExtra(EXTRA_CONTROL_CENTER, false)) heldHome(); // the held Home started atvOS
    }

    /** Home was held: Control Center opens, and the hint for it doesn't show again. */
    private void heldHome() {
        if (!view.openControlCenterFromRemote()) return; // setup shows
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_HOLD_LEARNED, false)) prefs.edit().putBoolean(KEY_HOLD_LEARNED, true).apply();
        view.setHoldHintLearned(true);
    }

    /**
     * Asks one time for access to the recommendations of apps. Without access, the top shelf shows the art of the apps.
     */
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
        profile.refresh(view::setAvatar); // not more than once a day
        donation.settle(); // only after a purchase sheet opened: a donation that Google Play completed later
        view.setClockRunning(true);
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clock, time); // only protected system broadcasts, so no export flag
        view.refreshSetup(); // after the home app dialog or Settings
        // After the "Allow debugging" prompt: adb does not answer a declined prompt, so the wait ends after
        // APPROVE_GRACE_MS.
        if (adbApproving) view.postDelayed(checkApproval, APPROVE_GRACE_MS);
    }

    @Override
    protected void onPause() {
        finishTextEdit();
        view.removeCallbacks(checkApproval); // posted again at the next resume
        resumed = false;
        view.setClockRunning(false);
        unregisterReceiver(clock);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (view == null) return; // onCreate handed off: this instance closes
        boolean standIn = intent.getBooleanExtra(EXTRA_HOME_BUTTON, false);
        if (intent.hasCategory(Intent.CATEGORY_HOME) || standIn) {
            if (!standIn) homeInstance = true; // Android sent Home to this instance
            view.goHome();
            if (intent.getBooleanExtra(EXTRA_CONTROL_CENTER, false)) heldHome();
            // Setup completed in an instance that handed off to this one: the prompt shows here.
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SET_UP, false)) askForListings();
            return;
        }
        handOffToHome();
    }

    /**
     * If atvOS is the home app and this instance did not start from the Home button, it opens the Home instance and
     * closes this one. Returns false for the Home instance, or when atvOS is not the home app.
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

    /**
     * Returns true if the Home button opens atvOS. It reads the app that the intent resolves to (see
     * {@link Controls#setup}).
     */
    @SuppressWarnings("deprecation") // the int-flag method is available on Android 12 and later
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
            view.refreshSetup(); // the profile picture step continues
        }
        if (requestCode == REQUEST_HOME) view.refreshSetup();
    }

    @Override
    protected void onDestroy() {
        if (view == null) { // handed off before setup
            super.onDestroy();
            return;
        }
        view.removeCallbacks(checkApproval);
        view.removeCallbacks(settled);
        RemoteKeys.unlisten(keysChanged);
        checkApproval.run(); // stops a run that waits for the prompt
        unregisterReceiver(packages);
        io.shutdownNow();
        net.shutdownNow();
        decode.shutdownNow();
        donation.end();
        super.onDestroy();
    }

    /**
     * Reads the images and the recommendations again. It queries the installed apps only after the package receiver
     * reports a change.
     */
    private void refresh() {
        List<Apps.Entry> previous = current;
        boolean reload = appsDirty || previous.isEmpty();
        appsDirty = false;
        pagesQueued.clear(); // pages of the apps can change: download them again at focus
        io.execute(() -> {
            backdrops.scan(this);
            Apps.Layout layout = reload ? apps.load(previous) : null;
            if (layout != null) dockPkgs = dock(layout.visible());
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (layout != null) {
                    // During an edit, or after a change of the layout during this load: apply at the next refresh.
                    if (!apps.isLatest(layout) || !view.setApps(layout.visible(), layout.hidden())) {
                        appsDirty = true;
                    } else {
                        current = all(layout.visible(), layout.hidden());
                        apps.saveLoaded(layout);
                    }
                }
                updateBackdrop();
            });
            prefetch();
        });
    }

    /** Downloads the images of the apps in the dock that are not on disk. One download run waits at a time. */
    private void prefetch() {
        if (prefetchQueued.compareAndSet(false, true)) {
            net.execute(() -> {
                prefetchQueued.set(false);
                backdrops.prefetch(dockPkgs, () -> runOnUiThread(this::shelfReady));
            });
        }
    }

    /** Returns the packages in the dock: the first row, with the first app of a folder. */
    private static Set<String> dock(List<Apps.Entry> visible) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < Math.min(HomeView.COLS, visible.size()); i++) {
            Apps.Entry e = visible.get(i);
            if (e.folder == null) out.add(e.pkg);
            else if (!e.folder.apps.isEmpty()) out.add(e.folder.apps.get(0).pkg);
        }
        return Set.copyOf(out);
    }

    /** Shows a downloaded recommendation image if its app has the focus. */
    private void shelfReady() {
        if (!isDestroyed()) updateBackdrop();
    }

    private void updateBackdrop() {
        // When the focus is below the dock, the backdrop does not change. It stays blurred and dimmed.
        if (!focusInDock && !wantedBackdrop.isEmpty()) return;
        // A folder shows the backdrop of its first app.
        Apps.Entry shown = focused != null && focused.folder != null
                ? (focused.folder.apps.isEmpty() ? null : focused.folder.apps.get(0)) : focused;
        String key = shown != null ? backdrops.backdropKey(shown.pkg, fullScreen) : Backdrops.DEFAULT;
        Recommendations.Item program = shown != null ? backdrops.recommendation(shown.pkg) : null;
        if (key.equals(wantedBackdrop)) return;
        wantedBackdrop = key;
        Backdrops.Backdrop cached = backdrops.cached(key);
        if (cached != null) {
            view.setBackdrop(cached);
            return;
        }
        decode.execute(() -> {
            if (!key.equals(wantedBackdrop)) return; // the focus moved
            Backdrops.Backdrop loaded = backdrops.load(key, program);
            runOnUiThread(() -> {
                if (loaded != null) backdrops.put(key, loaded);
                if (key.equals(wantedBackdrop)) view.setBackdrop(loaded != null ? loaded : backdrops.cached(Backdrops.DEFAULT));
            });
        });
    }

    // HomeView.Host

    @Override
    public void onFocusChanged(Apps.Entry entry, boolean inDock) {
        focused = entry;
        focusInDock = inDock;
        long now = android.os.SystemClock.uptimeMillis();
        view.removeCallbacks(settled);
        if (inDock && now - focusMovedAt < SETTLE_MS) view.postDelayed(settled, SETTLE_MS);
        else updateBackdrop();
        focusMovedAt = now;
        // For an app in the dock with a top shelf, downloads its other pages now, so that Up can show them.
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

    /**
     * The full-screen shelf opened or closed. The backdrop changes to the picture with the details scrim or with the
     * dock scrim.
     */
    @Override
    public void setFullScreen(boolean on) {
        fullScreen = on;
        updateBackdrop();
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
        refresh(); // reads the recommendations again and changes the backdrop
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
     * Opens the program of the top shelf. The intent comes from the data of a different app. It can open only the app
     * that published it, with no selector and no URI permissions.
     */
    @Override
    public void launchProgram(Recommendations.Item program, Rect from) {
        if (getPackageName().equals(program.pkg())) return; // never opens an intent of a different app in this app
        Intent intent;
        try {
            intent = Intent.parseUri(program.intentUri(), Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException e) { // extras that are not valid can throw
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
        Set<String> dock = dock(visible);
        if (!dock.equals(dockPkgs)) { // an app moved into the dock: its top shelf downloads now
            dockPkgs = dock;
            prefetch();
        }
    }

    /** Returns a copy of all tiles and apps, with the apps in folders, for the next load. UI thread. */
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
        runSelfAdb(new ArrayList<>(controls.setup().lockedHomes), SelfAdb.Job.HOME, SelfAdb.Job.KEYS_ON, progress,
                controls::rememberDisabled, SelfAdb.Result.OK.messageRes);
    }

    @Override
    public void restoreTvHome(HomeView.SelfSetup progress) {
        Controls.Setup st = controls.setup();
        if (st.disabledHomes.isEmpty()) {
            // Remote Buttons gave atvOS the Home button: turning it off gives it back. No adb is needed.
            boolean off = RemoteKeys.turnOff();
            progress.done(off, off ? R.string.setup_tv_restored : R.string.settings_restore_not_needed);
            return;
        }
        runSelfAdb(new ArrayList<>(st.disabledHomes), SelfAdb.Job.RESTORE, SelfAdb.Job.KEYS_OFF, progress,
                controls::forgetDisabled, R.string.setup_tv_restored);
    }

    @Override
    public void openRemoteButtons() {
        // When the user turns the service on, it brings atvOS back to the front (RemoteKeys.onServiceConnected).
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(RemoteKeys.KEY_OPENED_FOR_SETUP, true).apply();
        controls.openAccessibility();
    }

    /**
     * Settings > General > Remote Buttons. Off: Android turns the service off. On: self-adb turns it on if USB debugging
     * is on, else Accessibility opens for the user to turn it on.
     */
    @Override
    public void setRemoteButtons(boolean on, HomeView.SelfSetup progress) {
        if (!on) {
            RemoteKeys.turnOff();
            progress.done(true, 0);
            return;
        }
        if (!controls.adbEnabled()) {
            openRemoteButtons();
            progress.done(true, R.string.remote_buttons_turn_on); // an instruction, not an error
            return;
        }
        runSelfAdb(new ArrayList<>(), null, SelfAdb.Job.KEYS_ON, progress, changed -> { }, 0);
    }

    /**
     * Disables or enables the home apps of the TV over self-adb, then turns the remote buttons service on or off
     * ({@code then}). {@code record} receives the changed packages, also when a later package fails. {@code okMessage}
     * shows when all packages succeed. It runs on its own thread, not {@link #io}, because it can wait more than one
     * minute for the prompt.
     */
    private void runSelfAdb(List<String> packages, SelfAdb.Job job, SelfAdb.Job then, HomeView.SelfSetup progress,
                            java.util.function.Consumer<List<String>> record, int okMessage) {
        if (!controls.adbEnabled()) {
            // The app cannot turn on debugging. This opens the screen where the user turns it on.
            controls.openDeveloperSettings();
            progress.done(false, R.string.setup_tv_enable_debug);
            return;
        }
        // A run that waits for the prompt stops before it sends a command. A run that sends commands completes, and its
        // result is ignored.
        checkApproval.run();
        SelfAdb adb = new SelfAdb();
        selfAdb = adb;
        adbApproving = false;
        new Thread(() -> {
            List<String> changed = new ArrayList<>();
            SelfAdb.Result r = adb.run(packages, job, getPackageName(), then, step -> runOnUiThread(() -> {
                if (selfAdb != adb || isDestroyed()) return; // a newer run replaced it, or the activity closed
                adbApproving = step == SelfAdb.Listener.APPROVE;
                if (adbApproving) progress.prompt(adb.fingerprint());
                progress.status(stepMessage(step));
            }), changed);
            if (!changed.isEmpty()) record.accept(changed);
            boolean ok = r == SelfAdb.Result.OK;
            // Restore: Android turns the service off too, in case adb couldn't.
            if (ok && then == SelfAdb.Job.KEYS_OFF) runOnUiThread(RemoteKeys::turnOff);
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

    /** Settings > Appearance: saves the value, then draws the row behind the dock again. */
    @Override
    public void setLightAppearance(boolean light) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_LIGHT, light).apply();
        view.setLight(light);
        updateBackdrop(); // the key of a row contains the appearance
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
     * Shows a toast after an app moves to Hidden, with an icon of the Down key. The toast has its own view in the style
     * of the system toast. Screen readers read it as a toast.
     */
    @Override
    @SuppressWarnings("deprecation") // setView applies to the app in the foreground
    public void showHiddenHint() {
        if (hiddenHint != null) hiddenHint.cancel();
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(Math.round(16 * dp), Math.round(12 * dp), Math.round(20 * dp), Math.round(12 * dp));
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(TOAST_FILL);
        frame.setCornerRadius(100 * dp); // a pill
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

    /**
     * Returns the intent for Settings > Support: the Google Play page of atvOS, or App Info for atvOS. The website has
     * no intent, because TV apps do not open a web browser (TV app quality, TV-WB): Settings shows its address.
     */
    private Intent supportIntent(int what) {
        return what == SettingsScreen.STORE
                ? new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + getPackageName()))
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null));
    }

    /** Returns true if the TV has an app for the intent. The stub package of Android TV does not count. */
    private boolean canOpenFromSettings(int what) {
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
            return false; // Settings shows a note
        }
    }

    @Override
    public void setupDone() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean first = !prefs.getBoolean(KEY_SET_UP, false);
        if (first) prefs.edit().putBoolean(KEY_SET_UP, true).apply();
        // Setup made atvOS the home app: the Home instance continues.
        if (handOffToHome()) return;
        if (first) askForListings();
    }

    /**
     * Uninstalls an app after the user confirms it in the alert. It opens the uninstall screen of Android. Only apps
     * that Android lets the user uninstall. The package receiver then refreshes the home screen.
     */
    @Override
    public void deleteApp(Apps.Entry entry) {
        if (!entry.removable) return;
        start(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null, entry.label);
    }

    /**
     * Back with nothing to close. The home screen stays. When another app has the Home button and atvOS runs as an app,
     * Back goes back to that home screen (TV app quality, TV-DB).
     */
    @Override
    public void backAtRoot() {
        // Remote Buttons can stand in for the TV's home app: then atvOS is the home screen, and it stays.
        if (!homeInstance && RemoteKeys.homeMode(this, RemoteKeys.isOn()) != RemoteKeys.STAND_IN) finish();
    }

    /** Opens the TV keyboard for a hidden field. The field sends each change to the view. */
    @Override
    public void editText(String text, HomeView.TextEdit edit) {
        finishTextEdit();
        input.setText(text);
        input.setSelection(input.length());
        textEdit = edit; // set after setText, so that setText is not a change
        input.setVisibility(View.VISIBLE);
        input.requestFocus();
        input.post(() -> getSystemService(InputMethodManager.class).showSoftInput(input, 0)); // the user started the edit
    }

    private EditText textInput() {
        EditText field = new EditText(this) {
            @Override
            public boolean onKeyPreIme(int keyCode, KeyEvent event) {
                // Back keeps the text.
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    finishTextEdit();
                    return true; // the key does not go to the home screen
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
