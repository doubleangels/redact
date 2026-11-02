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

    /** Most bytes of one extension read to describe it; the rest is skipped. */
    private static final int MAX_DESCRIBED = 1024 * 1024;
    private static final int MAX_FINDINGS = 400;

    /**
     * What a clean would leave out of a GIF: comments, XMP, colour profiles and other application
     * data, plain text blocks, unknown extensions and anything after the trailer. Never throws; a
     * file whose blocks cannot be followed to the trailer is not recognized, so never called
     * clean, but what was found before that is still reported.
     */
    @NonNull
    static ImageContainerMetadata.Report scan(@NonNull java.nio.channels.FileChannel ch) {
        java.util.List<ImageContainerMetadata.Finding> findings = new java.util.ArrayList<>();
        boolean recognized = false;
        try {
            long length = ch.size();
            ch.position(0);
            // Not closed: closing a stream from Channels closes the caller's channel.
            DataInputStream in = new DataInputStream(new BufferedInputStream(
                    java.nio.channels.Channels.newInputStream(ch), 64 * 1024));
            long[] pos = {0};
            byte[] header = new byte[13];
            in.readFully(header);
            pos[0] += 13;
            if (!isGifHeader(header)) {
                return ImageContainerMetadata.Report.unrecognized(ImageContainerMetadata.Format.OTHER);
            }
            pos[0] += skipColorTable(in, header[10]);
            boolean sawImage = false;
            while (true) {
                int block = in.readUnsignedByte();
                pos[0]++;
                if (block == 0x3B) {
                    if (!sawImage) {
                        throw new IOException("GIF without a picture");
                    }
                    if (pos[0] < length) {
                        long after = length - pos[0];
                        byte[] peek = new byte[(int) Math.min(after, MAX_DESCRIBED)];
                        in.readFully(peek);
                        add(findings, "DATA_AFTER_IMAGE", ReadableText.strings(peek), after,
                                ImageContainerMetadata.Kind.OTHER);
                    }
                    recognized = true;
                    break;
                }
                if (block == 0x2C) {
                    byte[] descriptor = new byte[9];
                    in.readFully(descriptor);
                    pos[0] += 9 + skipColorTable(in, descriptor[8]);
                    in.readUnsignedByte();
                    pos[0]++;
                    pos[0] += skipSubBlocks(in, null);
                    sawImage = true;
                } else if (block == 0x21) {
                    int label = in.readUnsignedByte();
                    pos[0]++;
                    java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
                    if (label == 0xF9) {
                        pos[0] += skipSubBlocks(in, null);
                    } else if (label == 0xFF) {
                        long size = skipSubBlocks(in, data);
                        pos[0] += size;
                        byte[] bytes = data.toByteArray();
                        String id = bytes.length >= 11 ? new String(bytes, 0, 11, StandardCharsets.ISO_8859_1) : "";
                        if ("NETSCAPE2.0".equals(id) || "ANIMEXTS1.0".equals(id)) {
                            continue; // the loop count, which a clean keeps
                        }
                        if ("XMP DataXMP".equals(id)) {
                            // XMP is stored raw, ending with a "magic trailer" of 258 bytes.
                            String xmp = new String(bytes, 11, Math.max(0, bytes.length - 11 - 258),
                                    StandardCharsets.UTF_8);
                            add(findings, "XMP", xmp, size, ImageContainerMetadata.xmpKind(xmp));
                        } else if (id.startsWith("ICCRGBG1")) {
                            byte[] profile = java.util.Arrays.copyOfRange(bytes, Math.min(11, bytes.length), bytes.length);
                            add(findings, "ICC_PROFILE", ImageContainerMetadata.IccText.read(profile, "desc"),
                                    size, ImageContainerMetadata.Kind.OTHER);
                        } else {
                            add(findings, "GIF_APP_" + ImageContainerMetadata.keyOf(id.trim()),
                                    ReadableText.strings(bytes, Math.min(11, bytes.length), bytes.length),
                                    size, ImageContainerMetadata.Kind.OTHER);
                        }
                    } else {
                        long size = skipSubBlocks(in, data);
                        pos[0] += size;
                        byte[] bytes = data.toByteArray();
                        if (label == 0xFE) {
                            String text = new String(bytes, StandardCharsets.ISO_8859_1).trim();
                            add(findings, "GIF_COMMENT", text.isEmpty() ? null : text, size,
                                    ImageContainerMetadata.Kind.OTHER);
                        } else if (label == 0x01) {
                            // The text grid's 12-byte header comes first.
                            add(findings, "GIF_PLAIN_TEXT",
                                    ReadableText.strings(bytes, Math.min(12, bytes.length), bytes.length),
                                    size, ImageContainerMetadata.Kind.OTHER);
                        } else {
                            add(findings, String.format(java.util.Locale.ROOT, "GIF_EXTENSION_%02X", label),
                                    ReadableText.strings(bytes), size, ImageContainerMetadata.Kind.OTHER);
                        }
                    }
                } else {
                    throw new IOException("Unexpected GIF block " + block);
                }
            }
        } catch (IOException | RuntimeException e) {
            recognized = false;
        }
        return new ImageContainerMetadata.Report(ImageContainerMetadata.Format.GIF, recognized, findings,
                new java.util.ArrayList<>());
    }

    private static void add(@NonNull java.util.List<ImageContainerMetadata.Finding> findings, @NonNull String key,
            @androidx.annotation.Nullable String text, long bytes, @NonNull ImageContainerMetadata.Kind kind) {
        if (findings.size() >= MAX_FINDINGS) {
            return;
        }
        if (text != null && text.length() > 16 * 1024) {
            text = text.substring(0, 16 * 1024);
        }
        findings.add(new ImageContainerMetadata.Finding(key, text, bytes, kind));
    }

    private static int skipColorTable(@NonNull DataInputStream in, byte packed) throws IOException {
        if ((packed & 0x80) == 0) {
            return 0;
        }
        int size = 3 * (1 << ((packed & 0x07) + 1));
        in.readFully(new byte[size]);
        return size;
    }

    /** Skips sub-blocks up to their terminator, keeping up to {@link #MAX_DESCRIBED} bytes of them in {@code keep}. */
    private static long skipSubBlocks(@NonNull DataInputStream in, @androidx.annotation.Nullable java.io.ByteArrayOutputStream keep)
            throws IOException {
        long total = 0;
        byte[] buffer = new byte[255];
        while (true) {
            int size = in.readUnsignedByte();
            total++;
            if (size == 0) {
                return total;
            }
            in.readFully(buffer, 0, size);
            total += size;
            if (keep != null && keep.size() < MAX_DESCRIBED) {
                keep.write(buffer, 0, size);
            }
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
        byte[] data = new byte[255];
        while (true) {
            int size = in.readUnsignedByte();
            out.write(size);
            if (size == 0) {
                return;
            }
            in.readFully(data, 0, size);
            out.write(data, 0, size);
        }
    }

    private static void skipSubBlocks(@NonNull DataInputStream in) throws IOException {
        skipSubBlocks(in, null);
    }
}
