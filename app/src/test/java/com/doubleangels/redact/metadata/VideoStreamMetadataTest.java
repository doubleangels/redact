package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public class VideoStreamMetadataTest {

    private static final byte[] START = {0, 0, 0, 1};

    /** An SEI user_data_unregistered message: a 16-byte UUID, then the payload. */
    private static byte[] userData(String payload) {
        byte[] text = payload.getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(5);
        out.write(16 + text.length);
        out.write(new byte[16], 0, 16);
        out.write(text, 0, text.length);
        out.write(0x80); // rbsp trailing bits
        return out.toByteArray();
    }

    private static byte[] stream(byte[]... nals) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            out.write(START, 0, START.length);
            out.write(nal, 0, nal.length);
        }
        return out.toByteArray();
    }

    private static byte[] withHeader(byte[] header, byte[] body) {
        byte[] nal = new byte[header.length + body.length];
        System.arraycopy(header, 0, nal, 0, header.length);
        System.arraycopy(body, 0, nal, header.length, body.length);
        return nal;
    }

    @Test
    public void h264_findsUserDataAndItsText() {
        byte[] sps = {0x67, 0x42, 0x00, 0x1E};
        byte[] sei = withHeader(new byte[] {0x06}, userData("x264 - core 164 - options"));
        VideoStreamMetadata.Report r = VideoStreamMetadata.scanBuffer(stream(sps, sei, new byte[] {0x65, 1, 2}), false);

        assertEquals(1, r.userDataMessages);
        assertTrue(r.text.startsWith("x264"));
    }

    @Test
    public void hevc_findsPrefixSei() {
        byte[] sei = withHeader(new byte[] {(byte) (39 << 1), 0x01}, userData("MDPM camcorder data"));
        VideoStreamMetadata.Report r = VideoStreamMetadata.scanBuffer(stream(sei), true);

        assertEquals(1, r.userDataMessages);
    }

    @Test
    public void otherSeiMessages_areNotCounted() {
        // payload type 1 (picture timing), not user data
        byte[] sei = {0x06, 0x01, 0x02, 0x11, 0x22, (byte) 0x80};
        VideoStreamMetadata.Report r = VideoStreamMetadata.scanBuffer(stream(sei), false);

        assertEquals(0, r.userDataMessages);
        assertNull(r.text);
    }

    @Test
    public void lengthPrefixedSamples_areReadToo() {
        byte[] sei = withHeader(new byte[] {0x06}, userData("binary"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(sei.length);
        out.write(sei, 0, sei.length);

        assertEquals(1, VideoStreamMetadata.scanBuffer(out.toByteArray(), false).userDataMessages);
    }

    @Test
    public void typesAndSizesAbove255_areReadAcrossTheirExtensionBytes() {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x06);
        body.write(0xFF); // type 255 + 6, not user data
        body.write(0x06);
        body.write(0xFF); // size 255 + 1
        body.write(0x01);
        for (int i = 0; i < 256; i++) {
            body.write(0x11);
        }
        body.write(0x05); // user data of 255 + 1 bytes
        body.write(0xFF);
        body.write(0x01);
        body.write(new byte[16], 0, 16);
        for (int i = 0; i < 240; i++) {
            body.write('a');
        }
        body.write(0x80);
        VideoStreamMetadata.Report r = VideoStreamMetadata.scanBuffer(stream(body.toByteArray()), false);

        assertEquals(1, r.userDataMessages);
        assertEquals(256, r.userDataBytes);
        assertEquals(240, r.text.length());
    }

    @Test
    public void truncatedMessages_endTheScanWithoutCounting() {
        byte[][] truncated = {
                {0x06, (byte) 0xFF}, // type never ends
                {0x06, 0x05}, // no size
                {0x06, 0x05, (byte) 0xFF}, // size never ends
                {0x06, 0x05, 0x20, 1, 2}, // body shorter than its size
                {0x06, 0x05, 0x04, 1, 2, 3, 4, (byte) 0x80}, // too short to be user data
        };
        for (byte[] sei : truncated) {
            assertEquals(0, VideoStreamMetadata.scanBuffer(stream(sei), false).userDataMessages);
        }
    }

    @Test
    public void emulationPreventionBytes_areRemovedBeforeReading() {
        ByteArrayOutputStream nal = new ByteArrayOutputStream();
        nal.write(0x06);
        nal.write(0x05);
        nal.write(20);
        nal.write(new byte[] {0, 0, 3, 1}, 0, 4); // 00 00 01 in the UUID, escaped
        nal.write(new byte[13], 0, 13);
        nal.write(new byte[] {'a', 'b', 'c', 'd', (byte) 0x80}, 0, 5);
        VideoStreamMetadata.Report r = VideoStreamMetadata.scanBuffer(stream(nal.toByteArray()), false);

        assertEquals(1, r.userDataMessages);
        assertEquals("abcd", r.text);
    }

    @Test
    public void hevcSuffixSei_isCounted_andOtherNalTypesAreNot() {
        byte[] suffix = withHeader(new byte[] {(byte) (40 << 1), 0x01}, userData("suffix text"));
        byte[] slice = withHeader(new byte[] {(byte) (1 << 1), 0x01}, userData("not sei"));
        byte[] tooShort = {(byte) (39 << 1), 0x01};

        assertEquals(1, VideoStreamMetadata.scanBuffer(stream(suffix, slice, tooShort), true).userDataMessages);
        assertEquals(0, VideoStreamMetadata.scanBuffer(stream(new byte[] {0x06}), false).userDataMessages);
    }

    @Test
    public void onlyMostlyPrintablePayloads_becomeText() {
        byte[] binary = new byte[20];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (0x80 + i);
        }
        assertNull(VideoStreamMetadata.scanBuffer(stream(withHeader(new byte[] {0x06},
                userData(new String(binary, StandardCharsets.ISO_8859_1)))), false).text);
        assertNull(VideoStreamMetadata.scanBuffer(stream(withHeader(new byte[] {0x06}, userData("ab"))), false).text);
        assertNull(VideoStreamMetadata.scanBuffer(stream(withHeader(new byte[] {0x06}, userData("\0\0\0\0\0"))), false).text);
        assertEquals("abc def", VideoStreamMetadata.scanBuffer(
                stream(withHeader(new byte[] {0x06}, userData("abc\0def"))), false).text);
    }

    @Test
    public void severalMessages_areAllCounted_andTheFirstTextIsKept() {
        byte[] first = withHeader(new byte[] {0x06}, userData("first"));
        byte[] second = withHeader(new byte[] {0x06}, userData("second"));
        VideoStreamMetadata.Report r = VideoStreamMetadata.scanBuffer(stream(first, second), false);

        assertEquals(2, r.userDataMessages);
        assertEquals(16 + 5 + 16 + 6, r.userDataBytes);
        assertEquals("first", r.text);
    }

    @Test
    public void nalUnits_dropsTrailingZerosAndAcceptsThreeByteStartCodes() {
        byte[] data = {0, 0, 1, 0x06, 0x11, 0, 0, 0, 0, 1, 0x65, 0x22};

        java.util.List<byte[]> units = VideoStreamMetadata.nalUnits(data);
        assertEquals(2, units.size());
        org.junit.Assert.assertArrayEquals(new byte[] {0x06, 0x11}, units.get(0));
        org.junit.Assert.assertArrayEquals(new byte[] {0x65, 0x22}, units.get(1));
    }

    @Test
    public void lengthPrefixedData_stopsAtABadLength() {
        assertTrue(VideoStreamMetadata.nalUnits(new byte[] {1, 0, 0, 0, 0x06}).isEmpty());
        assertTrue(VideoStreamMetadata.nalUnits(new byte[] {(byte) 0x80, 0, 0, 0, 0x06}).isEmpty());
        assertEquals(1, VideoStreamMetadata.nalUnits(new byte[] {0, 0, 0, 2, 0x06, 0x07, 0x7F, 0x7F, 0x7F, 0x7F, 9}).size());
    }
}
