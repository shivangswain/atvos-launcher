package androidx.core.app;

import android.os.Bundle;
import android.os.IBinder;

/** Replaces the AndroidX BundleCompat method that Google Play Billing calls. It calls the platform method. */
public final class BundleCompat {
    private BundleCompat() {}

    public static void putBinder(Bundle bundle, String key, IBinder binder) {
        bundle.putBinder(key, binder);
    }
}
