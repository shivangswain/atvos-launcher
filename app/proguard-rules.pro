# Google Play billing is used without the libraries it would bring along (see build.gradle.kts):
# its references to them are to annotations, its own optional logging, and the alternative-billing
# screen that isn't in the manifest, none of which run.
-dontwarn androidx.activity.**
-dontwarn androidx.annotation.**
-dontwarn com.google.android.datatransport.**
-dontwarn com.google.android.gms.common.annotation.**
