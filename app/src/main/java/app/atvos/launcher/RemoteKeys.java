package app.atvos.launcher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;

/**
 * atvOS Launcher Remote Buttons: the remote buttons that Android gives to the TV's own home app.
 * <ul>
 * <li>Holding Home opens Control Center. A short press goes Home.</li>
 * <li>The Settings button (KEYCODE_SETTINGS, or KEYCODE_NOTIFICATION on TCL remotes) opens the TV's Settings.</li>
 * </ul>
 * It works in one of two ways ({@link #homeMode}):
 * <ul>
 * <li>{@link #HOME}: setup made atvOS the home app over self-adb. A short Home press goes to Android as before.</li>
 * <li>{@link #STAND_IN}: the user turned the service on in Accessibility instead of using adb, so the TV's own home app
 * keeps the Home button. Home opens atvOS, and when the TV's home screen opens (after Back in an app, or at start-up),
 * the service opens atvOS over it. For this it gets the window changes of that one app only.</li>
 * </ul>
 * A key filter sees the keys of the remote before the window manager does. Every other key passes unchanged, and it
 * does nothing while a different app that the user chose is the home app. It can't read windows (see
 * {@code res/xml/remote_keys.xml}). Keys that apps inject (adb {@code input}, or a key that a TV service sends again)
 * don't pass through key filters.
 */
public final class RemoteKeys extends AccessibilityService {

    private static final String TAG = "atvOS.keys";
    /** How atvOS gets the Home button: not at all, as the home app, or through this service. */
    static final int NONE = 0, HOME = 1, STAND_IN = 2;
    /**
     * Setup opened Accessibility for this service: when it starts, atvOS comes back to the front, so that setup
     * continues without Back.
     */
    static final String KEY_OPENED_FOR_SETUP = "remote_buttons_setup";
    /**
     * How long Home is held before Control Center opens. The remote of a TCL TV sends one key down and one key up, with
     * no repeats while the button is held, so a timer from the key down measures the hold.
     */
    private static final long HOLD_MS = 600;
    /** A start-up shows the TV's home screen: within this time after boot, the service opens atvOS when it starts. */
    private static final long BOOT_MS = 3 * 60_000;
    /** The shortest time between two openings of atvOS for the TV's home screen. */
    private static final long REOPEN_MS = 1_000;

    private static volatile RemoteKeys instance; // the bound service, or null
    private static Runnable changed;              // the home screen, while it runs: setup and Settings read the state again

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int homeDown = -1;           // the mode of a Home press that this service took, or -1
    private boolean held;                // that press opened Control Center
    private boolean settingsDown;        // this service took the key down of the Settings button
    private ComponentName standInFor;    // the home activity of the TV that atvOS opens over, or null
    private long openedAt;
    private final Runnable hold = () -> {
        held = true;
        open(homeDown, true);
    };

    /** Returns true while Android has the service bound. Any thread. */
    static boolean isOn() {
        return instance != null;
    }

    /** Returns true if the service is on in Accessibility, whether or not Android has started it. */
    static boolean isEnabled(Context c) {
        String list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return list != null && (":" + list).contains(":" + c.getPackageName() + "/");
    }

    /** {@code onChange} runs on the UI thread when the service starts or stops. UI thread. */
    static void listen(Runnable onChange) {
        changed = onChange;
    }

    /** Removes {@code onChange} if it is the listener (a newer home screen can have replaced it). UI thread. */
    static void unlisten(Runnable onChange) {
        if (changed == onChange) changed = null;
    }

    /** Turns the service off, as the switch in Accessibility does. Returns false if it isn't on. UI thread. */
    static boolean turnOff() {
        RemoteKeys s = instance;
        if (s == null) return false;
        s.disableSelf();
        s.stop(); // off now: Android unbinds it a moment later
        return true;
    }

    /**
     * Returns how atvOS gets the Home button. {@code on}: the service runs. While the service runs, it stands in for the
     * TV's own home app or Android's chooser (system apps), but not for a different home app that the user installed.
     */
    static int homeMode(Context c, boolean on) {
        return mode(c, defaultHome(c), on);
    }

