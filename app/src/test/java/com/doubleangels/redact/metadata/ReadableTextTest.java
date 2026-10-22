package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Turning binary metadata into something Scan can show. */
public class ReadableTextTest {

    private static byte[] bytes(Object... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Object part : parts) {
            if (part instanceof String) {
                byte[] b = ((String) part).getBytes(StandardCharsets.UTF_8);
                out.write(b, 0, b.length);
            } else {
                out.write((Integer) part);
            }
        }
        return out.toByteArray();
    }

    @Test
    public void strings_keepsWordsAndDropsNoise() {
        byte[] manifest = bytes(0, 1, "Adobe Photoshop 25.1", 0, 0, 0xFF, "c2pa.claim", 0, "AAAA", 0, "....", 0,
                "ab", 0, "12345678", 0, "Adobe Photoshop 25.1");

        assertEquals("Adobe Photoshop 25.1 · c2pa.claim", ReadableText.strings(manifest));
    }

    @Test
    public void strings_readsUtf8_andAnOffsetWindow() {
        byte[] data = bytes("skip", 0, "Café de Paris", 0, "after");

        assertEquals("Café de Paris", ReadableText.strings(data, 5, 14));
    }

    @Test
    public void strings_ofBinaryOnly_isNull() {
        byte[] binary = new byte[64];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (0x80 + i);
        }
        assertNull(ReadableText.strings(binary));
        assertNull(ReadableText.strings(new byte[0]));
        assertNull(ReadableText.strings(bytes(0xE2, 0x82))); // a cut-off UTF-8 sequence
    }

    @Test
    public void strings_isCappedForOneRow() {
        String result = ReadableText.strings("abcdefghij".repeat(60).getBytes(StandardCharsets.US_ASCII));

        assertEquals(ReadableText.MAX_LENGTH + 1, result.length());
        assertTrue(result.endsWith("…"));
    }

    @Test
    public void textOrStrings_keepsCleanTextWhole_andFallsBackForBinary() {
        assertEquals("+40.6892-074.0445/", ReadableText.textOrStrings(bytes("+40.6892-074.0445/", 0)));
        assertEquals("1234", ReadableText.textOrStrings(bytes("1234")));
        assertEquals("Lavf60.3.100", ReadableText.textOrStrings(bytes(1, 2, "Lavf60.3.100")));
        assertNull(ReadableText.textOrStrings(new byte[8]));
        assertEquals(ReadableText.MAX_LENGTH + 1, ReadableText.textOrStrings(bytes("x".repeat(400))).length());
    }

    @Test
    public void hexProfile_decodesImageMagickRawProfiles() {
        assertArrayEquals("Hello".getBytes(StandardCharsets.US_ASCII),
                ReadableText.hexProfile("\nxmp\n       5\n48656c\n6c6f\n"));
        assertArrayEquals(new byte[] {0x45, 0x78}, ReadableText.hexProfile("\nexif\n 2\n4578696600"));

        assertNull(ReadableText.hexProfile(null));
        assertNull(ReadableText.hexProfile("\nxmp\n"));
        assertNull(ReadableText.hexProfile("\nxmp\nfive\n4865"));
        assertNull(ReadableText.hexProfile("\nxmp\n 2\n48zz"));
        assertNull(ReadableText.hexProfile("\nxmp\n 0\n"));
    }

    @Test
    public void hexProfile_doesNotTrustTheLengthInTheFile() {
        // A claimed 2 GB profile must not allocate 2 GB; only the hex that is there is read.
        byte[] decoded = ReadableText.hexProfile("\nexif\n 2000000000\n4578");
        assertTrue(decoded == null || decoded.length <= 2);
    }

    @Test
    public void iso6709_readsLatitudeAndLongitude_withOrWithoutAltitude() {
        assertArrayEquals(new double[] {40.6892, -74.0445}, ReadableText.iso6709("+40.6892-074.0445+010.000/"), 1e-9);
        assertArrayEquals(new double[] {40.6892, -74.0445}, ReadableText.iso6709("+40.6892-074.0445/"), 1e-9);
        assertArrayEquals(new double[] {-33.8688, 151.2093}, ReadableText.iso6709(" -33.8688+151.2093 "), 1e-9);
        assertArrayEquals(new double[] {10, 95}, ReadableText.iso6709("+95.0+10.0"), 1e-9); // swapped

        assertNull(ReadableText.iso6709(null));
        assertNull(ReadableText.iso6709("+0.0+0.0/"));
        assertNull(ReadableText.iso6709("Paris"));
        assertNull(ReadableText.iso6709("+40.6892"));
        assertNull(ReadableText.iso6709("+40.6892, -74.0445"));
        assertNull(ReadableText.iso6709("+120.0+100.0"));
    }

    @Test
    public void utc_formatsLikeOtherScanDates() {
        assertEquals("1970-01-01 00:00:00 UTC", ReadableText.utc(0));
        assertEquals("2026-01-01 00:00:00 UTC", ReadableText.utc(1_767_225_600_000L));
    }
}
