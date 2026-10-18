package app.atvos.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Toast;

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
    @SuppressLint("MissingPermission") // USE_EXACT_ALARM grants it; canScheduleExactAlarms() guards it anyway
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
            alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, 60_000, pi);
        }
        prefs.edit().putLong(SleepTimer.KEY_SLEEP_AT, trigger).apply(); // same clock as the alarm, so a clock change can't skew it
    }

    /** Needs Do Not Disturb access, granted once with adb (Google TV has no screen for it). */
    void toggleDnd() {
        NotificationManager nm = activity.getSystemService(NotificationManager.class);
        if (!nm.isNotificationPolicyAccessGranted()) {
            Toast.makeText(activity, R.string.dnd_needs_access, Toast.LENGTH_LONG).show();
            return;
        }
        boolean on = nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(on ? NotificationManager.INTERRUPTION_FILTER_ALL : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
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
