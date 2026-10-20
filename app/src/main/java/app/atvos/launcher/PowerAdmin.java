package app.atvos.launcher;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Device admin with a single policy, force-lock, which is what lets Power Off and the Sleep Timer
 * put the TV to sleep. It can't wipe, reset passwords or see anything.
 */
public final class PowerAdmin extends DeviceAdminReceiver {

    /** Turned off (in Settings, say): a sleep timer could no longer sleep the TV, so it goes too. */
    @Override
    public void onDisabled(Context context, Intent intent) {
        SleepTimer.cancel(context);
    }
}
