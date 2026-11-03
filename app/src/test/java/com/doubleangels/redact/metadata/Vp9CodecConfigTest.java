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

    @Test
    public void vpcC_takesTheColourStandardAndTransferFromTheContainer() {
        byte[] key = hex("82498342404ff02cf60e3824");
        int[][] cases = {
                {Vp9CodecConfig.STANDARD_BT709, Vp9CodecConfig.TRANSFER_SDR, Vp9CodecConfig.RANGE_LIMITED},
                {Vp9CodecConfig.STANDARD_BT601_PAL, Vp9CodecConfig.TRANSFER_SDR, Vp9CodecConfig.RANGE_LIMITED},
                {Vp9CodecConfig.STANDARD_BT601_NTSC, Vp9CodecConfig.TRANSFER_SDR, Vp9CodecConfig.RANGE_FULL},
                {Vp9CodecConfig.STANDARD_BT2020, Vp9CodecConfig.TRANSFER_HLG, 0},
                {Vp9CodecConfig.STANDARD_BT2020, Vp9CodecConfig.TRANSFER_LINEAR, 0},
        };
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] c : cases) {
            byte[] vpcC = Vp9CodecConfig.vpcC(key, 1280, 720, 30, c[0], c[1], c[2]);
            org.junit.Assert.assertNotNull(vpcC);
            assertEquals(12, vpcC.length);
            seen.add(java.util.Arrays.toString(java.util.Arrays.copyOfRange(vpcC, 7, 10)));
        }
        assertEquals("each standard and transfer is told apart", cases.length, seen.size());
    }

    @Test
    public void vpcC_mapsEveryVp9ColourSpace() {
        // Profile 0 key frame; the fifth byte's top three bits are the colour space, then the range.
        int[][] expected = {
                {0, 2}, // unknown: unspecified
                {1, 6}, // BT.601
                {2, 1}, // BT.709
                {3, 6}, // SMPTE 170
                {4, 7}, // SMPTE 240
                {5, 9}, // BT.2020
        };
        for (int[] e : expected) {
            byte[] key = hex("82498342404ff02cf60e3824");
            key[4] = (byte) (e[0] << 5);
            byte[] vpcC = Vp9CodecConfig.vpcC(key, 1280, 720, 30, 0, 0, 0);
            org.junit.Assert.assertNotNull("colour space " + e[0], vpcC);
            assertEquals("primaries for colour space " + e[0], e[1], vpcC[7]);
        }
        // sRGB (7) is full range and has no range bit.
        byte[] rgb = hex("82498342404ff02cf60e3824");
        rgb[4] = (byte) (7 << 5);
        byte[] vpcC = Vp9CodecConfig.vpcC(rgb, 1280, 720, 30, 0, 0, 0);
        org.junit.Assert.assertNotNull(vpcC);
        assertEquals("full range", 1, vpcC[6] & 1);
    }
}
