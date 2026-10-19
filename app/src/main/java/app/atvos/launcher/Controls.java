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
import java.util.List;

/** The system state and actions behind the Control Center tiles. UI thread. */
final class Controls {

    /** A snapshot for drawing the tiles. */
    static final class State {
        boolean online, ethernet, dnd, bluetoothOn;
        String bluetoothDevice; // connected Bluetooth audio output, or null
        long sleepAt;           // SystemClock.elapsedRealtime() deadline (the alarm's clock), 0 = off
    }

    private static final int[] SLEEP_MINUTES = {15, 30, 60, 90, 120};
    // Google TV Settings screens: Remotes & Accessories, and Settings > System.
    static final String ACCESSORIES = "com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES";
    static final String SYSTEM = "com.android.tv.settings.DEVICES";
    static final String INPUTS = "com.android.tv.action.VIEW_INPUTS";
    /** How the TV's home screen is set up, for the Setup & Support sheet. */
    static final class Setup {
        boolean homeActive;   // the Home button opens atvOS Launcher
        String currentHome;   // what it opens instead (the app's name), or null if unknown
        /**
         * Home apps that keep the Home button whatever is chosen in Android's dialog: the TV's own
         * (Google TV's, Android TV's), which declare a raised priority. Only adb can turn them off.
         */
        final List<String> lockedHomes = new ArrayList<>();
        boolean canPickHome;  // Android's home-app dialog can make atvOS the home screen
        boolean holdsPower;   // the device admin is active
    }
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

        // Adapter state needs no permission on Android 12+; the connected headphones come from the
        // audio routes, which avoids the BLUETOOTH_CONNECT permission entirely.
        BluetoothManager bm = activity.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        try {
            s.bluetoothOn = adapter != null && adapter.isEnabled();
        } catch (SecurityException ignored) {
            // Older releases want the legacy BLUETOOTH permission; just show it as off.
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

        // A reboot drops the alarm but not the saved deadline: only trust it while the alarm exists.
        long at = prefs.getLong(SleepTimer.KEY_SLEEP_AT, 0);
        if (at > SystemClock.elapsedRealtime() && SleepTimer.active(activity)) {
            s.sleepAt = at;
        } else if (at != 0) {
            prefs.edit().remove(SleepTimer.KEY_SLEEP_AT).apply();
        }
        return s;
    }

    /** Puts the TV to sleep, the same as the remote's power button. Needs the one-time "lock screen" admin consent. */
    void powerOff() {
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow();
        } else {
            requestAdmin();
        }
    }

    /** Off → 15 → 30 → 60 → 90 → 120 min → off. */
    @SuppressLint("MissingPermission") // canScheduleExactAlarms() guards the exact alarm
    void cycleSleepTimer() {
        if (!dpm.isAdminActive(admin)) {
            requestAdmin();
            return;
        }
        long now = SystemClock.elapsedRealtime(), at = prefs.getLong(SleepTimer.KEY_SLEEP_AT, 0);
        long left = at > now ? at - now : 0;
        // The next step up from what's left; after the longest, the timer turns off.
        int next = 0;
        for (int m : SLEEP_MINUTES) {
            if (m * 60_000L > left + 60_000L) {
                next = m;
                break;
            }
        }
        if (next <= 0) {
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
            // An inexact alarm can be as much as 10 minutes late, so first ask for exact alarms
            // (Android 14+ doesn't grant them by default), where the TV has the screen for it.
            Intent ask = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", activity.getPackageName(), null));
            try {
                activity.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException e) {
                alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, 60_000, pi);
            }
        }
        prefs.edit().putLong(SleepTimer.KEY_SLEEP_AT, trigger).apply(); // same clock as the alarm, so a clock change can't skew it
    }

    /** Needs Do Not Disturb access, granted once with adb (Google TV has no screen for it). */
    void toggleDnd() {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            // Android's own screen for granting it, where the TV has one; otherwise say where to look.
            if (!open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) {
                Toast.makeText(activity, R.string.dnd_needs_access, Toast.LENGTH_LONG).show();
            }
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
    }

    /**
     * Gives up the force-lock device admin (holding select on Power Off), so the app can be
     * uninstalled without adb: Android TV has no settings screen for device admins.
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
     * Reads the home-screen setup from where the Home button actually goes, not from Android's
     * home-app role: on Google TV and Android TV the TV's own home declares a raised priority and
     * keeps the button even after atvOS is chosen in the dialog (checked on both, Android 12).
     */
    @SuppressWarnings("deprecation") // int-flag overloads work on every version
    Setup setup() {
        Setup s = new Setup();
        PackageManager pm = activity.getPackageManager();
        String self = activity.getPackageName();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo current = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        String currentPkg = current != null ? current.activityInfo.packageName : null;
        s.homeActive = self.equals(currentPkg);
        if (!s.homeActive && currentPkg != null && !"android".equals(currentPkg)) { // "android": Android's chooser
            s.currentHome = current.loadLabel(pm).toString();
        }
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (ri.priority > 0 && !self.equals(pkg) && !s.lockedHomes.contains(pkg)) s.lockedHomes.add(pkg);
        }
        RoleManager roles = activity.getSystemService(RoleManager.class);
        boolean roleAvailable = roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME);
        boolean chosen = roleAvailable && roles.isRoleHeld(RoleManager.ROLE_HOME);
        // The dialog only helps against an ordinary home app; a locked one wins regardless.
        s.canPickHome = !s.homeActive && s.lockedHomes.isEmpty() && !chosen
                && (roleAvailable || resolves(Settings.ACTION_HOME_SETTINGS));
        s.holdsPower = dpm.isAdminActive(admin);
        return s;
    }

    /**
     * Asks Android to make atvOS the home app: its "Default home app" dialog, else the Settings
     * screen for it. False if this TV has neither.
     */
    boolean requestDefaultHome(int requestCode) {
        RoleManager roles = activity.getSystemService(RoleManager.class);
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            try {
                activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode);
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // fall through to Settings
            }
        }
        return open(Settings.ACTION_HOME_SETTINGS);
    }

    @SuppressWarnings("deprecation")
    private boolean resolves(String action) {
        return activity.getPackageManager().resolveActivity(new Intent(action), PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    /** Opens a screen by intent action; false if this TV has nothing for it. */
    boolean open(String action) {
        try {
            activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /** A Settings screen, falling back to Settings itself where this TV doesn't have that screen. */
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
