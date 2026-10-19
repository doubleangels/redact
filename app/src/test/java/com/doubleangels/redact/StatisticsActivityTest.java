package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.metadata.CleanStats;
import com.doubleangels.redact.stats.StatsStore;

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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class StatisticsActivityTest {

    private Context context;
    private ActivityController<StatisticsActivity> controller;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        StatsStore.clear(context);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        StatsStore.clear(context);
        AppPreferences.resetAll(context);
    }

    private StatisticsActivity launch() {
        controller = Robolectric.buildActivity(StatisticsActivity.class).setup();
        return controller.get();
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

    @Test
    public void showsEveryTotal() {
        StatsStore.recordClean(context, new CleanStats(1200, true), false, false);
        StatsStore.recordClean(context, new CleanStats(84, false), true, true);
        StatsStore.recordShare(context);
        StatsStore.recordConverted(context);
        StatsStore.recordConverted(context);

        StatisticsActivity activity = launch();

        assertEquals("1,284", text(activity, R.id.statsHeroValue));
        assertTrue(text(activity, R.id.statsHeroLabel).startsWith("Metadata fields removed since "));
        assertEquals("2", tile(activity, R.id.tileFilesCleaned));
        assertEquals("1", tile(activity, R.id.tileLocations));
        assertEquals("1", tile(activity, R.id.tilePhotos));
        assertEquals("1", tile(activity, R.id.tileVideos));
        assertEquals("1", tile(activity, R.id.tileSharedIn));
        assertEquals("1", tile(activity, R.id.tileShares));
        assertEquals("2", tile(activity, R.id.tileConverted));
        assertEquals("Files Cleaned", tileLabel(activity, R.id.tileFilesCleaned));
        assertEquals("Times Shared", tileLabel(activity, R.id.tileShares));
        assertEquals("2 files cleaned in the last 30 days.", text(activity, R.id.statsChartSummary));
        assertEquals("2 files cleaned in the last 30 days.",
                activity.findViewById(R.id.statsChart).getContentDescription().toString());
    }

    @Test
    public void withNothingCounted_explainsHowToStart() {
        StatisticsActivity activity = launch();

        assertEquals("0", text(activity, R.id.statsHeroValue));
        assertEquals(activity.getString(R.string.stats_hero_empty), text(activity, R.id.statsHeroLabel));
        assertEquals("0", tile(activity, R.id.tileFilesCleaned));
        assertEquals(activity.getString(R.string.stats_chart_empty), text(activity, R.id.statsChartSummary));
    }

    @Test
    public void clear_asksFirstThenSetsEverythingToZero() {
        StatsStore.recordClean(context, new CleanStats(40, true), false, false);
        StatisticsActivity activity = launch();

        activity.findViewById(R.id.rowClearStats).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        // Nothing is cleared until the user confirms.
        assertEquals(40, StatsStore.snapshot(context).fieldsRemoved);

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertTrue(StatsStore.snapshot(context).isEmpty());
        assertEquals("0", text(activity, R.id.statsHeroValue));
        assertEquals(activity.getString(R.string.stats_cleared), ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void cancel_keepsTheCounts() {
        StatsStore.recordClean(context, new CleanStats(40, true), false, false);
        StatisticsActivity activity = launch();

        activity.findViewById(R.id.rowClearStats).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(40, StatsStore.snapshot(context).fieldsRemoved);
    }

    @Test
    public void followsTheHideContentSetting() {
        AppPreferences.setSecureWindow(context, true);

        StatisticsActivity activity = launch();

        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    @Test
    @Config(qualifiers = "de")
    public void isTranslated() {
        StatsStore.recordClean(context, new CleanStats(1500, false), false, false);

        StatisticsActivity activity = launch();

        assertEquals("1.500", text(activity, R.id.statsHeroValue));
        assertEquals("Bereinigte Dateien", tileLabel(activity, R.id.tileFilesCleaned));
        assertEquals("1 Datei in den letzten 30 Tagen bereinigt.", text(activity, R.id.statsChartSummary));
    }
}
