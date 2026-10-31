package com.doubleangels.redact.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Writes a 16-bit PNG from a 16-bit PNG, sample for sample, optionally with some pixels replaced.
 *
 * <p>Android's {@code Bitmap.compress} turns a 16-bit picture into an 8-bit one before it writes a
 * PNG, so a Convert or Censor of a 16-bit PNG used to lose 255 of every 256 shades. This reads the
 * source's own samples a row at a time and writes them back at 16 bits. Censor passes the
 * censored pixels from its 8-bit picture as a {@link Patch}, so only those change, and they
 * change anyway. The colour chunks are kept, so the picture looks the same; everything else is
 * left behind, and the caller cleans the result like any other PNG.
 */
public final class Png16 {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    /** Chunks that say how to show the samples; the rest are left out. */
    private static final Set<String> COLOUR_CHUNKS = Set.of(
            "iCCP", "sRGB", "gAMA", "cHRM", "cICP", "mDCV", "cLLI", "sBIT", "tRNS");
    /** Largest picture an interlaced source is gathered for in memory before it is written. */
    private static final long MAX_INTERLACED_BYTES = 256L * 1024 * 1024;
    private static final int IDAT_CHUNK = 64 * 1024;

    private Png16() {
    }

    /** A 16-bit PNG's size and layout. */
    public static final class Header {
        public final int width;
        public final int height;
        /** 0 grey, 2 RGB, 4 grey and alpha, 6 RGBA. */
        public final int colorType;
        public final boolean interlaced;

        Header(int width, int height, int colorType, boolean interlaced) {
            this.width = width;
            this.height = height;
            this.colorType = colorType;
            this.interlaced = interlaced;
        }

        int channels() {
            switch (colorType) {
                case 0:
                    return 1;
                case 4:
                    return 2;
                case 2:
                    return 3;
                default:
                    return 4;
            }
        }

        boolean hasAlpha() {
            return colorType == 4 || colorType == 6;
        }

        boolean grey() {
            return colorType == 0 || colorType == 4;
        }
    }

    /** Pixels to put in place of the source's. */
    public interface Patch {
        /**
         * Fills {@code argb} with row {@code y}'s replacement pixels, unpremultiplied, and
         * {@code replace} with which of them to use; returns false when the row keeps them all.
         */
        boolean row(int y, @NonNull int[] argb, @NonNull boolean[] replace) throws IOException;
    }

