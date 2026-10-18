package app.tvhome;

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

    static PendingIntent pending(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Whether the alarm still exists: alarms don't survive a reboot, though the saved deadline does. */
    static boolean active(Context context) {
        return PendingIntent.getBroadcast(context, 0, new Intent(context, SleepTimer.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_NO_CREATE) != null;
    }

    static void cancel(Context context) {
        PendingIntent pi = pending(context);
        context.getSystemService(AlarmManager.class).cancel(pi);
        pi.cancel(); // so active() reports it gone
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove(KEY_SLEEP_AT).apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        context.getSharedPreferences("home", Context.MODE_PRIVATE).edit().remove(KEY_SLEEP_AT).apply();
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        if (dpm.isAdminActive(new ComponentName(context, PowerAdmin.class))) dpm.lockNow();
    }
}
