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

# Strip verbose logging in release (warn/error kept).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-repackageclasses
