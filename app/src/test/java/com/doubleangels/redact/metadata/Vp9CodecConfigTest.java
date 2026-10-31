package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * The vpcC a WebM's VP9 needs in an MP4. The key frame headers come from libvpx, and the expected
 * boxes are what ffmpeg writes when it copies the same streams into an MP4.
 */
public class Vp9CodecConfigTest {

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    public void vpcC_eightBit420Bt709_matchesFfmpeg() {
        assertArrayEquals(hex("01000000001f820101010000"),
                Vp9CodecConfig.vpcC(hex("82498342404ff02cf60e3824"), 1280, 720, 30, 0, 0, 0));
    }

    @Test
    public void vpcC_tenBitHdr_takesThePqTransferFromTheContainer() {
        assertArrayEquals(hex("010000000229a20910090000"),
                Vp9CodecConfig.vpcC(hex("92498342503bf821bb061c12"), 1920, 1080, 60,
                        Vp9CodecConfig.STANDARD_BT2020, Vp9CodecConfig.TRANSFER_ST2084, 0));
    }

    @Test
    public void vpcC_profileOne444FullRange_matchesFfmpeg() {
        assertArrayEquals(hex("01000000011e870202020000"),
                Vp9CodecConfig.vpcC(hex("a24983421004fe03bec08704"), 640, 480, 25, 0, 0, 0));
    }

    @Test
    public void vpcC_refusesWhatIsNotAKeyFrame() {
        assertNull(Vp9CodecConfig.vpcC(hex("86000000"), 1280, 720, 30, 0, 0, 0)); // inter frame
        assertNull(Vp9CodecConfig.vpcC(hex("00000000"), 1280, 720, 30, 0, 0, 0));
        assertNull(Vp9CodecConfig.vpcC(hex("82"), 1280, 720, 30, 0, 0, 0)); // truncated
    }

    @Test
    public void level_followsTheVp9LevelTable() {
        assertEquals(31, Vp9CodecConfig.level(1280, 720, 30));
        assertEquals(40, Vp9CodecConfig.level(1920, 1080, 30));
        assertEquals(41, Vp9CodecConfig.level(1920, 1080, 60));
        assertEquals(51, Vp9CodecConfig.level(3840, 2160, 60));
        assertEquals(0, Vp9CodecConfig.level(0, 0, 30));
    }
}
