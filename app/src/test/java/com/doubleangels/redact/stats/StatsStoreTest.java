package com.doubleangels.redact.stats;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class StatsStoreTest {

    private static final long START_MS = 1_760_000_000_000L;

    private Context context;
    private final AtomicReference<LocalDate> today = new AtomicReference<>(LocalDate.of(2026, 10, 18));

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        StatsStore.setClockForTests(today::get, () -> START_MS);
        StatsStore.clear(context);
    }

    @After
    public void tearDown() {
        StatsStore.clear(context);
        StatsStore.resetClockForTests();
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
        StatsStore.recordClean(context, new CleanStats(12, true), false, false);
        StatsStore.recordClean(context, new CleanStats(3, false), true, false);
        StatsStore.recordClean(context, new CleanStats(5, true), false, true);

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
    public void dailyCounts_moveAlongAndDropOutAfterThirtyDays() {
        StatsStore.recordClean(context, new CleanStats(1, false), false, false);
        today.set(today.get().plusDays(1));
        StatsStore.recordClean(context, new CleanStats(1, false), false, false);
        StatsStore.recordClean(context, new CleanStats(1, false), false, false);

        long[] daily = StatsStore.snapshot(context).dailyCleans();
        assertEquals(1, daily[StatsStore.HISTORY_DAYS - 2]);
        assertEquals(2, daily[StatsStore.HISTORY_DAYS - 1]);

        today.set(today.get().plusDays(StatsStore.HISTORY_DAYS));
        StatsStore.recordClean(context, new CleanStats(1, false), false, false);
        StatsSnapshot s = StatsStore.snapshot(context);
        assertEquals(1, s.cleanedInHistory());
        // Lifetime totals keep the days the chart no longer shows.
        assertEquals(4, s.filesCleaned());
        long dayKeys = context.getSharedPreferences(StatsStore.PREFS_NAME, Context.MODE_PRIVATE)
                .getAll().keySet().stream().filter(k -> k.startsWith("day_")).count();
        assertEquals(1, dayKeys);
    }

    @Test
    public void clear_startsAgainFromNothing() {
        StatsStore.recordClean(context, new CleanStats(9, true), false, false);
        StatsStore.recordShare(context);

        StatsStore.clear(context);

        StatsSnapshot s = StatsStore.snapshot(context);
        assertTrue(s.isEmpty());
        assertEquals(0, s.fieldsRemoved);
        assertEquals(0, s.shares);
        assertArrayEquals(new long[StatsStore.HISTORY_DAYS], s.dailyCleans());
    }

    @Test
    public void resetAllSettings_keepsTheStatistics() {
        StatsStore.recordClean(context, new CleanStats(7, true), false, false);

        AppPreferences.resetAll(context);

        assertEquals(7, StatsStore.snapshot(context).fieldsRemoved);
    }
}
