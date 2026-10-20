package androidx.core.util;

/**
 * Replaces the AndroidX Consumer interface that the callbacks of Google Play Billing use. It has the same name and
 * signature.
 */
@FunctionalInterface
public interface Consumer<T> {
    void accept(T t);
}
