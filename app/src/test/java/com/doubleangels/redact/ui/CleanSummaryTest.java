package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.res.Resources;

import com.doubleangels.redact.metadata.CleanStats;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CleanSummaryTest {

    private final Resources res = RuntimeEnvironment.getApplication().getResources();

    private static String describe(Resources res, CleanStats... stats) {
        return CleanSummary.of(List.of(stats)).describe(res);
    }

    @Test
    public void oneFileWithLocation_mentionsIt() {
        assertEquals("Removed 24 metadata fields, including location.",
                describe(res, new CleanStats(24, true)));
    }

    @Test
    public void oneFileWithoutLocation_countsFieldsOnly() {
        assertEquals("Removed 1 metadata field.", describe(res, new CleanStats(1, false)));
    }

    @Test
    public void severalFiles_sumFieldsAndCountTheOnesWithLocation() {
        assertEquals("Removed 30 metadata fields. 2 files had location.",
                describe(res, new CleanStats(10, true), new CleanStats(15, false),
                        new CleanStats(5, true)));
        assertEquals("Removed 7 metadata fields. 1 file had location.",
                describe(res, new CleanStats(3, false), new CleanStats(4, true)));
    }

    @Test
    public void severalFilesWithoutLocation_countsFieldsOnly() {
        assertEquals("Removed 9 metadata fields.",
                describe(res, new CleanStats(4, false), new CleanStats(5, false)));
    }

    @Test
    public void nothingRemoved_saysNothingWasFound() {
        assertEquals("No identifying metadata was found.",
                describe(res, new CleanStats(0, false), new CleanStats(0, false)));
    }

    @Test
    public void noFiles_hasNoSummary() {
        assertNull(describe(res));
    }

    @Test
    public void append_joinsWithASpaceAndSkipsAMissingSummary() {
        assertEquals("Done. Removed 1 metadata field.",
                CleanSummary.append("Done.", "Removed 1 metadata field."));
        assertEquals("Done.", CleanSummary.append("Done.", null));
    }
}
