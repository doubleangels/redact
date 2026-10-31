package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Rebuilds a GIF, still or animated, with nothing but its pictures, without re-encoding them.
 *
 * <p>A GIF has no EXIF, but it can carry comments, XMP and other application data in extension
 * blocks, and anything after its trailer. Decoding and encoding it again would lose its frames,
 * timing and transparency, so this copies it block by block from an allowlist: the header and
 * logical screen, colour tables, every frame with its timing and transparency, and the loop
 * count. Comments, XMP, plain text blocks, unknown application data and everything after the
 * trailer are left out.
 */
public final class GifContainerMetadata {

    private GifContainerMetadata() {
    }

    /** Whether {@code file} starts like a GIF. */
    public static boolean isGif(@NonNull File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[6];
            return in.read(head) == 6 && isGifHeader(head);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isGifHeader(@NonNull byte[] head) {
        String signature = new String(head, 0, 6, StandardCharsets.ISO_8859_1);
        return "GIF87a".equals(signature) || "GIF89a".equals(signature);
    }

    /**
     * Writes {@code source}'s pictures to {@code target} and nothing else.
     *
     * @throws IOException when the file is not a GIF this class can follow, so the caller
     *     re-encodes it instead
     */
    public static void sanitize(@NonNull File source, @NonNull File target) throws IOException {
        if (source.getCanonicalPath().equals(target.getCanonicalPath())) {
            throw new IOException("Source and target must differ");
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(source), 64 * 1024));
                OutputStream out = new BufferedOutputStream(new FileOutputStream(target), 64 * 1024)) {
            byte[] header = new byte[13];
            in.readFully(header);
            if (!isGifHeader(header)) {
                throw new IOException("Not a GIF");
            }
            out.write(header);
            copyColorTable(in, out, header[10]);
            boolean sawImage = false;
            while (true) {
                int block;
                try {
                    block = in.readUnsignedByte();
                } catch (EOFException e) {
                    if (!sawImage) {
                        throw new IOException("GIF without a picture");
                    }
                    break; // a missing trailer: end the file properly
                }
                if (block == 0x3B) {
                    break;
                }
                if (block == 0x2C) {
                    byte[] descriptor = new byte[9];
                    in.readFully(descriptor);
                    out.write(0x2C);
                    out.write(descriptor);
                    copyColorTable(in, out, descriptor[8]);
                    out.write(in.readUnsignedByte()); // LZW minimum code size
                    copySubBlocks(in, out);
                    sawImage = true;
                } else if (block == 0x21) {
                    int label = in.readUnsignedByte();
                    if (label == 0xF9) {
                        // Graphic control: frame delay, disposal and transparency.
                        out.write(0x21);
                        out.write(0xF9);
                        copySubBlocks(in, out);
                    } else if (label == 0xFF) {
                        byte[] first = readSubBlock(in);
                        String id = first.length >= 11
                                ? new String(first, 0, 11, StandardCharsets.ISO_8859_1) : "";
                        if ("NETSCAPE2.0".equals(id) || "ANIMEXTS1.0".equals(id)) {
                            // The loop count, which is all these hold.
                            out.write(0x21);
                            out.write(0xFF);
                            out.write(first.length);
                            out.write(first);
                            copySubBlocks(in, out);
                        } else {
                            skipSubBlocks(in); // XMP, ICC and any other application data
                        }
                    } else {
                        skipSubBlocks(in); // comments, plain text and unknown extensions
                    }
                } else {
                    throw new IOException("Unexpected GIF block " + block);
                }
            }
            out.write(0x3B);
        }
    }

    private static void copyColorTable(@NonNull DataInputStream in, @NonNull OutputStream out, byte packed)
            throws IOException {
        if ((packed & 0x80) == 0) {
            return;
        }
        byte[] table = new byte[3 * (1 << ((packed & 0x07) + 1))];
        in.readFully(table);
        out.write(table);
    }

    @NonNull
    private static byte[] readSubBlock(@NonNull DataInputStream in) throws IOException {
        int size = in.readUnsignedByte();
        byte[] data = new byte[size];
        in.readFully(data);
        return data;
    }

    private static void copySubBlocks(@NonNull DataInputStream in, @NonNull OutputStream out) throws IOException {
        while (true) {
            int size = in.readUnsignedByte();
            out.write(size);
            if (size == 0) {
                return;
            }
            byte[] data = new byte[size];
            in.readFully(data);
            out.write(data);
        }
    }

    private static void skipSubBlocks(@NonNull DataInputStream in) throws IOException {
        while (true) {
            int size = in.readUnsignedByte();
            if (size == 0) {
                return;
            }
            in.readFully(new byte[size]);
        }
    }
}
