package app.atvos.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Puts the TV to sleep when the sleep timer alarm fires. It is not exported. */
public final class SleepTimer extends BroadcastReceiver {

    static final String KEY_SLEEP_AT = "sleep_at";

    private static void forget(Context context) {
        context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SLEEP_AT).apply();
    }

    static PendingIntent pending(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Returns true if the alarm exists. A reboot removes the alarm but not the saved deadline. */
    static boolean active(Context context) {
        return existing(context) != null;
    }

    static void cancel(Context context) {
        PendingIntent pi = existing(context); // null if there is no alarm
        if (pi != null) {
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            if (alarms != null) alarms.cancel(pi);
            pi.cancel(); // active() then returns false
        }
        forget(context);
    }

    private static PendingIntent existing(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        forget(context);
        PendingIntent spent = existing(context);
        if (spent != null) spent.cancel(); // active() then returns false
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm != null && dpm.isAdminActive(new ComponentName(context, PowerAdmin.class))) dpm.lockNow();
    }
}
