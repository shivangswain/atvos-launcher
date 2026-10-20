package androidx.core.app;

import android.os.Bundle;
import android.os.IBinder;

/**
 * Stand-in for the one AndroidX BundleCompat method Google Play billing calls (see
 * {@link androidx.core.util.Consumer} for why). On Android 12 and later it's the platform's own.
 */
public final class BundleCompat {
    private BundleCompat() {}

    public static void putBinder(Bundle bundle, String key, IBinder binder) {
        bundle.putBinder(key, binder);
    }
}
