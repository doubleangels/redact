package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * MediaExtractor.readSampleData throws IllegalArgumentException when a sample is bigger than the
 * buffer, rather than reporting its size, so the fast video path sizes the buffer first from
 * getSampleSize(). A large key frame must not abort the transmux.
 */
public class VideoCleanerSampleBufferTest {

    @Test
    public void aSampleThatFits_keepsTheBuffer() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1024);
        assertSame(buffer, VideoCleaner.bufferFor(buffer, 1024));
        assertSame(buffer, VideoCleaner.bufferFor(buffer, 10));
        // No more samples.
        assertSame(buffer, VideoCleaner.bufferFor(buffer, -1));
    }

    @Test
    public void aLargerSample_getsABufferItFits() throws IOException {
        ByteBuffer grown = VideoCleaner.bufferFor(ByteBuffer.allocate(1024), 3 * 1024 * 1024);
        assertEquals(3 * 1024 * 1024, grown.capacity());
    }

    @Test
    public void anImpossibleSample_failsSoTheVideoIsReEncoded() {
        assertThrows(IOException.class, () -> VideoCleaner.bufferFor(
                ByteBuffer.allocate(1024), VideoCleaner.MAX_SAMPLE_BYTES + 1L));
        assertThrows(IOException.class, () -> VideoCleaner.bufferFor(
                ByteBuffer.allocate(1024), Long.MAX_VALUE));
    }
}
