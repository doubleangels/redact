package com.doubleangels.redact.stats;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StatsSnapshotTest {

    private static StatsSnapshot withFiles(long images, long videos) {
        return new StatsSnapshot(images, videos, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0,
                new long[StatsStore.HISTORY_DAYS]);
    }

    @Test
    public void shareOfFiles_isOverPhotosAndVideos() {
        assertEquals(0.25, withFiles(3, 1).shareOfFiles(1), 0.0001);
        assertEquals(1, withFiles(1, 1).shareOfFiles(2), 0);
    }

    @Test
    public void shareOfFiles_withNothingCleaned_isZero() {
        assertEquals(0, withFiles(0, 0).shareOfFiles(0), 0);
        // Details with no files can only come from damaged counts; it must not divide by zero.
        assertEquals(0, withFiles(0, 0).shareOfFiles(4), 0);
    }

    @Test
    public void shareOfFiles_staysBetweenNoneAndEveryFile() {
        assertEquals(1, withFiles(2, 0).shareOfFiles(9), 0);
        assertEquals(0, withFiles(2, 0).shareOfFiles(-3), 0);
    }
}
