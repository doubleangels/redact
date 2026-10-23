package com.doubleangels.redact.stats;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.metadata.CleanStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class StatsStoreTest {

    private static final long START_MS = 1_760_000_000_000L;

    private Context context;
    private final AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 18));
    private final AtomicLong nowMs = new AtomicLong(START_MS);

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        StatsStore.setClockForTests(today::get, nowMs::get);
        StatsStore.clear(context);
    }

    @After
    public void tearDown() {
        StatsStore.clear(context);
        StatsStore.resetClockForTests();
        AppPreferences.resetAll(context);
    }

    private SharedPreferences raw() {
        return context.getSharedPreferences(StatsStore.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private long dayKeyCount() {
        return raw().getAll().keySet().stream().filter(k -> k.startsWith("day_")).count();
    }

    private static CleanStats fields(int removed, boolean location) {
        return new CleanStats(removed, location);
    }

    @Test
    public void nothingRecorded_isEmpty() {
        StatsSnapshot s = StatsStore.snapshot(context);
        assertTrue(s.isEmpty());
        assertEquals(0, s.filesCleaned());
        assertEquals(0, s.cleanedInHistory());
        assertEquals(StatsStore.HISTORY_DAYS, s.dailyCleans().length);
    }

    @Test
    public void cleans_addUpByKindAndSource() {
        StatsStore.recordClean(context, fields(12, true), false, false);
        StatsStore.recordClean(context, fields(3, false), true, false);
        StatsStore.recordClean(context, fields(5, true), false, true);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertFalse(s.isEmpty());
        assertEquals(START_MS, s.trackingSince);
        assertEquals(2, s.imagesCleaned);
        assertEquals(1, s.videosCleaned);
        assertEquals(3, s.filesCleaned());
        assertEquals(20, s.fieldsRemoved);
        assertEquals(2, s.locationsRemoved);
        assertEquals(1, s.sharedIn);
        assertEquals(3, s.dailyCleans()[StatsStore.HISTORY_DAYS - 1]);
    }

    @Test
    public void cleans_countEachKindOfDetailOncePerFile() {
        StatsStore.recordClean(context, new CleanStats(9, true, true, true, false), false, false);
        StatsStore.recordClean(context, new CleanStats(4, false, true, false, true), true, false);
        StatsStore.recordClean(context, new CleanStats(1, false, false, false, false), false, false);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(1, s.locationsRemoved);
        assertEquals(2, s.datesRemoved);
        assertEquals(1, s.devicesRemoved);
        assertEquals(1, s.namesRemoved);
    }

    @Test
    public void clear_resetsTheDetailCounts() {
        StatsStore.recordClean(context, new CleanStats(9, true, true, true, true), false, false);

        StatsStore.clear(context);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(0, s.datesRemoved);
        assertEquals(0, s.devicesRemoved);
        assertEquals(0, s.namesRemoved);
    }

    @Test
    public void sharesAndConversions_areCountedSeparately() {
        StatsStore.recordShare(context);
        StatsStore.recordShare(context);
        StatsStore.recordConverted(context);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(2, s.shares);
        assertEquals(1, s.converted);
        assertEquals(0, s.filesCleaned());
        assertFalse(s.isEmpty());
    }

    @Test
    public void sharesAndConversions_doNotAddToTheChart() {
        StatsStore.recordShare(context);
        StatsStore.recordConverted(context);

        assertEquals(0, StatsStore.snapshot(context).cleanedInHistory());
        assertEquals(0, dayKeyCount());
    }

    @Test
    public void aFileWithoutALocation_doesNotCountAsALocationRemoved() {
        StatsStore.recordClean(context, fields(30, false), false, false);
        StatsStore.recordClean(context, fields(30, false), true, true);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(0, s.locationsRemoved);
        assertEquals(60, s.fieldsRemoved);
    }

    @Test
    public void aFileWithNothingToRemove_stillCountsAsCleaned() {
        StatsStore.recordClean(context, fields(0, false), false, false);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(1, s.filesCleaned());
        assertEquals(0, s.fieldsRemoved);
        assertEquals(1, s.cleanedInHistory());
    }

    @Test
    public void aNegativeFieldCount_isTreatedAsNone() {
        StatsStore.recordClean(context, fields(10, false), false, false);
        StatsStore.recordClean(context, fields(-5, false), false, false);

        assertEquals(10, StatsStore.snapshot(context).fieldsRemoved);
    }

    @Test
    public void countsStopAtTheLargestValueInsteadOfTurningNegative() {
        raw().edit().putLong("fields_removed", Long.MAX_VALUE - 1).commit();

        StatsStore.recordClean(context, fields(1000, true), false, false);

        assertEquals(Long.MAX_VALUE, StatsStore.snapshot(context).fieldsRemoved);
    }

    @Test
    public void trackingStartsWithTheFirstThingCounted_andNeverMoves() {
        nowMs.set(START_MS + 5_000);
        StatsStore.recordShare(context);
        nowMs.set(START_MS + 90_000_000);
        StatsStore.recordClean(context, fields(1, false), false, false);
        StatsStore.recordConverted(context);

        assertEquals(START_MS + 5_000, StatsStore.snapshot(context).trackingSince);
    }

    @Test
    public void clear_startsAgainFromNothing() {
        StatsStore.recordClean(context, fields(9, true), false, false);
        StatsStore.recordShare(context);

        StatsStore.clear(context);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertTrue(s.isEmpty());
        assertEquals(0, s.fieldsRemoved);
        assertEquals(0, s.shares);
        assertArrayEquals(new long[StatsStore.HISTORY_DAYS], s.dailyCleans());
        assertTrue("nothing is left behind in storage", raw().getAll().isEmpty());
    }

    @Test
    public void afterAClear_trackingStartsAgainFromTheNextThingCounted() {
        StatsStore.recordClean(context, fields(1, false), false, false);
        StatsStore.clear(context);
        nowMs.set(START_MS + 123_456);

        StatsStore.recordConverted(context);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(START_MS + 123_456, s.trackingSince);
        assertEquals(1, s.converted);
        assertEquals(0, s.filesCleaned());
    }

    @Test
    public void dailyCounts_moveAlongAndDropOutAfterThirtyDays() {
        StatsStore.recordClean(context, fields(1, false), false, false);
        today.set(today.get().plusDays(1));
        StatsStore.recordClean(context, fields(1, false), false, false);
        StatsStore.recordClean(context, fields(1, false), false, false);

        long[] daily = StatsStore.snapshot(context).dailyCleans();
        assertEquals(1, daily[StatsStore.HISTORY_DAYS - 2]);
        assertEquals(2, daily[StatsStore.HISTORY_DAYS - 1]);

        today.set(today.get().plusDays(StatsStore.HISTORY_DAYS));
        StatsStore.recordClean(context, fields(1, false), false, false);
        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(1, s.cleanedInHistory());
        // Lifetime totals keep the days the chart no longer shows.
        assertEquals(4, s.filesCleaned());
        assertEquals(1, dayKeyCount());
    }

    @Test
    public void theChartRunsFromTwentyNineDaysAgoToToday() {
        LocalDate start = today.get();
        StatsStore.recordClean(context, fields(1, false), false, false);
        today.set(start.plusDays(StatsStore.HISTORY_DAYS - 1));
        StatsStore.recordClean(context, fields(1, false), false, false);

        StatsSnapshot s = StatsStore.snapshot(context);
        long[] daily = s.dailyCleans();
        assertEquals(start.toEpochDay(), s.firstDay);
        assertEquals("the oldest bar is the first clean, 29 days ago", 1, daily[0]);
        assertEquals(1, daily[StatsStore.HISTORY_DAYS - 1]);
        assertEquals(2, s.cleanedInHistory());

        // One more day and the first clean leaves the chart, on screen and in storage.
        today.set(today.get().plusDays(1));
        assertEquals(1, StatsStore.snapshot(context).cleanedInHistory());
        StatsStore.recordClean(context, fields(1, false), false, false);
        assertEquals(2, dayKeyCount());
    }

    @Test
    public void theChartShowsNothingFromBeforeItsWindowEvenBeforeTheNextPrune() {
        StatsStore.recordClean(context, fields(1, false), false, false);
        today.set(today.get().plusDays(400));

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(0, s.cleanedInHistory());
        assertEquals(1, s.filesCleaned());
    }

    @Test
    public void whenTheClockGoesBack_daysAfterTodayAreDropped() {
        StatsStore.recordClean(context, fields(1, false), false, false);
        today.set(today.get().minusDays(3));

        StatsStore.recordClean(context, fields(1, false), false, false);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(1, dayKeyCount());
        assertEquals(1, s.dailyCleans()[StatsStore.HISTORY_DAYS - 1]);
        assertEquals(2, s.filesCleaned());
    }

    @Test
    public void damagedDayKeys_areRemoved() {
        raw().edit().putLong("day_garbage", 7).putLong("day_", 3).commit();

        StatsStore.recordClean(context, fields(1, false), false, false);

        assertEquals(1, dayKeyCount());
    }

    @Test
    public void aDamagedValue_neverStopsACleanOrTheScreen() {
        raw().edit()
                .putString("fields_removed", "lots")
                .putInt("shares", 4)
                .putString("tracking_since", "yesterday")
                .putString("day_" + today.get().toEpochDay(), "x")
                .commit();

        StatsSnapshot before = StatsStore.snapshot(context);
        assertEquals(0, before.fieldsRemoved);
        assertEquals(0, before.shares);
        assertTrue(before.isEmpty());

        StatsStore.recordClean(context, fields(12, true), false, false);
        StatsStore.recordShare(context);

        StatsSnapshot after = StatsStore.snapshot(context);
        assertEquals(12, after.fieldsRemoved);
        assertEquals(1, after.shares);
        assertEquals(START_MS, after.trackingSince);
        assertEquals(1, after.dailyCleans()[StatsStore.HISTORY_DAYS - 1]);
    }

    @Test
    public void snapshots_cannotBeChangedFromOutside() {
        StatsStore.recordClean(context, fields(1, false), false, false);
        StatsSnapshot s = StatsStore.snapshot(context);

        s.dailyCleans()[StatsStore.HISTORY_DAYS - 1] = 99;

        assertEquals(1, s.dailyCleans()[StatsStore.HISTORY_DAYS - 1]);
        assertEquals(1, s.cleanedInHistory());
    }

    @Test
    public void countsFromManyThreadsAtOnce_areNeverLost() throws Exception {
        int threads = 8;
        int each = 250;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final boolean video = t % 2 == 0;
            Thread worker = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < each; i++) {
                    StatsStore.recordClean(context, fields(3, true), video, !video);
                    StatsStore.recordShare(context);
                    StatsStore.recordConverted(context);
                }
            });
            workers.add(worker);
            worker.start();
        }
        go.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }

        StatsSnapshot s = StatsStore.snapshot(context);
        long total = (long) threads * each;
        assertEquals(total, s.filesCleaned());
        assertEquals(total / 2, s.videosCleaned);
        assertEquals(total / 2, s.sharedIn);
        assertEquals(total * 3, s.fieldsRemoved);
        assertEquals(total, s.locationsRemoved);
        assertEquals(total, s.shares);
        assertEquals(total, s.converted);
        assertEquals(total, s.cleanedInHistory());
    }

    @Test
    public void resetAllSettings_keepsTheStatistics() {
        StatsStore.recordClean(context, fields(7, true), false, false);

        AppPreferences.resetAll(context);

        assertEquals(7, StatsStore.snapshot(context).fieldsRemoved);
    }

    @Test
    public void clearingStatistics_keepsTheSettings() {
        StatsStore.recordClean(context, fields(5, true), false, true);
        StatsStore.recordShare(context);
        AppPreferences.setSecureWindow(context, true);

        SharedPreferences settings = context.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
        for (String key : settings.getAll().keySet()) {
            assertFalse("the settings file holds a statistics key: " + key,
                    key.startsWith("day_") || key.equals("fields_removed") || key.equals("shares")
                            || key.equals("tracking_since"));
        }

        StatsStore.clear(context);

        assertTrue(AppPreferences.isSecureWindow(context));
    }

    @Test
    public void statisticsAreNeverBackedUp() {
        assertEquals(0, context.getApplicationInfo().flags & ApplicationInfo.FLAG_ALLOW_BACKUP);
    }

    @Test
    public void withStatisticsOff_nothingIsCounted() {
        AppPreferences.setKeepStatistics(context, false);

        StatsStore.recordClean(context, fields(4, true), false, true);
        StatsStore.recordShare(context);
        StatsStore.recordConverted(context);

        assertTrue(StatsStore.snapshot(context).isEmpty());
        assertTrue("not even the day it happened", raw().getAll().isEmpty());

        AppPreferences.setKeepStatistics(context, true);
        StatsStore.recordConverted(context);
        assertEquals(1, StatsStore.snapshot(context).converted);
    }
}
