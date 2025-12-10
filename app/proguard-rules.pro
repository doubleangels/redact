# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep line numbers for crash reporting
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep application class and activities
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep custom application class
-keep class com.doubleangels.redact.RedactApplication { *; }

# Keep all activities
-keep class com.doubleangels.redact.**Activity { *; }

# Keep all classes in metadata package (uses reflection)
-keep class com.doubleangels.redact.metadata.** { *; }

# Keep all classes in media package
-keep class com.doubleangels.redact.media.** { *; }

# Keep all classes in permission package
-keep class com.doubleangels.redact.permission.** { *; }

# Keep all classes in ui package
-keep class com.doubleangels.redact.ui.** { *; }

# Firebase Crashlytics
-keep public class * extends java.lang.Exception
-keep class com.google.firebase.crashlytics.** { *; }
-dontwarn com.google.firebase.crashlytics.**

# Firebase Analytics
-keep class com.google.android.gms.measurement.** { *; }
-keep class com.google.firebase.analytics.** { *; }
-dontwarn com.google.firebase.analytics.**

# Firebase Performance
-keep class com.google.firebase.perf.** { *; }
-dontwarn com.google.firebase.perf.**

# AndroidX Activity and Fragment
-keep class androidx.activity.** { *; }
-keep class androidx.fragment.** { *; }
-keep class androidx.appcompat.** { *; }

# ConstraintLayout
-keep class androidx.constraintlayout.** { *; }

# ExifInterface - Keep for reflection access
-keep class androidx.exifinterface.media.ExifInterface { *; }
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static final java.lang.String TAG_*;
}

# Material Components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Glide
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

# MediaCodec, MediaExtractor, MediaMuxer - Keep for video processing
-keep class android.media.MediaCodec { *; }
-keep class android.media.MediaExtractor { *; }
-keep class android.media.MediaMuxer { *; }
-keep class android.media.MediaFormat { *; }
-keep class android.media.MediaMetadataRetriever { *; }
-keepclassmembers class android.media.MediaMetadataRetriever {
    public static final int METADATA_KEY_*;
}

# FileProvider
-keep class androidx.core.content.FileProvider { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Keep ViewBinding generated classes
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(...);
}

# Keep R classes
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Remove logging in release builds (optional)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}