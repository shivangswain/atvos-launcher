package androidx.core.content;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;

/**
 * Stand-in for the one AndroidX ContextCompat method Google Play billing calls (see
 * {@link androidx.core.util.Consumer} for why), doing what AndroidX does: from Android 13 the
 * platform's own call; before it, a receiver that mustn't be exported is guarded by this app's own
 * signature permission instead (declared in the manifest), as Android 12 has no flag for it.
 */
public final class ContextCompat {
    public static final int RECEIVER_VISIBLE_TO_INSTANT_APPS = 0x1, RECEIVER_EXPORTED = 0x2, RECEIVER_NOT_EXPORTED = 0x4;

    private ContextCompat() {}

    @SuppressLint("WrongConstant") // the flags are billing's, already Context.RECEIVER_* values, passed on as given
    public static Intent registerReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter,
                                          String permission, Handler scheduler, int flags) {
        if (Build.VERSION.SDK_INT >= 33) return context.registerReceiver(receiver, filter, permission, scheduler, flags);
        if ((flags & RECEIVER_NOT_EXPORTED) != 0 && permission == null) {
            permission = context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION";
        }
        return context.registerReceiver(receiver, filter, permission, scheduler, flags & RECEIVER_VISIBLE_TO_INSTANT_APPS);
    }
}
