package app.atvos.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Fires when the sleep timer runs out and puts the TV to sleep. Not exported: only our own alarm reaches it. */
public final class SleepTimer extends BroadcastReceiver {

    static final String KEY_SLEEP_AT = "sleep_at";

    private static void forget(Context context) {
        context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().remove(KEY_SLEEP_AT).apply();
    }

    static PendingIntent pending(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Whether the alarm still exists: alarms don't survive a reboot, though the saved deadline does. */
    static boolean active(Context context) {
        return existing(context) != null;
    }

    static void cancel(Context context) {
        PendingIntent pi = existing(context); // none: nothing to cancel, and none made just to cancel it
        if (pi != null) {
            context.getSystemService(AlarmManager.class).cancel(pi);
            pi.cancel(); // so active() reports it gone
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
        if (spent != null) spent.cancel(); // so active() reports it gone, as after cancel()
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm.isAdminActive(new ComponentName(context, PowerAdmin.class))) dpm.lockNow();
    }
}
