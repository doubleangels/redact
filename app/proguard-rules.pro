# Defaults come from proguard-android-optimize.txt (native methods, Parcelable, enums, Serializable,
# R fields, view constructors); AGP keeps manifest components; Sentry, Glide, Material, AndroidX and
# Media3 ship their own consumer rules. Only app-specific needs live here.

# Readable Sentry stack traces (mapping file is uploaded for deobfuscation).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# MetadataDisplayer/MetadataStripper enumerate ExifInterface.TAG_* fields by reflection and use
# the field names, so they must not be renamed.
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static final java.lang.String TAG_*;
}

# Every level, in app and library code: a warning or error often carries an exception whose
# message holds a path, URI or file name, and logcat ends up in bug reports users share. Debug
# builds are not minified and keep full logs. Opted-in crash reports go through SentryManager.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
    public static *** println(...);
}

-repackageclasses
