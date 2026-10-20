package androidx.core.util;

/**
 * Stand-in for AndroidX's Consumer, the one AndroidX type Google Play billing's callbacks use, so
 * the app needs neither AndroidX nor the Kotlin standard library it now brings. Same name and
 * signature as the original, so billing's own code calls it unchanged.
 */
@FunctionalInterface
public interface Consumer<T> {
    void accept(T t);
}
