package app.atvos.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The system state and the actions of the Control Center tiles. UI thread, except {@link #rememberDisabled} and
 * {@link #forgetDisabled}, which the self-adb thread calls.
 */
final class Controls {

    /** A copy of the state for the tile draw. */
    static final class State {
        boolean online, ethernet, dnd, bluetoothOn;
        String bluetoothDevice; // the connected Bluetooth audio device, or null
        long sleepAt;           // the deadline in SystemClock.elapsedRealtime(), or 0 when off
    }

    private static final int[] SLEEP_MINUTES = {15, 30, 60, 90, 120};
    private static final String KEY_ASKED_EXACT = "asked_exact_alarms";
    // Settings screens of Google TV: Remotes & Accessories, and Settings > System.
    static final String ACCESSORIES = "com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES";
    static final String SYSTEM = "com.android.tv.settings.DEVICES";
    static final String INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** The home screen configuration of the TV, for setup and Settings. */
    static final class Setup {
        boolean homeActive;   // the Home button opens atvOS Launcher
        String currentHome;   // the name of the app that the Home button opens, or null
        /**
         * Home apps with a raised priority. They keep the Home button when the user selects a different home app. Only
         * adb can disable them.
         */
        final List<String> lockedHomes = new ArrayList<>();
        boolean canPickHome;  // the home app dialog of Android can make atvOS the home app
        boolean holdsPower;   // the device admin is active
        boolean developerOn;  // Developer options are on
        boolean adbOn;        // USB or network debugging is on
        /** Home apps that self-adb disabled. */
        final List<String> disabledHomes = new ArrayList<>();
    }
    /** The packages that self-adb disabled (comma-separated). Restore enables these packages again. */
    static final String KEY_DISABLED_HOMES = "self_disabled_homes";
    /** Home apps that self-adb does not disable: the Android chooser and Settings. */
    private static final Set<String> NEVER_DISABLE = Set.of("android", "com.android.settings", "com.android.tv.settings");
    private final Activity activity;
    private final SharedPreferences prefs;
    private final DevicePolicyManager dpm;
    private final ComponentName admin;

    Controls(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        this.dpm = activity.getSystemService(DevicePolicyManager.class);
        this.admin = new ComponentName(activity, PowerAdmin.class);
    }

    State state() {
        State s = new State();
        ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
        Network net = cm.getActiveNetwork();
        NetworkCapabilities caps = net != null ? cm.getNetworkCapabilities(net) : null;
        s.online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        s.ethernet = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);

        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        s.dnd = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;

        // The adapter state needs no permission on Android 12 and later. The connected headphones come from the audio
        // routes, so BLUETOOTH_CONNECT is not necessary.
        BluetoothManager bm = activity.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        try {
            s.bluetoothOn = adapter != null && adapter.isEnabled();
        } catch (SecurityException ignored) {
            // Without the permission, Bluetooth shows as off.
        }
        AudioManager am = activity.getSystemService(AudioManager.class);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            boolean bluetooth = t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                    || t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER;
            CharSequence name = d.getProductName();
            if (bluetooth && name != null && name.length() > 0) {
                s.bluetoothDevice = name.toString();
                break;
            }
        }

        // A reboot removes the alarm but not the saved deadline. The deadline is used only while the alarm exists.
        long at = prefs.getLong(SleepTimer.KEY_SLEEP_AT, 0);
        if (at > SystemClock.elapsedRealtime() && SleepTimer.active(activity)) {
            s.sleepAt = at;
        } else if (at != 0) {
            prefs.edit().remove(SleepTimer.KEY_SLEEP_AT).apply();
        }
        return s;
    }

    /** Puts the TV to sleep, as the power button of the remote does. The device admin must be active. */
    void powerOff() {
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
        } else {
            requestAdmin();
        }
    }

    /** Changes the sleep timer: Off → 15 → 30 → 60 → 90 → 120 min → Off. */
    @SuppressLint("MissingPermission") // canScheduleExactAlarms() guards the exact alarm
    void cycleSleepTimer() {
        if (!dpm.isAdminActive(admin)) {
            requestAdmin();
            return;
        }
        // A deadline from before a reboot is ignored.
        long now = SystemClock.elapsedRealtime(), at = SleepTimer.active(activity) ? prefs.getLong(SleepTimer.KEY_SLEEP_AT, 0) : 0;
        long left = at > now ? at - now : 0;
        // The next step above the time that remains. After the longest step, the timer goes off.
        int next = 0;
        for (int m : SLEEP_MINUTES) {
            if (m * 60_000L > left + 60_000L) {
                next = m;
                break;
            }
        }
        if (next == 0) {
            SleepTimer.cancel(activity);
            return;
        }
        AlarmManager alarms = activity.getSystemService(AlarmManager.class);
        PendingIntent pi = SleepTimer.pending(activity);
        long delay = next * 60_000L;
        long trigger = now + delay;
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
        } else {
            // Asks once for exact alarms if the TV has the screen for it. An inexact alarm can be up to 10 minutes
            // late.
            if (!prefs.getBoolean(KEY_ASKED_EXACT, false)) {
                prefs.edit().putBoolean(KEY_ASKED_EXACT, true).apply();
                Intent ask = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", activity.getPackageName(), null));
                try {
                    activity.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                } catch (ActivityNotFoundException | SecurityException ignored) {
                    // the TV has no such screen
                }
            }
            alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, 60_000, pi);
        }
        prefs.edit().putLong(SleepTimer.KEY_SLEEP_AT, trigger).apply(); // the same clock as the alarm
    }

    /** Turns Do Not Disturb on or off. It needs Do Not Disturb access. */
    void toggleDnd() {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            // Opens the access screen of Android if the TV has one, else shows a message.
            if (!open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) {
                Toast.makeText(activity, R.string.dnd_needs_access, Toast.LENGTH_LONG).show();
            }
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }

    /**
     * Removes the force-lock device admin, so that the user can uninstall the app. A long press on Power Off calls it.
     */
    void releasePower() {
        if (!dpm.isAdminActive(admin)) {
            Toast.makeText(activity, R.string.power_not_held, Toast.LENGTH_SHORT).show();
            return;
        }
        SleepTimer.cancel(activity);
        dpm.removeActiveAdmin(admin);
        Toast.makeText(activity, R.string.power_released, Toast.LENGTH_LONG).show();
    }

    /**
     * Reads the home screen configuration from the app that the Home button opens, not from the home role of Android.
     */
    @SuppressWarnings("deprecation") // the int-flag methods are available on Android 12 and later
    Setup setup() {
        Setup s = new Setup();
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo current = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        String currentPkg = current != null ? current.activityInfo.packageName : null;
        s.homeActive = self.equals(currentPkg);
        if (!s.homeActive && currentPkg != null && !"android".equals(currentPkg)) { // "android" is the chooser of Android
            // Uses the app name, not the activity name.
            s.currentHome = current.activityInfo.applicationInfo.loadLabel(pm).toString();
        }
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            // Never Android or Settings, whatever their priority.
            if (ri.priority > 0 && !self.equals(pkg) && !NEVER_DISABLE.contains(pkg) && !s.lockedHomes.contains(pkg)) {
                s.lockedHomes.add(pkg);
            }
        }
        RoleManager roles = activity.getSystemService(RoleManager.class);
        boolean roleAvailable = roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME);
        boolean chosen = roleAvailable && roles.isRoleHeld(RoleManager.ROLE_HOME);
        // The dialog applies only when no home app has a raised priority.
        s.canPickHome = !s.homeActive && s.lockedHomes.isEmpty() && !chosen
                && (roleAvailable || hasHomeSettings());
        // The home apps that self-adb disabled, and the home apps of the TV that are disabled for this user. Only
        // installed and disabled packages are kept, because Restore stops at the first failure.
        Set<String> candidates = disabled();
        for (ResolveInfo ri : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_SYSTEM_ONLY)) {
            candidates.add(ri.activityInfo.packageName);
        }
        for (String pkg : candidates) {
            if (self.equals(pkg) || NEVER_DISABLE.contains(pkg)) continue;
            try {
                if (pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) s.disabledHomes.add(pkg);
            } catch (IllegalArgumentException ignored) {
                // uninstalled
            }
        }
        s.adbOn = adbEnabled();
        s.developerOn = s.adbOn || Settings.Global.getInt(activity.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        s.holdsPower = dpm.isAdminActive(admin);
        return s;
    }

    /** Returns true if adb debugging (USB or wireless) is on. */
    boolean adbEnabled() {
        return Settings.Global.getInt(activity.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /** Opens Developer options. */
    void openDeveloperSettings() {
        if (!openFresh(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) open(Settings.ACTION_SETTINGS);
    }

    /** Opens the About screen of Settings. */
    void openAbout() {
        if (!openFresh(Settings.ACTION_DEVICE_INFO_SETTINGS)) open(Settings.ACTION_SETTINGS);
    }

    /** Returns the saved home apps that self-adb disabled. */
    private Set<String> disabled() {
        Set<String> all = new LinkedHashSet<>();
        for (String pkg : prefs.getString(KEY_DISABLED_HOMES, "").split(",")) if (!pkg.isEmpty()) all.add(pkg);
        return all;
    }

    /**
     * Adds {@code packages} to the saved home apps that self-adb disabled. Any thread: two runs (one replaced by a retry)
     * can finish together, and each read-modify-write must see the other.
     */
    synchronized void rememberDisabled(List<String> packages) {
        Set<String> all = disabled();
        all.addAll(packages);
        prefs.edit().putString(KEY_DISABLED_HOMES, String.join(",", all)).apply();
    }

    /** Removes {@code packages} from the saved home apps that self-adb disabled. Any thread. */
    synchronized void forgetDisabled(List<String> packages) {
        Set<String> all = disabled();
        packages.forEach(all::remove);
        if (all.isEmpty()) prefs.edit().remove(KEY_DISABLED_HOMES).apply();
        else prefs.edit().putString(KEY_DISABLED_HOMES, String.join(",", all)).apply();
    }

    /**
     * Asks Android to make atvOS the home app: the "Default home app" dialog, else the Settings screen for it. Returns
     * false if the TV has neither.
     */
    boolean requestDefaultHome(int requestCode) {
        RoleManager roles = activity.getSystemService(RoleManager.class);
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            try {
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // no dialog: try Settings
            }
        }
        return open(Settings.ACTION_HOME_SETTINGS);
    }

    @SuppressWarnings("deprecation")
    private boolean hasHomeSettings() {
        return activity.getPackageManager().resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    /** Opens a screen by intent action. Returns false if no app can open it. */
    boolean open(String action) {
        return start(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Opens the screen in a new Settings task. Without a new task, an open Settings task shows its last screen. */
    private boolean openFresh(String action) {
        return start(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    private boolean start(Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** Opens a Settings screen, or Settings if the TV does not have that screen. */
    void openSettings(String action) {
        if (!open(action) && !open(Settings.ACTION_SETTINGS)) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void requestAdmin() {
        try {
            activity.startActivity(new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, activity.getString(R.string.power_admin_description)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
