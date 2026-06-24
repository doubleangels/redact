package com.doubleangels.redact.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaSelectorAnimatedTest {

    @Test
    public void containsAnimatedImagePayload_detectsAnimatedWebpChunk() {
        byte[] riff = "RIFF0000WEBPANIM".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(MediaSelector.containsAnimatedImagePayload(riff, riff.length));
    }

    @Test
    public void containsAnimatedImagePayload_detectsApngActlChunk() {
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                'a', 'c', 'T', 'L'
        };
        assertTrue(MediaSelector.containsAnimatedImagePayload(png, png.length));
    }

    @Test
    public void containsAnimatedImagePayload_ignoresStillJpeg() {
        byte[] jpeg = new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        assertFalse(MediaSelector.containsAnimatedImagePayload(jpeg, jpeg.length));
    }
}
