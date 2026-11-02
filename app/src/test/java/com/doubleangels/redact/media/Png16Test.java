package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;

/** A 16-bit PNG keeps all 16 bits through Convert and Censor. */
public class Png16Test {

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = Png16Test.class.getClassLoader().getResourceAsStream("image/" + name)) {
            if (in == null) {
                throw new IOException("Missing test resource");
            }
            return in.readAllBytes();
        }
    }

    private static byte[] rewrite(byte[] png, Png16.Patch patch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Png16.rewrite(new ByteArrayInputStream(png), out, patch);
        return out.toByteArray();
    }

    /** The unfiltered rows of a non-interlaced PNG, and the chunk types it has. */
    private static byte[] samples(byte[] png, StringBuilder types) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(png);
        b.position(8);
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int width = 0;
        int height = 0;
        int bpp = 0;
        while (b.remaining() >= 12) {
            int length = b.getInt();
            byte[] t = new byte[4];
            b.get(t);
            String type = new String(t, StandardCharsets.ISO_8859_1);
            types.append(type).append(' ');
            byte[] body = new byte[length];
            b.get(body);
            b.getInt();
            if (type.equals("IHDR")) {
                ByteBuffer h = ByteBuffer.wrap(body);
                width = h.getInt();
                height = h.getInt();
                assertEquals(16, body[8]);
                assertEquals("not interlaced", 0, body[12]);
                bpp = (body[9] == 6 ? 4 : body[9] == 2 ? 3 : body[9] == 4 ? 2 : 1) * 2;
            } else if (type.equals("IDAT")) {
                idat.write(body);
            }
        }
        byte[] all = new byte[width * height * bpp];
        try (InputStream z = new InflaterInputStream(new ByteArrayInputStream(idat.toByteArray()))) {
            byte[] previous = new byte[width * bpp];
            for (int y = 0; y < height; y++) {
                int filter = z.read();
                byte[] row = z.readNBytes(width * bpp);
                Png16.unfilter(filter, row, previous, bpp);
                System.arraycopy(row, 0, all, y * width * bpp, row.length);
                previous = row;
            }
        }
        return all;
    }

    @Test
    public void readHeader_recognisesOnly16BitPngs() throws Exception {
        Png16.Header header = Png16.readHeader(new ByteArrayInputStream(resource("rgba16.png")));
        assertNotNull(header);
        assertEquals(40, header.width);
        assertEquals(30, header.height);
        assertEquals(6, header.colorType);

        byte[] eightBit = resource("rgba16.png");
        eightBit[24] = 8;
        assertNull(Png16.readHeader(new ByteArrayInputStream(eightBit)));
        assertNull(Png16.readHeader(new ByteArrayInputStream("not a png".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    public void rewrite_keepsEverySample_andDropsTextChunks() throws Exception {
        byte[] source = resource("rgba16_interlaced.png");
        byte[] out = rewrite(source, null);

        StringBuilder types = new StringBuilder();
        byte[] written = samples(out, types);
        assertEquals("IHDR IDAT IEND ", types.toString());
        // The same picture stored without interlacing comes out as the same file.
        byte[] plain = rewrite(resource("rgba16.png"), null);
        assertArrayEquals(plain, out);
        assertArrayEquals(samples(plain, new StringBuilder()), written);
        // And a second pass changes nothing.
        assertArrayEquals(out, rewrite(out, null));

        boolean finerThan8Bits = false;
        for (int i = 0; i < written.length; i += 2) {
            int v = ((written[i] & 0xFF) << 8) | (written[i + 1] & 0xFF);
            finerThan8Bits |= v % 257 != 0;
        }
        assertTrue("the source's 16-bit shades are still there", finerThan8Bits);
    }

    @Test
    public void rewrite_replacesOnlyThePatchedPixels() throws Exception {
        byte[] source = resource("rgba16.png");
        byte[] before = samples(rewrite(source, null), new StringBuilder());
        byte[] after = samples(rewrite(source, (y, argb, replace) -> {
            if (y != 5) {
                return false;
            }
            argb[3] = 0x80FF0000;
            replace[3] = true;
            return true;
        }), new StringBuilder());

        int rowBytes = 40 * 8;
        int at = 5 * rowBytes + 3 * 8;
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF, 0, 0, 0, 0, (byte) 0x80, (byte) 0x80},
                java.util.Arrays.copyOfRange(after, at, at + 8));
        for (int i = 0; i < before.length; i++) {
            if (i < at || i >= at + 8) {
                assertEquals("byte " + i, before[i], after[i]);
            }
        }
    }

    @Test
    public void applyPatch_writesGreyAsLuma() {
        Png16.Header grey = new Png16.Header(1, 1, 0, false);
        byte[] row = new byte[2];
        Png16.applyPatch(grey, row, new int[] {0xFFFFFFFF}, new boolean[] {true});
        assertArrayEquals(new byte[] {(byte) 0xFF, (byte) 0xFF}, row);
        Png16.applyPatch(grey, row, new int[] {0xFF000000}, new boolean[] {false});
        assertFalse("a pixel not replaced stays", row[0] == 0);
    }

    @Test
    public void write_savesEvery16BitSample_withTheColourChunksGiven() throws Exception {
        int width = 7;
        int height = 5;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Png16.write(width, height, true,
                java.util.List.of(Png16.colourChunk("cICP", new byte[] {12, 13, 0, 1})),
                (y, samples) -> {
                    for (int i = 0; i < samples.length; i++) {
                        samples[i] = (y * 9973 + i * 131) & 0xFFFF;
                    }
                }, out);

        StringBuilder types = new StringBuilder();
        byte[] written = samples(out.toByteArray(), types);
        assertEquals("IHDR cICP IDAT IEND ", types.toString());
        for (int y = 0; y < height; y++) {
            for (int i = 0; i < width * 4; i++) {
                int at = (y * width * 4 + i) * 2;
                int v = ((written[at] & 0xFF) << 8) | (written[at + 1] & 0xFF);
                assertEquals((y * 9973 + i * 131) & 0xFFFF, v);
            }
        }
    }

    @Test
    public void rewrite_readsImageDataSplitOverSeveralChunks() throws Exception {
        StringBuilder oneTypes = new StringBuilder();
        StringBuilder splitTypes = new StringBuilder();
        byte[] one = samples(rewrite(resource("rgba16.png"), null), oneTypes);
        byte[] split = samples(rewrite(resource("rgba16_split.png"), null), splitTypes);
        org.junit.Assert.assertArrayEquals("the same samples, however the data was split", one, split);
    }
}
