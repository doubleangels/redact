package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class AppPreferencesTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication().getApplicationContext();
        context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void qualityForLossyFormat_returnsPresetAndFormatSpecificQuality() {
        assertEquals(92, AppPreferences.qualityForLossyFormat(
                AppPreferences.QUALITY_PRESET_HIGH, false));
        assertEquals(90, AppPreferences.qualityForLossyFormat(
                AppPreferences.QUALITY_PRESET_HIGH, true));
        assertEquals(85, AppPreferences.qualityForLossyFormat(
                AppPreferences.QUALITY_PRESET_BALANCED, false));
        assertEquals(80, AppPreferences.qualityForLossyFormat(
                AppPreferences.QUALITY_PRESET_BALANCED, true));
        assertEquals(75, AppPreferences.qualityForLossyFormat(
                AppPreferences.QUALITY_PRESET_SMALLER, false));
        assertEquals(70, AppPreferences.qualityForLossyFormat(
                AppPreferences.QUALITY_PRESET_SMALLER, true));
    }

    @Test
    public void clampFormatIndex_clampsOutOfRangeValues() {
        assertEquals(0, AppPreferences.clampFormatIndex(-1));
        assertEquals(0, AppPreferences.clampFormatIndex(0));
        assertEquals(3, AppPreferences.clampFormatIndex(3));
        assertEquals(3, AppPreferences.clampFormatIndex(99));
    }

    @Test
    public void imageQualityPreset_defaultsToHighAndClampsOnWrite() {
        assertEquals(AppPreferences.QUALITY_PRESET_HIGH, AppPreferences.getImageQualityPreset(context));
        AppPreferences.setImageQualityPreset(context, AppPreferences.QUALITY_PRESET_SMALLER);
        assertEquals(AppPreferences.QUALITY_PRESET_SMALLER, AppPreferences.getImageQualityPreset(context));
        AppPreferences.setImageQualityPreset(context, 99);
        assertEquals(AppPreferences.QUALITY_PRESET_SMALLER, AppPreferences.getImageQualityPreset(context));
        AppPreferences.setImageQualityPreset(context, -5);
        assertEquals(AppPreferences.QUALITY_PRESET_HIGH, AppPreferences.getImageQualityPreset(context));
    }

    @Test
    public void secureDeletePasses_acceptsOnlyConfiguredValues() {
        assertEquals(3, AppPreferences.getSecureDeletePasses(context));
        AppPreferences.setSecureDeletePasses(context, 1);
        assertEquals(1, AppPreferences.getSecureDeletePasses(context));
        AppPreferences.setSecureDeletePasses(context, 7);
        assertEquals(7, AppPreferences.getSecureDeletePasses(context));
        AppPreferences.setSecureDeletePasses(context, 5);
        assertEquals(3, AppPreferences.getSecureDeletePasses(context));
        AppPreferences.setSecureDeletePasses(context, 0);
        assertEquals(3, AppPreferences.getSecureDeletePasses(context));
    }

    @Test
    public void maxBitmapSize_defaultsAndClampsOnWrite() {
        AppPreferences.setMaxBitmapSize(context, 2048);
        assertEquals(2048, AppPreferences.getMaxBitmapSize(context));
        AppPreferences.setMaxBitmapSize(context, 8192);
        assertEquals(8192, AppPreferences.getMaxBitmapSize(context));
        AppPreferences.setMaxBitmapSize(context, 1000);
        assertEquals(4096, AppPreferences.getMaxBitmapSize(context));
    }

    @Test
    public void defaultImageFormatIndex_defaultsToJpegAndClamps() {
        assertEquals(AppPreferences.FORMAT_INDEX_JPEG_H264, AppPreferences.getDefaultImageFormatIndex(context));
        AppPreferences.setDefaultImageFormatIndex(context, AppPreferences.FORMAT_INDEX_PNG_H265);
        assertEquals(AppPreferences.FORMAT_INDEX_PNG_H265, AppPreferences.getDefaultImageFormatIndex(context));
        AppPreferences.setDefaultImageFormatIndex(context, 99);
        assertEquals(AppPreferences.FORMAT_INDEX_HEIC_AV1, AppPreferences.getDefaultImageFormatIndex(context));
        assertEquals(AppPreferences.FORMAT_INDEX_JPEG_H264, AppPreferences.getDefaultVideoFormatIndex(context));
    }

    @Test
    public void booleanPreferences_defaultFalseAndRoundTrip() {
        Application app = RuntimeEnvironment.getApplication();
        assertFalse(AppPreferences.areNotificationsEnabled(app));
        assertFalse(AppPreferences.areCleanNotificationsEnabled(app));
        assertFalse(AppPreferences.areConvertNotificationsEnabled(app));
        assertFalse(AppPreferences.areProgressNotificationsEnabled(app));
        assertFalse(AppPreferences.isCrashReportingEnabled(app));
        assertFalse(AppPreferences.isShareConfirmBeforeStrip(app));
        assertFalse(AppPreferences.isPreserveCameraSettings(app));
        assertFalse(AppPreferences.isPreserveLocation(app));
        assertFalse(AppPreferences.isVideoFallbackCopy(app));

        AppPreferences.setNotificationsEnabled(app, true);
        AppPreferences.setCleanNotificationsEnabled(app, true);
        AppPreferences.setConvertNotificationsEnabled(app, true);
        AppPreferences.setProgressNotificationsEnabled(app, true);
        AppPreferences.setCrashReportingEnabled(app, true);
        AppPreferences.setShareConfirmBeforeStrip(app, true);
        AppPreferences.setPreserveCameraSettings(app, true);
        AppPreferences.setPreserveLocation(app, true);
        AppPreferences.setVideoFallbackCopy(app, true);

        assertTrue(AppPreferences.areNotificationsEnabled(app));
        assertTrue(AppPreferences.areCleanNotificationsEnabled(app));
        assertTrue(AppPreferences.areConvertNotificationsEnabled(app));
        assertTrue(AppPreferences.areProgressNotificationsEnabled(app));
        assertTrue(AppPreferences.isCrashReportingEnabled(app));
        assertTrue(AppPreferences.isShareConfirmBeforeStrip(app));
        assertTrue(AppPreferences.isPreserveCameraSettings(app));
        assertTrue(AppPreferences.isPreserveLocation(app));
        assertTrue(AppPreferences.isVideoFallbackCopy(app));
    }

    @Test
    public void progressNotifications_requireMasterSwitch() {
        AppPreferences.setNotificationsEnabled(context, true);
        AppPreferences.setProgressNotificationsEnabled(context, true);
        assertTrue(AppPreferences.areProgressNotificationsEnabled(context));

        AppPreferences.setNotificationsEnabled(context, false);
        assertFalse(AppPreferences.areProgressNotificationsEnabled(context));
    }

    @Test
    public void strictClean_defaultsToTrueAndPreserveDefaultsMatchApp() {
        assertTrue(AppPreferences.isStrictClean(context));
        assertTrue(AppPreferences.isAutoClearTempFiles(context));
        AppPreferences.setStrictClean(context, false);
        assertFalse(AppPreferences.isStrictClean(context));
    }

    @Test
    public void initialPermissionsPrompt_tracksCompletion() {
        assertFalse(AppPreferences.hasCompletedInitialPermissionsPrompt(context));
        AppPreferences.setInitialPermissionsPromptCompleted(context);
        assertTrue(AppPreferences.hasCompletedInitialPermissionsPrompt(context));
    }
}