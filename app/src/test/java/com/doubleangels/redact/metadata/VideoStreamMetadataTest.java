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
}
