package com.doubleangels.redact.ui;

import androidx.annotation.NonNull;
import androidx.annotation.RawRes;

import com.doubleangels.redact.R;

import java.util.List;

/**
 * The open-source libraries shipped in the app, shown on Settings › Open-Source Licenses.
 * Every {@code implementation} dependency in app/build.gradle must be covered by an entry here;
 * OpenSourceLicensesTest fails when one is added without being credited. Debug-only libraries
 * such as LeakCanary are left out because they never ship.
 */
public final class OpenSourceLicenses {

    private static final String APACHE_2_0 = "Apache License 2.0";

    /** One credited library, which may span several Maven artifacts. */
    public record Entry(@NonNull String name, @NonNull List<String> artifacts,
            @NonNull String licenseName, @RawRes int licenseText, @NonNull String url) {}

    public static final List<Entry> ENTRIES = List.of(
            new Entry("AndroidX Activity", List.of("androidx.activity:activity"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/activity"),
            new Entry("AndroidX AppCompat", List.of("androidx.appcompat:appcompat"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/appcompat"),
            new Entry("AndroidX ConstraintLayout",
                    List.of("androidx.constraintlayout:constraintlayout"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/constraintlayout"),
            new Entry("AndroidX Core SplashScreen", List.of("androidx.core:core-splashscreen"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/core"),
            new Entry("AndroidX ExifInterface", List.of("androidx.exifinterface:exifinterface"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/exifinterface"),
            new Entry("AndroidX Fragment", List.of("androidx.fragment:fragment"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/fragment"),
            new Entry("AndroidX Lifecycle", List.of("androidx.lifecycle:lifecycle-viewmodel-ktx"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
            new Entry("AndroidX Media3",
                    List.of("androidx.media3:media3-common", "androidx.media3:media3-effect",
                            "androidx.media3:media3-transformer"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://github.com/androidx/media"),
            new Entry("AndroidX ProfileInstaller", List.of("androidx.profileinstaller:profileinstaller"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://developer.android.com/jetpack/androidx/releases/profileinstaller"),
            new Entry("Glide", List.of("com.github.bumptech.glide:glide"),
                    "BSD 2-Clause, MIT, Apache 2.0", R.raw.license_glide,
                    "https://github.com/bumptech/glide"),
            new Entry("Material Components for Android",
                    List.of("com.google.android.material:material"),
                    APACHE_2_0, R.raw.license_apache_2_0,
                    "https://github.com/material-components/material-components-android"),
            new Entry("Sentry Android",
                    List.of("io.sentry:sentry-android", "io.sentry:sentry-android-fragment"),
                    "MIT License", R.raw.license_mit,
                    "https://github.com/getsentry/sentry-java"));

    private OpenSourceLicenses() {}
}
