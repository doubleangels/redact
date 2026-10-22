package com.doubleangels.redact.metadata;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Builds small MP4 and WebM files box by box, for tests of container metadata. */
final class TestVideos {

    /** The QuickTime/MP4 timestamp for 2026-01-18 10:30 UTC (seconds since 1904). */
    static final long RECORDED_1904 = 3851577000L;
    static final String LOCATION = "+40.6892-074.0445/";
    /** Stands in for compressed video: tests check these bytes survive a scrub untouched. */
    static final byte[] SAMPLES = "SAMPLE-DATA-0123456789".getBytes(StandardCharsets.US_ASCII);

    private TestVideos() {}

    // ---- ISO base media -----------------------------------------------------------------------

    static byte[] box(String type, byte[]... children) {
        int size = 8;
        for (byte[] c : children) {
            size += c.length;
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putInt(size).put(type.getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] c : children) {
            b.put(c);
        }
        return b.array();
    }

    /** A box written with the 64-bit "largesize" header. */
    static byte[] largeBox(String type, byte[] payload) {
        ByteBuffer b = ByteBuffer.allocate(16 + payload.length);
        b.putInt(1).put(type.getBytes(StandardCharsets.ISO_8859_1)).putLong(16 + payload.length).put(payload);
        return b.array();
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] ftyp() {
        return box("ftyp", bytes("isom"), new byte[]{0, 0, 2, 0}, bytes("isomiso2mp41"));
    }

    /** mvhd/tkhd/mdhd: version 0, creation and modification time, then padding. */
    static byte[] header(String type, long time, int padding) {
        ByteBuffer b = ByteBuffer.allocate(12 + padding);
        b.putInt(0).putInt((int) time).putInt((int) time);
        return box(type, b.array());
    }

    static byte[] hdlr(String handler) {
        ByteBuffer b = ByteBuffer.allocate(25);
        b.putInt(0).putInt(0).put(handler.getBytes(StandardCharsets.ISO_8859_1)).put(new byte[13]);
        return box("hdlr", b.array());
    }

    static byte[] trak(String handler, long time) {
        return box("trak", header("tkhd", time, 72),
                box("mdia", header("mdhd", time, 12), hdlr(handler),
                        box("minf", box("stbl", box("stsd", new byte[8])))));
    }

    /** A QuickTime metadata box (no version/flags) listing mdta keys and their values. */
    static byte[] mdtaMeta(String... keysAndValues) {
        ByteArrayOutputStream keys = new ByteArrayOutputStream();
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        int count = keysAndValues.length / 2;
        for (int i = 0; i < count; i++) {
            byte[] key = bytes(keysAndValues[2 * i]);
            ByteBuffer k = ByteBuffer.allocate(8 + key.length);
            k.putInt(8 + key.length).put(bytes("mdta")).put(key);
            keys.writeBytes(k.array());
            byte[] value = bytes(keysAndValues[2 * i + 1]);
            ByteBuffer data = ByteBuffer.allocate(8 + value.length);
            data.putInt(1).putInt(0).put(value);
            byte[] dataBox = box("data", data.array());
            ByteBuffer item = ByteBuffer.allocate(8 + dataBox.length);
            item.putInt(8 + dataBox.length).putInt(i + 1).put(dataBox);
            items.writeBytes(item.array());
        }
        ByteBuffer keysHeader = ByteBuffer.allocate(8);
        keysHeader.putInt(0).putInt(count);
        return box("meta", hdlr("mdta"), box("keys", keysHeader.array(), keys.toByteArray()),
                box("ilst", items.toByteArray()));
    }

    /** An iTunes-style udta/meta with one ilst item, as ISO meta (with version/flags). */
    static byte[] itunesMeta(String itemType, String value) {
        byte[] v = bytes(value);
        ByteBuffer data = ByteBuffer.allocate(8 + v.length);
        data.putInt(1).putInt(0).put(v);
        return box("meta", new byte[4], hdlr("mdir"), box("ilst", box(itemType, box("data", data.array()))));
    }

