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

    /**
     * {@code png} with an eXIf chunk holding {@code tiff} right after IHDR, with a correct CRC.
     * Some writers put "Exif\0\0" in front of the TIFF data; {@code withHeader} adds it.
     */
    static byte[] pngWithExif(byte[] png, byte[] tiff, boolean withHeader) {
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        byte[] body = withHeader ? concat(new byte[] {'E', 'x', 'i', 'f', 0, 0}, tiff) : tiff;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(png, 0, afterIhdr);
        writePngChunk(out, "eXIf", body);
        out.write(png, afterIhdr, png.length - afterIhdr);
        return out.toByteArray();
    }

    /** A PNG chunk: big-endian length, type, data and the CRC over type and data. */
    static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(ByteBuffer.allocate(4).putInt(data.length).array(), 0, 4);
        out.write(typeBytes, 0, 4);
        out.write(data, 0, data.length);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array(), 0, 4);
    }

    /**
     * A RIFF/WEBP file made of {@code chunks}, each a four-letter type and its data, padded to an
     * even size as the format requires, with the RIFF size filled in.
     */
    static byte[] webp(Object... typeAndData) {
        ByteArrayOutputStream chunks = new ByteArrayOutputStream();
        for (int i = 0; i < typeAndData.length; i += 2) {
            byte[] data = (byte[]) typeAndData[i + 1];
            chunks.write(((String) typeAndData[i]).getBytes(StandardCharsets.US_ASCII), 0, 4);
            chunks.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length).array(), 0, 4);
            chunks.write(data, 0, data.length);
            if ((data.length & 1) == 1) {
                chunks.write(0);
            }
        }
        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {'R', 'I', 'F', 'F'}, 0, 4);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + body.length).array(), 0, 4);
        out.write(new byte[] {'W', 'E', 'B', 'P'}, 0, 4);
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
