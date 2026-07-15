package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProcessingResourceWarningsTest {

    @Test
    public void estimateTempBytesForItem_usesFallbackWhenSizeUnknown() {
        assertEquals(1024L * 1024L * 1024L, ProcessingResourceWarnings.estimateTempBytesForItem(-1, true));
        assertEquals(128L * 1024L * 1024L, ProcessingResourceWarnings.estimateTempBytesForItem(0, false));
    }

    @Test
    public void estimateTempBytesForItem_scalesDeclaredSize() {
        long oneGb = 1024L * 1024L * 1024L;
        assertTrue(ProcessingResourceWarnings.estimateTempBytesForItem(oneGb, true) >= (long) (oneGb * 2.5));
        assertEquals(
                (long) Math.ceil(100L * 1024L * 1024L * 1.5),
                ProcessingResourceWarnings.estimateTempBytesForItem(100L * 1024L * 1024L, false));
    }
}
