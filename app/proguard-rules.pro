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
# App fragments and activities (referenced by name in XML / navigation)
# ---------------------------------------------------------------------------
-keep class com.doubleangels.redact.RedactApplication { *; }
-keep class com.doubleangels.redact.MainActivity { *; }
-keep class com.doubleangels.redact.ShareHandlerActivity { *; }
-keep class com.doubleangels.redact.CleanFragment { *; }
-keep class com.doubleangels.redact.ConvertFragment { *; }
-keep class com.doubleangels.redact.ScanFragment { *; }

# ---------------------------------------------------------------------------
# UI / ViewModel — keep public API, allow internal member obfuscation
# ---------------------------------------------------------------------------
-keep class com.doubleangels.redact.ui.MainViewModel { *; }
-keep class com.doubleangels.redact.ui.UIStateManager { *; }

# ---------------------------------------------------------------------------
# Media package — public API called across package boundaries
# ---------------------------------------------------------------------------
-keep class com.doubleangels.redact.media.MediaItem { *; }
-keep class com.doubleangels.redact.media.MediaSelector { *; }
-keep class com.doubleangels.redact.media.MediaProcessor { *; }
-keep class com.doubleangels.redact.media.MediaAdapter { *; }
-keep class com.doubleangels.redact.media.ConvertFileAdapter { *; }
# FormatConverter and VideoMedia3Converter are accessed via static methods
-keep class com.doubleangels.redact.media.FormatConverter { *; }
-keep class com.doubleangels.redact.media.VideoMedia3Converter { *; }

# ---------------------------------------------------------------------------
# Metadata package — uses reflection internally (ExifInterface TAG_ constants)
# ---------------------------------------------------------------------------
-keep class com.doubleangels.redact.metadata.MetadataStripper { *; }
-keep class com.doubleangels.redact.metadata.MetadataDisplayer { *; }
-keep class com.doubleangels.redact.metadata.XmlLikeMetadataFormatter { *; }

# ---------------------------------------------------------------------------
# Sentry — keep SDK + BeforeSend lambda + breadcrumb infrastructure
# ---------------------------------------------------------------------------
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**
-keep class com.doubleangels.redact.sentry.** { *; }

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
-keep class androidx.media3.** { *; }
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