    /** Returns the mode for {@code home}, the activity that the Home button opens. */
    static int mode(Context c, ResolveInfo home, boolean on) {
        if (home == null) return NONE;
        ActivityInfo a = home.activityInfo;
        if (c.getPackageName().equals(a.packageName)) return HOME;
        return on && (a.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? STAND_IN : NONE;
    }

    /** Returns the activity that the Home button opens, or null. */
    @SuppressWarnings("deprecation") // the int-flag method is available on Android 12 and later
    private static ResolveInfo defaultHome(Context c) {
        return c.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        if (changed != null) changed.run();
        int mode = update();
        SharedPreferences prefs = getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE);
        boolean forSetup = prefs.getBoolean(KEY_OPENED_FOR_SETUP, false);
        if (forSetup) prefs.edit().remove(KEY_OPENED_FOR_SETUP).apply();
        // After setup opened Accessibility for it, atvOS comes back and setup continues. After start-up, the TV's home
        // screen is in front.
        if (forSetup && mode != NONE || mode == STAND_IN && SystemClock.elapsedRealtime() < BOOT_MS) open(mode, false);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        stop();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    private void stop() {
        if (instance == this) {
            instance = null;
            if (changed != null) changed.run();
        }
        handler.removeCallbacks(hold);
    }

    /**
     * The TV's home screen opened (only in {@link #STAND_IN}, and only from its package): atvOS opens over it. Other
     * windows of that app, such as its panels, stay.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || standInFor == null) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null || !standInFor.getPackageName().contentEquals(pkg)
                || !standInFor.getClassName().contentEquals(cls)) return;
        if (update() != STAND_IN || SystemClock.uptimeMillis() - openedAt < REOPEN_MS) return;
        open(STAND_IN, false);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(hold);
    }

    /** Returns true to take the key; Android then doesn't deliver it. Main thread. */
    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_HOME -> {
                if (down) {
                    if (e.getRepeatCount() > 0) return homeDown >= 0; // a remote that repeats while held
                    int mode = update();
                    if (mode == NONE) return false;
                    homeDown = mode;
                    held = false;
                    handler.postDelayed(hold, HOLD_MS);
                    return true;
                }
                if (homeDown < 0) return false; // its key down went to Android
                int mode = homeDown;
                homeDown = -1;
                handler.removeCallbacks(hold);
                if (held || e.isCanceled()) return true;
                if (mode == HOME) performGlobalAction(GLOBAL_ACTION_HOME); // a short press: Home, as before
                else open(STAND_IN, false);
                return true;
            }
            case KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION -> {
                if (down) {
                    // One check for each press: the key up follows the key down.
                    if (e.getRepeatCount() == 0) settingsDown = homeMode(this, true) != NONE;
                    return settingsDown;
                }
                if (!settingsDown) return false;
                settingsDown = false;
                if (!e.isCanceled()) start(new Intent(Settings.ACTION_SETTINGS));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Reads the home mode, and gets the window changes of the TV's home app only while it stands in for that app.
     * Returns the mode.
     */
    private int update() {
        ResolveInfo ri = defaultHome(this);
        int mode = mode(this, ri, true);
        ComponentName home = null;
        if (mode == STAND_IN) { // the window events name the activity class, not an alias of it
            ActivityInfo a = ri.activityInfo;
            home = new ComponentName(a.packageName, a.targetActivity != null ? a.targetActivity : a.name);
        }
        standInFor = home;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            int types = home != null ? AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED : 0;
            String[] packages = home != null ? new String[]{home.getPackageName()} : null;
            if (info.eventTypes != types || !Arrays.equals(info.packageNames, packages)) {
                info.eventTypes = types;
                info.packageNames = packages;
                setServiceInfo(info);
            }
        }
        return mode;
    }

    /**
     * Brings the atvOS home screen to the front, with Control Center open for {@code controlCenter}. As the home app,
     * it is the Home intent; as a stand-in, it is atvOS by name, so that Android doesn't take it for a second home app.
     */
    private void open(int mode, boolean controlCenter) {
        Intent intent = mode == HOME
                ? new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                : new Intent(this, HomeActivity.class).putExtra(HomeActivity.EXTRA_HOME_BUTTON, true);
        if (controlCenter) intent.putExtra(HomeActivity.EXTRA_CONTROL_CENTER, true);
        openedAt = SystemClock.uptimeMillis();
        start(intent);
    }

    private void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Log.w(TAG, "can't open " + intent.getAction(), ex);
        }
    }
}
