package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AppPreferencesTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply();
    }

    @Test
    public void defaults() {
        assertFalse(AppPreferences.areNotificationsEnabled(context));
        assertFalse(AppPreferences.areCleanNotificationsEnabled(context));
        assertFalse(AppPreferences.areConvertNotificationsEnabled(context));
        assertTrue(AppPreferences.areProgressNotificationsEnabled(context));
        assertFalse(AppPreferences.isCrashReportingEnabled(context));
        assertEquals(AppPreferences.FORMAT_INDEX_JPEG_H264, AppPreferences.getDefaultImageFormatIndex(context));
        assertEquals(AppPreferences.FORMAT_INDEX_JPEG_H264, AppPreferences.getDefaultVideoFormatIndex(context));
        assertEquals(AppPreferences.QUALITY_PRESET_HIGH, AppPreferences.getImageQualityPreset(context));
        assertFalse(AppPreferences.isShareConfirmBeforeStrip(context));
        
        // Advanced Settings defaults
        assertEquals(3, AppPreferences.getSecureDeletePasses(context));
        assertEquals(4096, AppPreferences.getMaxBitmapSize(context));
        assertEquals(100, AppPreferences.getMaxImageFileSizeMb(context));
        assertFalse(AppPreferences.isAutoClearTempFiles(context));
        assertFalse(AppPreferences.isStrictClean(context));
        assertFalse(AppPreferences.isPreserveLocation(context));
        assertFalse(AppPreferences.isPreserveCameraSettings(context));
    }

    @Test
    public void notificationAndCrashPrefsRoundTrip() {
        AppPreferences.setNotificationsEnabled(context, true);
        AppPreferences.setCleanNotificationsEnabled(context, true);
        AppPreferences.setConvertNotificationsEnabled(context, true);
        AppPreferences.setProgressNotificationsEnabled(context, false);
        AppPreferences.setCrashReportingEnabled(context, true);
        AppPreferences.setShareConfirmBeforeStrip(context, true);

        assertTrue(AppPreferences.areNotificationsEnabled(context));
        assertTrue(AppPreferences.areCleanNotificationsEnabled(context));
        assertTrue(AppPreferences.areConvertNotificationsEnabled(context));
        assertFalse(AppPreferences.areProgressNotificationsEnabled(context));
        assertTrue(AppPreferences.isCrashReportingEnabled(context));
        assertTrue(AppPreferences.isShareConfirmBeforeStrip(context));
    }

    @Test
    public void advancedPrefsRoundTrip() {
        AppPreferences.setSecureDeletePasses(context, 7);
        AppPreferences.setMaxBitmapSize(context, 4096);
        AppPreferences.setMaxImageFileSizeMb(context, 50);
        AppPreferences.setAutoClearTempFiles(context, true);
        AppPreferences.setStrictClean(context, true);
        AppPreferences.setPreserveLocation(context, true);
        AppPreferences.setPreserveCameraSettings(context, true);

        assertEquals(7, AppPreferences.getSecureDeletePasses(context));
        assertEquals(4096, AppPreferences.getMaxBitmapSize(context));
        assertEquals(50, AppPreferences.getMaxImageFileSizeMb(context));
        assertTrue(AppPreferences.isAutoClearTempFiles(context));
        assertTrue(AppPreferences.isStrictClean(context));
        assertTrue(AppPreferences.isPreserveLocation(context));
        assertTrue(AppPreferences.isPreserveCameraSettings(context));
    }

    @Test
    public void clampFormatIndex_bounds() {
        assertEquals(AppPreferences.FORMAT_INDEX_JPEG_H264, AppPreferences.clampFormatIndex(-1));
        assertEquals(AppPreferences.FORMAT_INDEX_HEIC_AV1, AppPreferences.clampFormatIndex(99));
        assertEquals(AppPreferences.FORMAT_INDEX_WEBP_VP9, AppPreferences.clampFormatIndex(2));
    }

    @Test
    public void setDefaultFormatIndices_clampOnWrite() {
        AppPreferences.setDefaultImageFormatIndex(context, -5);
        AppPreferences.setDefaultVideoFormatIndex(context, 10);
        assertEquals(AppPreferences.FORMAT_INDEX_JPEG_H264, AppPreferences.getDefaultImageFormatIndex(context));
        assertEquals(AppPreferences.FORMAT_INDEX_HEIC_AV1, AppPreferences.getDefaultVideoFormatIndex(context));
    }

    @Test
    public void imageQualityPreset_clampsAndInvalidStored() {
        AppPreferences.setImageQualityPreset(context, -1);
        assertEquals(AppPreferences.QUALITY_PRESET_HIGH, AppPreferences.getImageQualityPreset(context));

        AppPreferences.setImageQualityPreset(context, 99);
        assertEquals(AppPreferences.QUALITY_PRESET_SMALLER, AppPreferences.getImageQualityPreset(context));

        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("image_quality_preset", 42).apply();
        assertEquals(AppPreferences.QUALITY_PRESET_HIGH, AppPreferences.getImageQualityPreset(context));

        AppPreferences.setImageQualityPreset(context, AppPreferences.QUALITY_PRESET_BALANCED);
        assertEquals(AppPreferences.QUALITY_PRESET_BALANCED, AppPreferences.getImageQualityPreset(context));
    }

    @Test
    public void qualityForLossyFormat_allPresets() {
        assertEquals(92, AppPreferences.qualityForLossyFormat(AppPreferences.QUALITY_PRESET_HIGH, false));
        assertEquals(90, AppPreferences.qualityForLossyFormat(AppPreferences.QUALITY_PRESET_HIGH, true));
        assertEquals(85, AppPreferences.qualityForLossyFormat(AppPreferences.QUALITY_PRESET_BALANCED, false));
        assertEquals(80, AppPreferences.qualityForLossyFormat(AppPreferences.QUALITY_PRESET_BALANCED, true));
        assertEquals(75, AppPreferences.qualityForLossyFormat(AppPreferences.QUALITY_PRESET_SMALLER, false));
        assertEquals(70, AppPreferences.qualityForLossyFormat(AppPreferences.QUALITY_PRESET_SMALLER, true));
        assertEquals(92, AppPreferences.qualityForLossyFormat(999, false));
    }
}
