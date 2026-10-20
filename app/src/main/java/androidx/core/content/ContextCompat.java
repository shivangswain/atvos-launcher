package androidx.core.content;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/**
 * Replaces the AndroidX ContextCompat method that Google Play Billing calls. On Android 13 and later, it calls the
 * platform method. On Android 12, it protects a receiver that is not exported with the signature permission of the app.
 */
public final class ContextCompat {
    public static final int RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x1, RECEIVER_EXPORTED = 0x2, RECEIVER_NOT_EXPORTED = 0x4;

    private ContextCompat() {}

    @SuppressLint("WrongConstant") // the flags are Context.RECEIVER_* values
    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter,
                                          String permission, Handler scheduler, int flags) {
        if (Build.VERSION.SDK_INT >= 33) return context.registerReceiver(receiver, filter, permission, scheduler, flags);
        if ((flags & RECEIVER_NOT_EXPORTED) != 0 && permission == null) {
            permission = context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
        }
        return context.registerReceiver(receiver, filter, permission, scheduler, flags & RECEIVER_VISIBLE_TO_INSTANT_APPS);
    }
}
