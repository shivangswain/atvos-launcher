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
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.widget.Toast;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** The home activity: owns the view, loads apps and backdrops off the UI thread, launches apps. */
public final class HomeActivity extends Activity implements HomeView.Host {

    /** The one SharedPreferences file every part of the launcher keeps its settings in. */
    static final String PREFS = "home";
    private static final String TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    private static final String KEY_ASKED = "asked_tv_listings", KEY_SET_UP = "set_up";
    private static final int REQUEST_LISTINGS = 8, REQUEST_HOME = 9;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor(); // recommendation prefetch
    private final AtomicBoolean prefetchQueued = new AtomicBoolean();
    private Apps apps;
    private Backdrops backdrops;
    private Controls controls;
    private Profile profile;
    private HomeView view;

    // UI thread
    private List<Apps.Entry> current = Collections.emptyList();
    private Apps.Entry focused;
    private boolean focusInDock = true, resumed;
    private boolean appsDirty = true; // installed apps changed (or not loaded yet): re-query them on refresh
    private volatile String wantedBackdrop = "";

    /** Installs, updates and removals; between them, resuming Home skips the PackageManager queries. */
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (resumed) refresh();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        apps = new Apps(this, dm.widthPixels);
        backdrops = new Backdrops(this, dm.widthPixels, dm.heightPixels);
        controls = new Controls(this);
        profile = new Profile(this, io, Math.round(40f * dm.widthPixels / HomeView.W));
        view = new HomeView(this, this);
        view.setBackdrop(backdrops.cached(Backdrops.DEFAULT));
        setContentView(view);
        view.requestFocus();
        profile.load(view::setAvatar);

        IntentFilter changes = new IntentFilter();
        changes.addAction(Intent.ACTION_PACKAGE_ADDED);
        changes.addAction(Intent.ACTION_PACKAGE_REMOVED);
        changes.addAction(Intent.ACTION_PACKAGE_CHANGED);
        changes.addAction(Intent.ACTION_PACKAGE_REPLACED);
        changes.addDataScheme("package");
        registerReceiver(packages, changes); // protected system broadcasts only, so no export flag needed

        // First run: the Setup & Support sheet, then (as it closes) the recommendations prompt.
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SET_UP, false)) askForListings();
        else view.openSetup(true);
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
    }

    @Override
    protected void onPause() {
        resumed = false;
        view.setClockRunning(false);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.hasCategory(Intent.CATEGORY_HOME)) view.goHome();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Profile.REQUEST_ACCOUNT) profile.onActivityResult(resultCode, data, view::setAvatar);
        if (requestCode == REQUEST_HOME) view.refreshSetup();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        io.shutdownNow();
        net.shutdownNow();
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
        io.execute(() -> {
            backdrops.scan(this);
            Apps.Layout layout = reload ? apps.load(previous) : null;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (layout != null) {
                    if (view.setApps(layout.visible, layout.hidden)) current = all(layout.visible, layout.hidden);
                    else appsDirty = true; // mid-edit: apply on the next refresh instead
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
        String key = focused != null ? backdrops.backdropKey(focused.pkg) : Backdrops.DEFAULT;
        Recommendations.Item program = focused != null ? backdrops.recommendation(focused.pkg) : null;
        if (key.equals(wantedBackdrop)) return;
        wantedBackdrop = key;
        Backdrops.Backdrop cached = backdrops.cached(key);
        if (cached != null) {
            view.setBackdrop(cached);
            return;
        }
        String k = key;
        io.execute(() -> {
            if (!k.equals(wantedBackdrop)) return; // focus already moved on
            Backdrops.Backdrop loaded = backdrops.load(k, program);
            runOnUiThread(() -> {
                if (loaded != null) backdrops.put(k, loaded);
                if (k.equals(wantedBackdrop)) view.setBackdrop(loaded != null ? loaded : backdrops.cached(Backdrops.DEFAULT));
            });
        });
    }

    // ---- HomeView.Host -----------------------------------------------------------------------

    @Override
    public void onFocusChanged(Apps.Entry entry, boolean inDock) {
        focused = entry;
        focusInDock = inDock;
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
        if (getPackageName().equals(program.pkg)) return; // never aim another app's intent at ourselves
        Intent intent;
        try {
            intent = Intent.parseUri(program.intentUri, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException e) { // malformed extras can throw too
            Toast.makeText(this, getString(R.string.cannot_open, program.title), Toast.LENGTH_SHORT).show();
            return;
        }
        intent.setSelector(null);
        intent.setClipData(null);
        intent.removeFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        ComponentName target = intent.getComponent();
        if (target != null && !target.getPackageName().equals(program.pkg)) intent.setComponent(null);
        intent.setPackage(program.pkg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        start(intent, zoomFrom(from), program.title);
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

    private static List<Apps.Entry> all(List<Apps.Entry> visible, List<Apps.Entry> hidden) {
        List<Apps.Entry> all = new ArrayList<>(visible);
        all.addAll(hidden);
        return all;
    }

    @Override
    public Controls.State controlState() {
        return controls.state();
    }

    @Override
    public boolean control(int id) {
        switch (id) {
            case ControlCenter.PROFILE: profile.choose(); return false;
            case ControlCenter.SETTINGS: controls.openSettings(Settings.ACTION_SETTINGS); return false;
            case ControlCenter.POWER: controls.powerOff(); return false;
            case ControlCenter.WIFI: controls.openSettings(Settings.ACTION_WIFI_SETTINGS); return false;
            case ControlCenter.DND: controls.toggleDnd(); return true;
            case ControlCenter.BLUETOOTH: controls.openSettings(Controls.ACCESSORIES); return false;
            case ControlCenter.SLEEP: controls.cycleSleepTimer(); return true;
            case ControlCenter.ACCESSIBILITY: controls.openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS); return false;
            case ControlCenter.SYSTEM: controls.openSettings(Controls.SYSTEM); return false;
            case ControlCenter.INPUTS:
            case ControlCenter.SEARCH:
                if (!controls.open(id == ControlCenter.INPUTS ? Controls.INPUTS : Intent.ACTION_ASSIST)) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                return false;
            default: return true;
        }
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
    public void setupDone() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SET_UP, false)) return;
        prefs.edit().putBoolean(KEY_SET_UP, true).apply();
        askForListings();
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