    static byte[] xmpUuid(String xml) {
        byte[] id = new byte[]{(byte) 0xBE, 0x7A, (byte) 0xCF, (byte) 0xCB, (byte) 0x97, (byte) 0xA9, 0x42,
                (byte) 0xE8, (byte) 0x9C, 0x71, (byte) 0x99, (byte) 0x94, (byte) 0x91, (byte) 0xE3,
                (byte) 0xAF, (byte) 0xAC};
        return box("uuid", id, bytes(xml));
    }

    static byte[] mdat() {
        return box("mdat", SAMPLES);
    }

    /** ftyp + moov(mvhd, a video track, extras) + mdat: what a clean muxer output looks like. */
    static byte[] mp4(long time, byte[]... moovExtras) {
        byte[][] moovChildren = new byte[2 + moovExtras.length][];
        moovChildren[0] = header("mvhd", time, 96);
        moovChildren[1] = trak("vide", time);
        System.arraycopy(moovExtras, 0, moovChildren, 2, moovExtras.length);
        return concat(ftyp(), box("moov", moovChildren), mdat());
    }

    static byte[] cleanMp4() {
        return mp4(0);
    }

    // ---- EBML ---------------------------------------------------------------------------------

    static byte[] ebml(long id, byte[]... children) {
        byte[] data = concat(children);
        byte[] idBytes = idBytes(id);
        // Always an 8-byte size, which every EBML reader accepts.
        ByteBuffer b = ByteBuffer.allocate(idBytes.length + 8 + data.length);
        b.put(idBytes).put((byte) 0x01);
        for (int i = 6; i >= 0; i--) {
            b.put((byte) ((long) data.length >>> (8 * i)));
        }
        b.put(data);
        return b.array();
    }

    /** An element whose size is "unknown", as live recorders write clusters. */
    static byte[] ebmlUnknownSize(long id, byte[]... children) {
        return concat(idBytes(id), new byte[]{(byte) 0xFF}, concat(children));
    }

    private static byte[] idBytes(long id) {
        int n = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (id >>> (8 * (n - 1 - i)));
        }
        return out;
    }

    static byte[] webm(boolean tagged, boolean unknownSizeCluster) {
        byte[] header = ebml(0x1A45DFA3L, ebml(0x4282L, bytes("webm")));
        byte[] info = tagged
                ? ebml(0x1549A966L, ebml(0x7BA9L, bytes("Holiday at home")), ebml(0x4461L, new byte[8]),
                        ebml(0x4D80L, bytes("Lavf")))
                : ebml(0x1549A966L, ebml(0x4D80L, bytes("Lavf")));
        byte[] tracks = ebml(0x1654AE6BL, ebml(0xAEL, ebml(0xD7L, new byte[]{1})));
        byte[] block = ebml(0xA3L, SAMPLES);
        byte[] cluster = unknownSizeCluster
                ? ebmlUnknownSize(0x1F43B675L, ebml(0xE7L, new byte[]{0}), block)
                : ebml(0x1F43B675L, ebml(0xE7L, new byte[]{0}), block);
        byte[] tags = ebml(0x1254C367L, ebml(0x7373L,
                ebml(0x67C8L, ebml(0x45A3L, bytes("LOCATION")), ebml(0x4487L, bytes(LOCATION))),
                ebml(0x67C8L, ebml(0x45A3L, bytes("COMMENT")), ebml(0x4487L, bytes("secret comment")))));
        byte[] segment = tagged ? concat(info, tracks, cluster, tags) : concat(info, tracks, cluster);
        return concat(header, ebml(0x18538067L, segment));
    }

    // ---- files --------------------------------------------------------------------------------

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }

    static File write(File dir, String name, byte[] content) throws IOException {
        File f = File.createTempFile("tv_", "_" + name, dir);
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
        return f;
    }

    static File resource(File dir, String path) throws IOException {
        try (InputStream in = TestVideos.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Missing test resource " + path);
            }
            return write(dir, new File(path).getName(), in.readAllBytes());
        }
    }

    static boolean contains(File f, byte[] needle) throws IOException {
        byte[] hay = Files.readAllBytes(f.toPath());
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static boolean contains(File f, String needle) throws IOException {
        return contains(f, bytes(needle));
    }
}
