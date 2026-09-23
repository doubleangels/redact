# ============================================================
# Redact ProGuard Rules
# ============================================================

# ---------------------------------------------------------------------------
# Crash-reporting attributes — preserve stack traces for Sentry symbolication
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Rename the SourceFile attribute to keep stack traces readable in Sentry
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Android framework entry-points
# ---------------------------------------------------------------------------
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ---------------------------------------------------------------------------
# App activities/fragments/application class — no keep needed. RedactApplication,
# MainActivity, and ShareHandlerActivity are already covered by the generic
# "extends Application/Activity" rules above (and by AGP's own automatic
# manifest-component keep rules); CleanFragment/ConvertFragment/ScanFragment
# aren't referenced from any XML or by reflection anywhere in this app
# (verified: they're only ever constructed directly with `new`), so R8 keeps
# them present through normal reachability while renaming their members.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# UI / ViewModel
# ---------------------------------------------------------------------------
# ViewModelProvider resolves a ViewModel's constructor reflectively; keep just
# the constructors it needs. (ScanViewModel needs no rule here at all: it has
# never had one and already works, confirming AndroidX Lifecycle's own bundled
# consumer rules already protect ViewModel constructors app-wide — this rule
# is extra insurance for MainViewModel specifically, not a functional need.)
-keepclassmembers class com.doubleangels.redact.ui.MainViewModel {
    <init>(...);
}
# UIStateManager is only ever constructed directly with `new`; no keep needed.

# ---------------------------------------------------------------------------
# Metadata package — no keep needed: these classes are called directly (not
# via reflection or XML) and are reachable from the kept fragments/activities
# above, so R8 keeps them present while still renaming/shrinking their
# members. The reflection this app does elsewhere is on ExifInterface's own
# TAG_ fields and MediaMetadataRetriever's own METADATA_KEY_ fields, which
# are kept separately below/above on those external classes.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Sentry — keep the SDK itself (official recommendation, needed for stack
# trace symbolication). The app's own sentry package (SentryInitializer,
# SentryManager, SentryPrivacyScrubber) needs no keep: BeforeSend/breadcrumb
# callbacks are registered as inline lambdas that call these classes'
# methods directly, not via reflection or a Sentry-side name lookup.
# ---------------------------------------------------------------------------
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# ---------------------------------------------------------------------------
# AndroidX core libraries
# ---------------------------------------------------------------------------
-keep class androidx.activity.** { *; }
-keep class androidx.fragment.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class androidx.constraintlayout.** { *; }
-keep class androidx.core.content.FileProvider { *; }
-keep class androidx.viewbinding.** { *; }

# ExifInterface — TAG_ string constants accessed reflectively
-keep class androidx.exifinterface.media.ExifInterface { *; }
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static final java.lang.String TAG_*;
}

# ---------------------------------------------------------------------------
# Material Components
# ---------------------------------------------------------------------------
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ---------------------------------------------------------------------------
# Glide — official recommended rules
# ---------------------------------------------------------------------------
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class com.bumptech.glide.load.data.ParcelFileDescriptorRewinder$InternalRewinder {
    *** rewind();
}
-dontwarn com.bumptech.glide.**

# ---------------------------------------------------------------------------
# Jetpack Media3 Transformer / Muxer — keep codec negotiation classes
# ---------------------------------------------------------------------------
-keep,allowshrinking class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Android media classes used directly
-keep class android.media.MediaCodec { *; }
-keep class android.media.MediaExtractor { *; }
-keep class android.media.MediaMuxer { *; }
-keep class android.media.MediaFormat { *; }
-keep class android.media.MediaMetadataRetriever { *; }
-keepclassmembers class android.media.MediaMetadataRetriever {
    public static final int METADATA_KEY_*;
}

# ---------------------------------------------------------------------------
# JVM / Android boilerplate
# ---------------------------------------------------------------------------

# Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Parcelable
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ViewBinding generated classes
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(...);
}

# R resource fields
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# Strip verbose debug/info logging in release (preserves warn/error)
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
# ---------------------------------------------------------------------------
# Apache Commons Imaging
# ---------------------------------------------------------------------------
-keep class org.apache.commons.imaging.** { *; }
-dontwarn org.apache.commons.imaging.**

# ---------------------------------------------------------------------------
# Advanced Optimizations
# ---------------------------------------------------------------------------
-optimizationpasses 5
-repackageclasses
