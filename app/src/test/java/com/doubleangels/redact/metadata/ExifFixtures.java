package com.doubleangels.redact.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** EXIF blocks with tags ExifInterface cannot set, such as the XP tags Windows writes. */
final class ExifFixtures {

    private ExifFixtures() {
    }

    /** A little-endian TIFF block whose IFD0 holds one BYTE tag with {@code value}. */
    static byte[] tiffWith(int tag, byte[] value) {
        int valueOffset = 8 + 2 + 12 + 4;
        ByteBuffer b = ByteBuffer.allocate(valueOffset + value.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        b.putShort((short) 1);
        b.putShort((short) tag).putShort((short) 1).putInt(value.length);
        if (value.length <= 4) {
            byte[] inline = new byte[4];
            System.arraycopy(value, 0, inline, 0, value.length);
            b.put(inline);
        } else {
            b.putInt(valueOffset);
        }
        b.putInt(0); // no IFD1
        if (value.length > 4) {
            b.put(value);
        }
        return b.array();
    }

    /** XP text as Windows writes it: UTF-16LE with a terminator. */
    static byte[] xpText(String text) {
        byte[] chars = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] out = new byte[chars.length + 2];
        System.arraycopy(chars, 0, out, 0, chars.length);
        return out;
    }

    /** {@code jpeg} with an APP1 EXIF segment holding {@code tiff} right after SOI. */
    static byte[] withExif(byte[] jpeg, byte[] tiff) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        int length = 2 + 6 + tiff.length;
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(new byte[] {'E', 'x', 'i', 'f', 0, 0}, 0, 6);
        out.write(tiff, 0, tiff.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }
}
