package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MediaSizeLimitsTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void copyStream_copiesBytesAndReturnsCount() throws IOException {
        byte[] source = new byte[100_000];
        for (int i = 0; i < source.length; i++) {
            source[i] = (byte) (i % 251);
        }
        ByteArrayOutputStream dest = new ByteArrayOutputStream();

        long copied = MediaSizeLimits.copyStream(
                new ByteArrayInputStream(source), dest);

        assertEquals(source.length, copied);
        assertTrue(java.util.Arrays.equals(source, dest.toByteArray()));
    }

    @Test
    public void copyStream_invokesCancelCheckPerBufferChunk() throws IOException {
        byte[] source = new byte[65536 * 3 + 17];
        AtomicInteger checks = new AtomicInteger();
        MediaSizeLimits.copyStream(
                new ByteArrayInputStream(source),
                new ByteArrayOutputStream(),
                checks::incrementAndGet);
        assertEquals(4, checks.get());
    }

    @Test
    public void declaredSizeBytes_readsRealFileSizeViaPfdFallback() throws IOException {
        File file = File.createTempFile("size_limits_", ".bin");
        file.deleteOnExit();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(new byte[42_000]);
        }

        long size = MediaSizeLimits.declaredSizeBytes(context.getContentResolver(), Uri.fromFile(file));
        assertEquals(42_000, size);
    }

    @Test
    public void declaredSizeBytes_returnsMinusOneForMissingFile() {
        File missing = new File(context.getCacheDir(), "missing_" + System.nanoTime() + ".bin");
        assertEquals(-1, MediaSizeLimits.declaredSizeBytes(
                context.getContentResolver(), Uri.fromFile(missing)));
    }

    /** A source that never ends, or is larger than the room left, stops before filling storage. */
    @org.junit.Test
    public void aCopyStops_atItsLimit() throws Exception {
        java.io.InputStream endless = new java.io.InputStream() {
            @Override
            public int read() {
                return 0;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                return len;
            }
        };
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

        org.junit.Assert.assertThrows(MediaSizeLimits.TooLargeException.class,
                () -> MediaSizeLimits.copyStream(endless, out, null, 1_000_000));
        org.junit.Assert.assertTrue("nothing past the limit was written", out.size() <= 1_000_000);

        java.io.ByteArrayOutputStream small = new java.io.ByteArrayOutputStream();
        org.junit.Assert.assertEquals(5, MediaSizeLimits.copyStream(
                new java.io.ByteArrayInputStream(new byte[5]), small, null, 5));
    }

    @org.junit.Test
    public void theRoomForACopy_leavesAReserve() {
        java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"));
        long room = MediaSizeLimits.roomFor(dir);
        org.junit.Assert.assertTrue(room >= 0);
        org.junit.Assert.assertTrue(room <= Math.max(0, dir.getUsableSpace() - MediaSizeLimits.FREE_SPACE_RESERVE_BYTES));
    }
}
