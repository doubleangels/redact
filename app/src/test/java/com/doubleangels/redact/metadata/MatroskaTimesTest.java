package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

/** Keep Date Taken reads a WebM's recording date, for the MP4 a kept date sends it to. */
public class MatroskaTimesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void matroskaTimes_readsDateUtcAsMp4Seconds() throws Exception {
        File webm = temp.newFile("dated.webm");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("video/dated.webm")) {
            Files.write(webm.toPath(), in.readAllBytes());
        }
        // 2024-05-01 10:00:00 UTC, counted from 1904 as an MP4 does.
        long seconds = 1_714_557_600L + 2_082_844_800L;
        assertArrayEquals(new long[] {seconds, seconds}, VideoContainerMetadata.matroskaTimes(webm));
    }

    @Test
    public void matroskaTimes_isNullWithoutADate() throws Exception {
        File other = temp.newFile("other.bin");
        Files.write(other.toPath(), new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0});
        assertNull(VideoContainerMetadata.matroskaTimes(other));
    }
}
