package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.sentry.SentryManager;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
                        // Graphic control: frame delay, disposal and transparency, in the one
                        // 4-byte sub-block the format defines. Anything after it is dropped.
                        Filtered gce = filterSubBlocks(in, GifContainerMetadata::graphicControlBlock);
                        if (!gce.kept.isEmpty()) {
                            out.write(0x21);
                            out.write(0xF9);
                            gce.writeTo(out);
                        }
                    } else if (label == 0xFF) {
                        byte[] first = readSubBlock(in);
                        String id = first.length >= 11
                                ? new String(first, 0, 11, StandardCharsets.ISO_8859_1) : "";
                        if (first.length == 11 && ("NETSCAPE2.0".equals(id) || "ANIMEXTS1.0".equals(id))) {
                            // The loop count and buffer size, which are all these define; any
                            // other sub-block is dropped.
                            Filtered loop = filterSubBlocks(in, GifContainerMetadata::loopBlock);
                            out.write(0x21);
                            out.write(0xFF);
                            out.write(first.length);
                            out.write(first);
                            loop.writeTo(out);
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
    static ImageContainerMetadata.Report scan(@NonNull FileChannel ch) {
        List<ImageContainerMetadata.Finding> findings = new ArrayList<>();
        boolean recognized = false;
        try {
            long length = ch.size();
            ch.position(0);
            // Not closed: closing a stream from Channels closes the caller's channel.
            DataInputStream in = new DataInputStream(new BufferedInputStream(
                    Channels.newInputStream(ch), 64 * 1024));
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
                    ByteArrayOutputStream data = new ByteArrayOutputStream();
                    if (label == 0xF9) {
                        Filtered gce = filterSubBlocks(in, GifContainerMetadata::graphicControlBlock);
                        pos[0] += gce.totalBytes;
                        addDropped(findings, gce);
                    } else if (label == 0xFF) {
                        byte[] first = readSubBlock(in);
                        String id = first.length >= 11 ? new String(first, 0, 11, StandardCharsets.ISO_8859_1) : "";
                        if (first.length == 11 && ("NETSCAPE2.0".equals(id) || "ANIMEXTS1.0".equals(id))) {
                            // The loop count, which a clean keeps, and whatever else a clean drops.
                            Filtered loop = filterSubBlocks(in, GifContainerMetadata::loopBlock);
                            pos[0] += 1 + first.length + loop.totalBytes;
                            addDropped(findings, loop);
                            continue;
                        }
                        data.write(first);
                        long size = 1 + first.length + skipSubBlocks(in, data);
                        pos[0] += size;
                        byte[] bytes = data.toByteArray();
                        if ("XMP DataXMP".equals(id)) {
                            // XMP is stored raw, ending with a "magic trailer" of 258 bytes.
                            String xmp = new String(bytes, 11, Math.max(0, bytes.length - 11 - 258),
                                    StandardCharsets.UTF_8);
                            add(findings, "XMP", xmp, size, ImageContainerMetadata.xmpKind(xmp));
                        } else if (id.startsWith("ICCRGBG1")) {
                            byte[] profile = Arrays.copyOfRange(bytes, Math.min(11, bytes.length), bytes.length);
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
                            add(findings, String.format(Locale.ROOT, "GIF_EXTENSION_%02X", label),
                                    ReadableText.strings(bytes), size, ImageContainerMetadata.Kind.OTHER);
                        }
                    }
                } else {
                    throw new IOException("Unexpected GIF block " + block);
                }
            }
        } catch (IOException | RuntimeException e) {
            // A damaged file is never called clean; a RuntimeException is a gap in this parser,
            // so report it, as ImageContainerMetadata.scan does.
            if (e instanceof RuntimeException) {
                SentryManager.recordException(e);
            }
            recognized = false;
        }
        return new ImageContainerMetadata.Report(ImageContainerMetadata.Format.GIF, recognized, findings,
                new ArrayList<>());
    }

    /** Which sub-blocks of an extension a clean keeps, by their index and bytes. */
    private interface SubBlockRule {
        boolean keep(int index, @NonNull byte[] block);
    }

    /** A graphic control extension's one 4-byte sub-block. */
    private static boolean graphicControlBlock(int index, @NonNull byte[] block) {
        return index == 0 && block.length == 4;
    }

    /** The loop-count (id 1, 3 bytes) and buffer-size (id 2, 5 bytes) sub-blocks, once each. */
    private static boolean loopBlock(int index, @NonNull byte[] block) {
        return index < 2 && ((block.length == 3 && block[0] == 1) || (block.length == 5 && block[0] == 2));
    }

    /** The sub-blocks of an extension split into those a clean keeps and those it drops. */
    private static final class Filtered {
        final List<byte[]> kept = new ArrayList<>();
        final ByteArrayOutputStream dropped = new ByteArrayOutputStream();
        long droppedBytes;
        /** Every byte read, sizes and terminator included. */
        long totalBytes;

        void writeTo(@NonNull OutputStream out) throws IOException {
            for (byte[] block : kept) {
                out.write(block.length);
                out.write(block);
            }
            out.write(0);
        }
    }

    @NonNull
    private static Filtered filterSubBlocks(@NonNull DataInputStream in, @NonNull SubBlockRule rule)
            throws IOException {
        Filtered result = new Filtered();
        boolean sawLoop = false;
        boolean sawBuffer = false;
        for (int index = 0; ; index++) {
            byte[] block = readSubBlock(in);
            result.totalBytes += 1 + block.length;
            if (block.length == 0) {
                return result;
            }
            boolean keep = rule.keep(index, block);
            if (keep && block.length == 3) {
                keep = !sawLoop;
                sawLoop = true;
            } else if (keep && block.length == 5) {
                keep = !sawBuffer;
                sawBuffer = true;
            }
            if (keep) {
                result.kept.add(block);
            } else {
                result.droppedBytes += block.length;
                if (result.dropped.size() < MAX_DESCRIBED) {
                    result.dropped.write(block);
                }
            }
        }
    }

    private static void addDropped(@NonNull List<ImageContainerMetadata.Finding> findings,
            @NonNull Filtered filtered) {
        if (filtered.droppedBytes > 0) {
            add(findings, "GIF_EXTRA_SUB_BLOCKS", ReadableText.strings(filtered.dropped.toByteArray()),
                    filtered.droppedBytes, ImageContainerMetadata.Kind.OTHER);
        }
    }

    private static void add(@NonNull List<ImageContainerMetadata.Finding> findings, @NonNull String key,
            @Nullable String text, long bytes, @NonNull ImageContainerMetadata.Kind kind) {
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
    private static long skipSubBlocks(@NonNull DataInputStream in, @Nullable ByteArrayOutputStream keep)
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
