package app.tvhome;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Gives up the force-lock admin so Home can be uninstalled (Android TV has no screen for it):
 *
 *   adb shell am broadcast -n app.tvhome/.ReleasePower
 *
 * The manifest only accepts senders holding DUMP, which the adb shell has and apps can't get.
 */
public final class ReleasePower extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(context, PowerAdmin.class);
        if (!dpm.isAdminActive(admin)) return;
        SleepTimer.cancel(context);
        dpm.removeActiveAdmin(admin);
        Toast.makeText(context, R.string.power_released, Toast.LENGTH_LONG).show();
    }
}
