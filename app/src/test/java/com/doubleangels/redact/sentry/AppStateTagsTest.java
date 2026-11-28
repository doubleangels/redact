package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.usage.UsageStatsManager;
import android.net.Uri;

import com.doubleangels.redact.AppPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Map;
import java.util.regex.Pattern;

import io.sentry.SentryEvent;

@RunWith(RobolectricTestRunner.class)
public class AppStateTagsTest {

    /** A value that could carry a name, path or free text would not match this. */
    private static final Pattern FIXED_WORD = Pattern.compile("[a-z0-9_]{1,24}");

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryManager.init(app);
    }

    @After
    public void tearDown() {
        AppPreferences.setCrashReportingEnabled(app, false);
    }

    @Test
    public void everyTag_isAFixedWordOrSmallNumber_andNoneIsBlankedByTheScrubber() {
        AppPreferences.setOutputTree(app, false,
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AHoliday%20Alice"));

        Map<String, String> tags = AppStateTags.collect(app);

        assertTrue(tags.size() >= 20);
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            assertTrue(tag.getKey() + "=" + tag.getValue(), FIXED_WORD.matcher(tag.getValue()).matches());
            SentryEvent event = new SentryEvent();
            event.setTag(tag.getKey(), tag.getValue());
            SentryPrivacyScrubber.scrubEvent(event);
            assertEquals("the scrubber keeps " + tag.getKey(), tag.getValue(), event.getTag(tag.getKey()));
        }
        assertEquals("only whether a folder was chosen, never which", "chosen", tags.get("setting.image_folder"));
        assertEquals("default", tags.get("setting.video_folder"));
    }

    @Test
    public void settings_followThePreferences() {
        AppPreferences.setStrictClean(app, true);
        AppPreferences.setPreserveLocation(app, true);
        AppPreferences.setApproximateLocation(app, true);

        Map<String, String> tags = AppStateTags.collect(app);

        assertEquals("true", tags.get("setting.strict_clean"));
        assertEquals("approximate", tags.get("setting.keep_gps"));
        assertEquals("off", tags.get("setting.keep_date_taken"));
        assertEquals("standard", tags.get("setting.clean_preset"));

        AppPreferences.setStrictClean(app, false);
        assertEquals("custom", AppStateTags.collect(app).get("setting.clean_preset"));
    }

    @Test
    public void applyAppState_addsTheTags_withoutReplacingOnesTheEventHas() {
        SentryEvent event = new SentryEvent();
        event.setTag("setting.strict_clean", "kept");

        SentryManager.applyAppState(event);

        assertEquals("kept", event.getTag("setting.strict_clean"));
        assertEquals(AppStateTags.collect(app).get("device.low_ram"), event.getTag("device.low_ram"));
        SentryManager.applyAppState(null);
    }

    @Test
    public void collect_withoutAContext_isEmpty() {
        assertTrue(AppStateTags.collect(null).isEmpty());
    }

    @Test
    public void installer_isOneOfAFewWords_neverAnotherAppsName() {
        assertEquals("play", AppStateTags.installerClass("com.android.vending"));
        assertEquals("store_other", AppStateTags.installerClass("org.fdroid.fdroid"));
        assertEquals("sideloaded", AppStateTags.installerClass("com.google.android.packageinstaller"));
        assertEquals("adb", AppStateTags.installerClass("com.android.shell"));
        assertEquals("none", AppStateTags.installerClass(null));
        assertEquals("other", AppStateTags.installerClass("com.example.someones.private.store"));
        assertFalse(AppStateTags.installer(app).contains("."));
    }

    @Test
    public void standbyBucket_namesEachBucket() {
        assertEquals("active", AppStateTags.standbyBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE));
        assertEquals("working_set", AppStateTags.standbyBucket(UsageStatsManager.STANDBY_BUCKET_WORKING_SET));
        assertEquals("frequent", AppStateTags.standbyBucket(UsageStatsManager.STANDBY_BUCKET_FREQUENT));
        assertEquals("rare", AppStateTags.standbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE));
        assertEquals("restricted", AppStateTags.standbyBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED));
        assertEquals("other", AppStateTags.standbyBucket(99));
    }

    @Test
    public void frameRateClass_bandsTheRate() {
        assertEquals("unknown", SentryManager.frameRateClass(0));
        assertEquals("up_to_30", SentryManager.frameRateClass(30));
        assertEquals("up_to_60", SentryManager.frameRateClass(60));
        assertEquals("above_60", SentryManager.frameRateClass(120));
    }
}