    /** The header of {@code in} when it is a 16-bit PNG this class writes, else null. */
    @Nullable
    public static Header readHeader(@NonNull InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] signature = new byte[8];
        try {
            data.readFully(signature);
            if (!java.util.Arrays.equals(signature, SIGNATURE)) {
                return null;
            }
            int length = data.readInt();
            byte[] type = new byte[4];
            data.readFully(type);
            if (length != 13 || !"IHDR".equals(new String(type, StandardCharsets.ISO_8859_1))) {
                return null;
            }
            int width = data.readInt();
            int height = data.readInt();
            int bitDepth = data.readUnsignedByte();
            int colorType = data.readUnsignedByte();
            int compression = data.readUnsignedByte();
            int filter = data.readUnsignedByte();
            int interlace = data.readUnsignedByte();
            if (bitDepth != 16 || (colorType != 0 && colorType != 2 && colorType != 4 && colorType != 6)
                    || compression != 0 || filter != 0 || interlace > 1 || width <= 0 || height <= 0) {
                return null;
            }
            return new Header(width, height, colorType, interlace == 1);
        } catch (EOFException e) {
            return null;
        }
    }

    /**
     * Writes {@code source}, a 16-bit PNG, to {@code target} at 16 bits, not interlaced, with
     * {@code patch}'s pixels in place of the source's where it has them.
     *
     * @throws IOException when the source is not a 16-bit PNG or is damaged
     */
    public static void rewrite(@NonNull InputStream source, @NonNull OutputStream target,
            @Nullable Patch patch) throws IOException {
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(source, 64 * 1024));
        Header header = readHeader(in);
        if (header == null) {
            throw new IOException("Not a 16-bit PNG");
        }
        in.readInt(); // IHDR CRC
        List<byte[]> colour = new ArrayList<>();
        // The chunks before the picture data; the picture data is read as a stream after them.
        String type;
        int length;
        while (true) {
            length = in.readInt();
            byte[] t = new byte[4];
            in.readFully(t);
            type = new String(t, StandardCharsets.ISO_8859_1);
            if ("IDAT".equals(type)) {
                break;
            }
            if ("IEND".equals(type) || length < 0) {
                throw new IOException("PNG without picture data");
            }
            byte[] body = new byte[length];
            in.readFully(body);
            in.readInt();
            if (COLOUR_CHUNKS.contains(type)) {
                colour.add(chunk(type, body));
            }
        }

        int bpp = header.channels() * 2;
        long rowBytes = (long) header.width * bpp;
        if (rowBytes > Integer.MAX_VALUE - 1) {
            throw new IOException("PNG too wide");
        }
        InputStream pixels = new InflaterInputStream(new IdatStream(in, length), new java.util.zip.Inflater(), 64 * 1024);

        target.write(SIGNATURE);
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream(13);
        writeInt(ihdr, header.width);
        writeInt(ihdr, header.height);
        ihdr.write(16);
        ihdr.write(header.colorType);
        ihdr.write(0);
        ihdr.write(0);
        ihdr.write(0);
        target.write(chunk("IHDR", ihdr.toByteArray()));
        for (byte[] c : colour) {
            target.write(c);
        }
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (IdatWriter idat = new IdatWriter(target);
                DeflaterOutputStream z = new DeflaterOutputStream(idat, deflater, 64 * 1024)) {
            RowWriter writer = new RowWriter(header, (int) rowBytes, z, patch);
            if (header.interlaced) {
                byte[] all = deinterlace(header, pixels, bpp);
                for (int y = 0; y < header.height; y++) {
                    writer.write(y, all, (int) (y * rowBytes));
                }
            } else {
                byte[] previous = new byte[(int) rowBytes];
                byte[] row = new byte[(int) rowBytes];
                for (int y = 0; y < header.height; y++) {
                    int filter = pixels.read();
                    if (filter < 0) {
                        throw new IOException("PNG picture data ends early");
                    }
                    readFully(pixels, row);
                    unfilter(filter, row, previous, bpp);
                    writer.write(y, row, 0);
                    byte[] swap = previous;
                    previous = row;
                    row = swap;
                }
            }
            z.finish();
        } finally {
            deflater.end();
        }
        target.write(chunk("IEND", new byte[0]));
    }

    /** Writes rows, with the patch applied and the filter that compresses each best. */
    private static final class RowWriter {
        private final Header header;
        private final OutputStream out;
        @Nullable private final Patch patch;
        private final int[] argb;
        private final boolean[] replace;
        private final byte[] current;
        private byte[] previous;
        private final byte[][] filtered = new byte[5][];

        RowWriter(Header header, int rowBytes, OutputStream out, @Nullable Patch patch) {
            this.header = header;
            this.out = out;
            this.patch = patch;
            this.argb = patch != null ? new int[header.width] : null;
            this.replace = patch != null ? new boolean[header.width] : null;
            this.current = new byte[rowBytes];
            this.previous = new byte[rowBytes];
            for (int i = 0; i < 5; i++) {
                filtered[i] = new byte[rowBytes];
            }
        }

        void write(int y, byte[] source, int offset) throws IOException {
            System.arraycopy(source, offset, current, 0, current.length);
            if (patch != null) {
                java.util.Arrays.fill(replace, false);
                if (patch.row(y, argb, replace)) {
                    applyPatch(header, current, argb, replace);
                }
            }
            int bpp = header.channels() * 2;
            int best = 0;
            long bestScore = Long.MAX_VALUE;
            for (int f = 0; f < 5; f++) {
                filter(f, current, previous, filtered[f], bpp);
                long score = 0;
                for (byte b : filtered[f]) {
                    score += Math.abs((int) b);
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = f;
                }
            }
            out.write(best);
            out.write(filtered[best]);
            System.arraycopy(current, 0, previous, 0, current.length);
        }
    }

    /** Puts the replacement pixels, 8-bit, into a row of 16-bit samples. */
    @VisibleForTesting
    static void applyPatch(@NonNull Header header, @NonNull byte[] row, @NonNull int[] argb,
            @NonNull boolean[] replace) {
        int channels = header.channels();
        for (int x = 0; x < header.width; x++) {
            if (!replace[x]) {
                continue;
            }
            int c = argb[x];
            int a = (c >>> 24) & 0xFF;
            int r = (c >>> 16) & 0xFF;
            int g = (c >>> 8) & 0xFF;
            int b = c & 0xFF;
            int at = x * channels * 2;
            if (header.grey()) {
                put(row, at, (r * 299 + g * 587 + b * 114 + 500) / 1000);
            } else {
                put(row, at, r);
                put(row, at + 2, g);
                put(row, at + 4, b);
            }
            if (header.hasAlpha()) {
                put(row, at + (channels - 1) * 2, a);
            }
        }
    }

    /** An 8-bit value as the 16-bit sample that shows the same shade. */
    private static void put(byte[] row, int at, int value8) {
        int v = value8 * 257;
        row[at] = (byte) (v >>> 8);
        row[at + 1] = (byte) v;
    }

    /** All of an Adam7 interlaced picture's rows, in order, unfiltered. */
    @NonNull
    private static byte[] deinterlace(@NonNull Header header, @NonNull InputStream pixels, int bpp)
            throws IOException {
        long total = (long) header.width * header.height * bpp;
        if (total > MAX_INTERLACED_BYTES) {
            throw new IOException("Interlaced PNG too large to gather");
        }
        byte[] all = new byte[(int) total];
        int[][] passes = {{0, 0, 8, 8}, {4, 0, 8, 8}, {0, 4, 4, 8}, {2, 0, 4, 4}, {0, 2, 2, 4}, {1, 0, 2, 2}, {0, 1, 1, 2}};
        for (int[] p : passes) {
            int passWidth = (header.width - p[0] + p[2] - 1) / p[2];
            int passHeight = (header.height - p[1] + p[3] - 1) / p[3];
            if (passWidth <= 0 || passHeight <= 0) {
                continue;
            }
            byte[] previous = new byte[passWidth * bpp];
            byte[] row = new byte[passWidth * bpp];
            for (int py = 0; py < passHeight; py++) {
                int filter = pixels.read();
                if (filter < 0) {
                    throw new IOException("PNG picture data ends early");
                }
                readFully(pixels, row);
                unfilter(filter, row, previous, bpp);
                int y = p[1] + py * p[3];
                for (int px = 0; px < passWidth; px++) {
                    int x = p[0] + px * p[2];
                    System.arraycopy(row, px * bpp, all, (int) (((long) y * header.width + x) * bpp), bpp);
                }
                byte[] swap = previous;
                previous = row;
                row = swap;
            }
        }
        return all;
    }

    @VisibleForTesting
    static void unfilter(int filter, @NonNull byte[] row, @NonNull byte[] previous, int bpp) throws IOException {
        switch (filter) {
            case 0:
                return;
            case 1:
                for (int i = bpp; i < row.length; i++) {
                    row[i] += row[i - bpp];
                }
                return;
            case 2:
                for (int i = 0; i < row.length; i++) {
                    row[i] += previous[i];
                }
                return;
            case 3:
                for (int i = 0; i < row.length; i++) {
                    int left = i >= bpp ? row[i - bpp] & 0xFF : 0;
                    row[i] += (byte) ((left + (previous[i] & 0xFF)) >>> 1);
                }
                return;
            case 4:
                for (int i = 0; i < row.length; i++) {
                    int left = i >= bpp ? row[i - bpp] & 0xFF : 0;
                    int upLeft = i >= bpp ? previous[i - bpp] & 0xFF : 0;
                    row[i] += (byte) paeth(left, previous[i] & 0xFF, upLeft);
                }
                return;
            default:
                throw new IOException("Unknown PNG filter " + filter);
        }
    }

    @VisibleForTesting
    static void filter(int filter, @NonNull byte[] row, @NonNull byte[] previous, @NonNull byte[] out, int bpp) {
        for (int i = 0; i < row.length; i++) {
            int x = row[i] & 0xFF;
            int left = i >= bpp ? row[i - bpp] & 0xFF : 0;
            int up = previous[i] & 0xFF;
            int upLeft = i >= bpp ? previous[i - bpp] & 0xFF : 0;
            int predicted;
            switch (filter) {
                case 1:
                    predicted = left;
                    break;
                case 2:
                    predicted = up;
                    break;
                case 3:
                    predicted = (left + up) >>> 1;
                    break;
                case 4:
                    predicted = paeth(left, up, upLeft);
                    break;
                default:
                    predicted = 0;
                    break;
            }
            out[i] = (byte) (x - predicted);
        }
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    private static void readFully(@NonNull InputStream in, @NonNull byte[] buffer) throws IOException {
        int n = 0;
        while (n < buffer.length) {
            int r = in.read(buffer, n, buffer.length - n);
            if (r < 0) {
                throw new IOException("PNG picture data ends early");
            }
            n += r;
        }
    }

    @NonNull
    private static byte[] chunk(@NonNull String type, @NonNull byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 12);
        writeInt(out, body.length);
        byte[] t = type.getBytes(StandardCharsets.ISO_8859_1);
        out.write(t);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(body);
        writeInt(out, (int) crc.getValue());
        return out.toByteArray();
    }

    private static void writeInt(@NonNull OutputStream out, int value) throws IOException {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    /** The payloads of consecutive IDAT chunks as one stream, starting inside the first. */
    private static final class IdatStream extends InputStream {
        private final DataInputStream in;
        private int left;
        private boolean done;

        IdatStream(@NonNull DataInputStream in, int firstLength) {
            this.in = in;
            this.left = firstLength;
        }

        private boolean advance() throws IOException {
            while (left == 0 && !done) {
                in.readInt(); // CRC of the chunk just read
                int length = in.readInt();
                byte[] t = new byte[4];
                in.readFully(t);
                if (!"IDAT".equals(new String(t, StandardCharsets.ISO_8859_1))) {
                    done = true;
                    return false;
                }
                left = length;
            }
            return !done;
        }

        @Override
        public int read() throws IOException {
            if (!advance()) {
                return -1;
            }
            left--;
            return in.readUnsignedByte();
        }

        @Override
        public int read(@NonNull byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (!advance()) {
                return -1;
            }
            int n = in.read(b, off, Math.min(len, left));
            if (n < 0) {
                throw new EOFException();
            }
            left -= n;
            return n;
        }
    }

    /** Writes what it is given as IDAT chunks of up to 64 KB. */
    private static final class IdatWriter extends OutputStream {
        private final OutputStream out;
        private final byte[] buffer = new byte[IDAT_CHUNK];
        private int used;

        IdatWriter(@NonNull OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (used == buffer.length) {
                flushChunk();
            }
            buffer[used++] = (byte) b;
        }

        @Override
        public void write(@NonNull byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (used == buffer.length) {
                    flushChunk();
                }
                int n = Math.min(len, buffer.length - used);
                System.arraycopy(b, off, buffer, used, n);
                used += n;
                off += n;
                len -= n;
            }
        }

        private void flushChunk() throws IOException {
            if (used > 0) {
                out.write(chunk("IDAT", java.util.Arrays.copyOf(buffer, used)));
                used = 0;
            }
        }

        @Override
        public void close() throws IOException {
            flushChunk();
        }
    }
}
