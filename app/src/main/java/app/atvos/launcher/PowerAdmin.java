package app.atvos.launcher;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device admin with a single policy, force-lock, which is what lets Power Off and the Sleep Timer
 * put the TV to sleep. It can't wipe, reset passwords or see anything.
 */
public final class PowerAdmin extends DeviceAdminReceiver {
}
