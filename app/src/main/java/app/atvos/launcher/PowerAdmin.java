package app.atvos.launcher;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

/** Device admin receiver with one policy, force-lock. Power Off and the sleep timer use it to put the TV to sleep. */
public final class PowerAdmin extends DeviceAdminReceiver {

    /** Cancels the sleep timer when the device admin is disabled. */
    @Override
    public void onDisabled(Context context, Intent intent) {
        SleepTimer.cancel(context);
    }
}
