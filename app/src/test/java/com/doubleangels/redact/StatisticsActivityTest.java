package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.metadata.CleanStats;
import com.doubleangels.redact.stats.StatsStore;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.text.DateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class StatisticsActivityTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final long STARTED_MS = TODAY.minusDays(40)
            .atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

    /** Every language the app ships, as resource qualifiers. */
    private static final String[] LOCALES = {
            "en", "ar", "de", "es", "fr", "hi", "it", "ja", "ko", "pt", "ru", "zh-rCN", "zh-rTW"};

    private Context context;
    private ActivityController<StatisticsActivity> controller;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        StatsStore.setClockForTests(() -> TODAY, () -> STARTED_MS);
        StatsStore.clear(context);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        StatsStore.clear(context);
        StatsStore.resetClockForTests();
        AppPreferences.resetAll(context);
    }

    private StatisticsActivity launch() {
        controller = Robolectric.buildActivity(StatisticsActivity.class).setup();
        return controller.get();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static String text(StatisticsActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }

    private static String tile(StatisticsActivity activity, int tileId) {
        return ((TextView) activity.findViewById(tileId).findViewById(R.id.statValue)).getText().toString();
    }

    private static String tileLabel(StatisticsActivity activity, int tileId) {
        return ((TextView) activity.findViewById(tileId).findViewById(R.id.statLabel)).getText().toString();
    }

    private static String percent(StatisticsActivity activity, int rowId) {
        return ((TextView) activity.findViewById(rowId).findViewById(R.id.statPercent)).getText().toString();
    }

    private static int bar(StatisticsActivity activity, int rowId) {
        return ((LinearProgressIndicator) activity.findViewById(rowId).findViewById(R.id.statBar)).getProgress();
    }

    private AlertDialog openClearDialog(StatisticsActivity activity) {
        activity.findViewById(R.id.rowClearStats).performClick();
        idle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    // ---- what it shows ----------------------------------------------------------------------------

    @Test
    public void showsEveryTotal() {
        StatsStore.recordClean(context, new CleanStats(1200, true, true, true, false), false, false);
        StatsStore.recordClean(context, new CleanStats(84, false, true, false, true), true, true);
        StatsStore.recordShare(context);
        StatsStore.recordConverted(context);
        StatsStore.recordConverted(context);

        StatisticsActivity activity = launch();

        assertEquals("1,284", text(activity, R.id.statsFieldsValue));
        assertEquals("1", text(activity, R.id.statsLocationsValue));
        assertEquals("2", text(activity, R.id.statsFilesValue));
        assertEquals("1", tile(activity, R.id.rowLocations));
        assertEquals("50%", percent(activity, R.id.rowLocations));
        assertEquals("2", tile(activity, R.id.rowDates));
        assertEquals("100%", percent(activity, R.id.rowDates));
        assertEquals("1", tile(activity, R.id.rowDevices));
        assertEquals("1", tile(activity, R.id.rowNames));
        assertEquals("1", tile(activity, R.id.tilePhotos));
        assertEquals("1", tile(activity, R.id.tileVideos));
        assertEquals("2", tile(activity, R.id.tileConverted));
        assertEquals("2 files cleaned in the last 30 days.", text(activity, R.id.statsChartSummary));
        assertEquals("2 files cleaned in the last 30 days.",
                activity.findViewById(R.id.statsChart).getContentDescription().toString());
    }

    @Test
    public void everyTileHasItsOwnLabelAndIcon() {
        StatisticsActivity activity = launch();

        int[] tiles = {R.id.rowLocations, R.id.rowDates, R.id.rowDevices, R.id.rowNames,
                R.id.tilePhotos, R.id.tileVideos, R.id.tileConverted};
        int[] labels = {R.string.stats_removed_location, R.string.stats_removed_dates,
                R.string.stats_removed_devices, R.string.stats_removed_names, R.string.stats_photos,
                R.string.stats_videos, R.string.stats_files_converted};
        for (int i = 0; i < tiles.length; i++) {
            assertEquals(activity.getString(labels[i]), tileLabel(activity, tiles[i]));
            assertNotNull(((android.widget.ImageView) activity.findViewById(tiles[i])
                    .findViewById(R.id.statIcon)).getDrawable());
        }
    }

    @Test
    public void whatWasRemoved_showsEachShareAsAWholePercentageAndABar() {
        StatsStore.recordClean(context, new CleanStats(4, true, true, false, false), false, false);
        StatsStore.recordClean(context, new CleanStats(4, false, true, false, false), false, false);
        StatsStore.recordClean(context, new CleanStats(4, false, false, false, false), true, false);

        StatisticsActivity activity = launch();

        assertEquals("33%", percent(activity, R.id.rowLocations));
        assertEquals(33, bar(activity, R.id.rowLocations));
        assertEquals("67%", percent(activity, R.id.rowDates));
        assertEquals(67, bar(activity, R.id.rowDates));
        assertEquals("0", tile(activity, R.id.rowNames));
        assertEquals("0%", percent(activity, R.id.rowNames));
        assertEquals(0, bar(activity, R.id.rowNames));
    }

    @Test
    public void heroShowsTheSameFiguresAsTheSettingsCard() {
        StatisticsActivity activity = launch();

        int[] values = {R.id.statsFieldsValue, R.id.statsLocationsValue, R.id.statsFilesValue};
        int[] labels = {R.string.stats_fields_removed, R.string.stats_locations_removed,
                R.string.stats_files_cleaned};
        for (int i = 0; i < values.length; i++) {
            android.view.ViewGroup figure = (android.view.ViewGroup) activity.findViewById(values[i]).getParent();
            assertEquals(activity.getString(labels[i]), ((TextView) figure.getChildAt(1)).getText().toString());
        }
    }

    @Test
    public void heroSaysWhenCountingStarted() {
        StatsStore.recordShare(context);

        StatisticsActivity activity = launch();

        String date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(STARTED_MS));
        assertEquals(activity.getString(R.string.stats_settings_since, date), text(activity, R.id.statsHeroLabel));
        assertEquals("Counted on this device since Sep 8, 2026.", text(activity, R.id.statsHeroLabel));
    }

    @Test
    public void theChartStartsTwentyNineDaysAgo() {
        StatisticsActivity activity = launch();

        assertEquals("Sep 19", text(activity, R.id.statsChartStart));
    }

    @Test
    public void oneFile_usesTheSingular() {
        StatsStore.recordClean(context, new CleanStats(3, false), false, false);

        StatisticsActivity activity = launch();

        assertEquals("1 file cleaned in the last 30 days.", text(activity, R.id.statsChartSummary));
    }

    @Test
    public void cleansOlderThanThirtyDays_countInTheTotalsButNotTheChart() {
        StatsStore.setClockForTests(() -> TODAY.minusDays(45), () -> STARTED_MS);
        StatsStore.recordClean(context, new CleanStats(10, true), false, false);
        StatsStore.setClockForTests(() -> TODAY, () -> STARTED_MS);

        StatisticsActivity activity = launch();

        assertEquals("1", text(activity, R.id.statsFilesValue));
        assertEquals(activity.getString(R.string.stats_chart_empty), text(activity, R.id.statsChartSummary));
    }

    @Test
    public void bigNumbersAreGroupedInFull() {
        context.getSharedPreferences("redact_stats", Context.MODE_PRIVATE).edit()
                .putLong("fields_removed", 1_234_567_890L)
                .putLong("files_cleaned_images", 9_876_543L)
                .putLong("tracking_since", STARTED_MS)
                .commit();

        StatisticsActivity activity = launch();

        assertEquals("1,234,567,890", text(activity, R.id.statsFieldsValue));
        assertEquals("9,876,543", text(activity, R.id.statsFilesValue));
    }

    @Test
    public void withNothingCounted_explainsHowToStart() {
        StatisticsActivity activity = launch();

        assertEquals("0", text(activity, R.id.statsFieldsValue));
        assertEquals(activity.getString(R.string.stats_settings_empty), text(activity, R.id.statsHeroLabel));
        assertEquals("0", text(activity, R.id.statsFilesValue));
        assertEquals("0", tile(activity, R.id.tileConverted));
        assertEquals(activity.getString(R.string.stats_chart_empty), text(activity, R.id.statsChartSummary));
        assertEquals(activity.getString(R.string.stats_chart_empty),
                activity.findViewById(R.id.statsChart).getContentDescription().toString());
    }

    @Test
    public void toolbarIsTitledAndGoesBack() {
        StatisticsActivity activity = launch();
        MaterialToolbar toolbar = activity.findViewById(R.id.statsToolbar);

        assertEquals(activity.getString(R.string.stats_title), toolbar.getTitle().toString());
        assertNotNull(toolbar.getNavigationContentDescription());

        View up = null;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            View child = toolbar.getChildAt(i);
            if (child instanceof android.widget.ImageButton) {
                up = child;
            }
        }
        assertNotNull(up);
        up.performClick();
        idle();
        assertTrue(activity.isFinishing());
    }

    // ---- keeping up to date -----------------------------------------------------------------------

    @Test
    public void comingBackToTheScreen_showsWhatWasCountedMeanwhile() {
        StatisticsActivity activity = launch();
        assertEquals("0", text(activity, R.id.statsLocationsValue));

        controller.pause().stop();
        StatsStore.recordClean(context, new CleanStats(17, true), false, true);
        StatsStore.recordShare(context);
        controller.restart().start().resume();

        assertEquals("17", text(activity, R.id.statsFieldsValue));
        assertEquals("1", text(activity, R.id.statsLocationsValue));
        assertEquals("100%", percent(activity, R.id.rowLocations));
        assertEquals("1 file cleaned in the last 30 days.", text(activity, R.id.statsChartSummary));
    }

    @Test
    public void rotating_keepsShowingTheCounts() {
        StatsStore.recordClean(context, new CleanStats(5, true), true, false);
        launch();

        controller.recreate();
        StatisticsActivity activity = controller.get();

        assertEquals("5", text(activity, R.id.statsFieldsValue));
        assertEquals("1", tile(activity, R.id.tileVideos));
    }

    // ---- clearing ---------------------------------------------------------------------------------

    @Test
    public void clearDialog_saysWhatHappens() {
        StatisticsActivity activity = launch();

        AlertDialog dialog = openClearDialog(activity);

        assertEquals(activity.getString(R.string.stats_clear_message),
                ((TextView) dialog.findViewById(android.R.id.message)).getText().toString());
        assertEquals(activity.getString(R.string.stats_clear_confirm),
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).getText().toString());
        assertEquals(activity.getString(android.R.string.cancel),
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).getText().toString());
        assertFalse("dialog titles are not questions",
                activity.getString(R.string.stats_clear_title).endsWith("?"));
    }

    @Test
    public void clear_asksFirstThenSetsEverythingToZero() {
        StatsStore.recordClean(context, new CleanStats(40, true), false, true);
        StatsStore.recordShare(context);
        StatsStore.recordConverted(context);
        StatisticsActivity activity = launch();

        AlertDialog dialog = openClearDialog(activity);
        // Nothing is cleared until the user confirms.
        assertEquals(40, StatsStore.snapshot(context).fieldsRemoved);

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        assertFalse(dialog.isShowing());
        assertTrue(StatsStore.snapshot(context).isEmpty());
        assertEquals("0", text(activity, R.id.statsFieldsValue));
        assertEquals(activity.getString(R.string.stats_settings_empty), text(activity, R.id.statsHeroLabel));
        assertEquals("0", text(activity, R.id.statsLocationsValue));
        assertEquals("0", text(activity, R.id.statsFilesValue));
        for (int id : new int[]{R.id.rowLocations, R.id.rowDates, R.id.rowDevices, R.id.rowNames}) {
            assertEquals("0", tile(activity, id));
            assertEquals("0%", percent(activity, id));
            assertEquals(0, bar(activity, id));
        }
        for (int id : new int[]{R.id.tilePhotos, R.id.tileVideos, R.id.tileConverted}) {
            assertEquals("0", tile(activity, id));
        }
        assertEquals(activity.getString(R.string.stats_chart_empty), text(activity, R.id.statsChartSummary));
        assertEquals(activity.getString(R.string.stats_cleared), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void afterClearing_countingStartsAgain() {
        StatsStore.recordClean(context, new CleanStats(40, true), false, false);
        StatisticsActivity activity = launch();
        openClearDialog(activity).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        controller.pause().stop();
        StatsStore.recordClean(context, new CleanStats(2, false), false, false);
        controller.restart().start().resume();

        assertEquals("2", text(activity, R.id.statsFieldsValue));
        assertEquals("1", text(activity, R.id.statsFilesValue));
        assertEquals("0", text(activity, R.id.statsLocationsValue));
    }

    @Test
    public void clearingWithNothingCounted_isHarmless() {
        StatisticsActivity activity = launch();

        openClearDialog(activity).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        assertTrue(StatsStore.snapshot(context).isEmpty());
        assertEquals(activity.getString(R.string.stats_cleared), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void cancel_keepsTheCounts() {
        StatsStore.recordClean(context, new CleanStats(40, true), false, false);
        StatisticsActivity activity = launch();

        openClearDialog(activity).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();

        assertEquals(40, StatsStore.snapshot(context).fieldsRemoved);
        assertEquals("40", text(activity, R.id.statsFieldsValue));
    }

    @Test
    public void clearing_keepsTheSettings() {
        AppPreferences.setSecureWindow(context, true);
        StatsStore.recordClean(context, new CleanStats(40, true), false, false);
        StatisticsActivity activity = launch();

        openClearDialog(activity).getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        assertTrue(AppPreferences.isSecureWindow(context));
    }

    // ---- privacy and appearance -------------------------------------------------------------------

    @Test
    public void followsTheHideContentSetting() {
        AppPreferences.setSecureWindow(context, true);

        StatisticsActivity activity = launch();

        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    @Test
    public void withHideContentOff_screenshotsAreAllowed() {
        AppPreferences.setSecureWindow(context, false);

        StatisticsActivity activity = launch();

        assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Test
    @Config(qualifiers = "night")
    public void opensInDarkMode() {
        StatsStore.recordClean(context, new CleanStats(9, true), false, false);

        StatisticsActivity activity = launch();

        assertEquals("9", text(activity, R.id.statsFieldsValue));
    }

    @Test
    @Config(qualifiers = "de")
    public void isTranslated() {
        StatsStore.recordClean(context, new CleanStats(1500, false), false, false);

        StatisticsActivity activity = launch();

        assertEquals("1.500", text(activity, R.id.statsFieldsValue));
        assertEquals("Kamera & Telefon", tileLabel(activity, R.id.rowDevices));
        assertEquals("1 Datei in den letzten 30 Tagen bereinigt.", text(activity, R.id.statsChartSummary));
    }

    @Test
    @Config(qualifiers = "ar")
    public void arabic_isLaidOutRightToLeft() {
        StatsStore.recordClean(context, new CleanStats(1500, true), false, false);

        StatisticsActivity activity = launch();

        assertEquals(View.LAYOUT_DIRECTION_RTL, activity.findViewById(R.id.statsChart).getLayoutDirection());
        assertEquals("نُظِّف ملف واحد خلال آخر 30 يومًا.", text(activity, R.id.statsChartSummary));
    }

    @Test
    public void everyLanguage_opensWithCounts() {
        StatsStore.recordClean(context, new CleanStats(1500, true), false, true);
        StatsStore.recordClean(context, new CleanStats(7, false), true, false);
        StatsStore.recordShare(context);
        for (String locale : LOCALES) {
            RuntimeEnvironment.setQualifiers(locale);
            ActivityController<StatisticsActivity> c = Robolectric.buildActivity(StatisticsActivity.class).setup();
            StatisticsActivity activity = c.get();

            assertFalse(locale, text(activity, R.id.statsFieldsValue).isEmpty());
            assertFalse(locale, text(activity, R.id.statsHeroLabel).isEmpty());
            assertFalse(locale, text(activity, R.id.statsChartSummary).isEmpty());
            assertFalse(locale, text(activity, R.id.statsChartStart).isEmpty());
            assertFalse(locale, tileLabel(activity, R.id.rowDevices).isEmpty());
            if (!locale.equals("en")) {
                assertFalse(locale + " shows English",
                        tileLabel(activity, R.id.rowDevices).equals("Camera & Phone"));
            }
            c.pause().stop().destroy();
        }
    }

    @Test
    public void everyLanguage_hasTheChartSummaryForEveryCount() {
        long[] counts = {1, 2, 3, 4, 5, 11, 21, 22, 25, 100, 101, 1_000_000};
        for (String locale : LOCALES) {
            Configuration config = new Configuration(context.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(locale.replace("-r", "-")));
            Resources res = context.createConfigurationContext(config).getResources();
            for (long n : counts) {
                String formatted = java.text.NumberFormat.getIntegerInstance(config.getLocales().get(0)).format(n);
                String summary = res.getQuantityString(R.plurals.stats_chart_summary, (int) n, formatted);
                assertFalse(locale + " " + n, summary.isEmpty());
                assertFalse(locale + " " + n + " left a placeholder: " + summary, summary.contains("%"));
                if (n > 2) {
                    assertTrue(locale + " " + n + " should show the count: " + summary, summary.contains(formatted));
                }
            }
        }
    }
}
