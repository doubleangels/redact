package com.doubleangels.redact.metadata;

import static com.doubleangels.redact.metadata.HeifContainerMetadata.ascii;
import static com.doubleangels.redact.metadata.HeifContainerMetadata.box;
import static com.doubleangels.redact.metadata.HeifContainerMetadata.boxSize;
import static com.doubleangels.redact.metadata.HeifContainerMetadata.fourCc;
import static com.doubleangels.redact.metadata.HeifContainerMetadata.headerSize;
import static com.doubleangels.redact.metadata.HeifContainerMetadata.u16;
import static com.doubleangels.redact.metadata.HeifContainerMetadata.u32;
import static com.doubleangels.redact.metadata.HeifContainerMetadata.writeU32;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The picture tracks of a HEIF image sequence (a burst or an animated HEIC or AVIF), kept through
 * a clean.
 *
 * <p>Like the rest of {@link HeifContainerMetadata}, this works from an allowlist. Only picture
 * tracks ({@code pict}, {@code vide} and their auxiliary alpha or depth tracks) in HEVC or AV1 are
 * kept, with the boxes that time and decode them. User data and metadata boxes, where
 * location and dates go, are left out at every level. The creation and modification times are
 * zeroed, the handler name is emptied, and the sample entry's compressor name is blanked. Every
 * frame is filtered like a still picture, and the frames are written one per chunk to the new
 * data box. A layout this cannot follow (fragments, encryption, external data, another codec)
 * throws, and the clean keeps only the still image.
 */
final class HeifTracks {

    private static final Set<String> PICTURE_HANDLERS = Set.of("pict", "vide", "auxv");
    private static final Set<String> SAMPLE_ENTRY_CHILDREN = Set.of(
            "hvcC", "av1C", "colr", "pasp", "clap", "btrt", "ccst", "clli", "mdcv", "cclv", "amve", "auxi");
    /** Sample table boxes copied as they are; the sizes, chunks and offsets are written anew. */
    private static final Set<String> STBL_COPY = Set.of(
            "stsd", "stts", "ctts", "cslg", "stss", "sdtp", "sgpd", "sbgp");
    private static final int MAX_FRAME_BYTES = 256 * 1024 * 1024;

    private static final class Track {
        long id;
        byte[] tkhd;
        @Nullable byte[] edts;
        @Nullable byte[] tref;
        byte[] mdhd;
        String handler;
        byte[] mediaHeader;
        @Nullable byte[] dinf;
        final List<byte[]> stblCopies = new ArrayList<>();
        String codec;
        int nalLengthSize = 4;
        long[] sampleOffsets;
        int[] sampleSizes;
        long[] newOffsets;
        int[] newSizes;
    }

    private final RandomAccessFile in;
    private final byte[] mvhd;
    private final List<Track> tracks = new ArrayList<>();

    private HeifTracks(@NonNull RandomAccessFile in, @NonNull byte[] mvhd) {
        this.in = in;
        this.mvhd = mvhd;
    }

    /**
     * Reads the picture tracks of {@code moov}. Returns null when it holds none.
     *
     * @throws IOException when the tracks use a layout this class does not follow
     */
    @Nullable
    static HeifTracks parse(@NonNull RandomAccessFile in, @NonNull byte[] moov) throws IOException {
        byte[] mvhd = null;
        List<int[]> traks = new ArrayList<>();
        for (int[] child : children(moov, headerSize(moov, 0), moov.length)) {
            String type = fourCc(moov, child[0] + 4);
            if ("mvhd".equals(type)) {
                mvhd = withZeroedTimes(Arrays.copyOfRange(moov, child[0], child[1]));
            } else if ("trak".equals(type)) {
                traks.add(child);
            } else if ("mvex".equals(type)) {
                throw new IOException("Fragmented sequence");
            }
        }
        if (mvhd == null) {
            throw new IOException("No movie header");
        }
        HeifTracks result = new HeifTracks(in, mvhd);
        for (int[] trak : traks) {
            Track track = result.parseTrack(moov, trak[0] + headerSize(moov, trak[0]), trak[1]);
            if (track != null) {
                result.tracks.add(track);
            }
        }
        return result.tracks.isEmpty() ? null : result;
    }

    @Nullable
    private Track parseTrack(@NonNull byte[] moov, int start, int end) throws IOException {
        Track track = new Track();
        byte[] mdia = null;
        for (int[] child : children(moov, start, end)) {
            String type = fourCc(moov, child[0] + 4);
            byte[] bytes = Arrays.copyOfRange(moov, child[0], child[1]);
            switch (type) {
                case "tkhd": {
                    track.tkhd = withZeroedTimes(bytes);
                    int p = headerSize(bytes, 0);
                    track.id = u32(bytes, p + 4 + ((bytes[p] & 0xFF) == 1 ? 16 : 8));
                    break;
                }
                case "edts":
                    track.edts = bytes;
                    break;
                case "tref":
                    track.tref = bytes;
                    break;
                case "mdia":
                    mdia = bytes;
                    break;
                default:
                    break; // udta, meta and anything else
            }
        }
        if (track.tkhd == null || mdia == null) {
            throw new IOException("Damaged track");
        }
        byte[] minf = null;
        for (int[] child : children(mdia, headerSize(mdia, 0), mdia.length)) {
            String type = fourCc(mdia, child[0] + 4);
            byte[] bytes = Arrays.copyOfRange(mdia, child[0], child[1]);
            if ("mdhd".equals(type)) {
                track.mdhd = withZeroedTimes(bytes);
            } else if ("hdlr".equals(type)) {
                track.handler = fourCc(bytes, headerSize(bytes, 0) + 8);
            } else if ("minf".equals(type)) {
                minf = bytes;
            }
        }
        if (track.handler == null || !PICTURE_HANDLERS.contains(track.handler)) {
            return null; // sound, text, timed metadata: not the pictures
        }
        if (track.mdhd == null || minf == null) {
            throw new IOException("Damaged media box");
        }
        byte[] stbl = null;
        for (int[] child : children(minf, headerSize(minf, 0), minf.length)) {
            String type = fourCc(minf, child[0] + 4);
            byte[] bytes = Arrays.copyOfRange(minf, child[0], child[1]);
            switch (type) {
                case "vmhd":
                case "nmhd":
                    track.mediaHeader = bytes;
                    break;
                case "dinf":
                    requireSelfContained(bytes);
                    track.dinf = bytes;
                    break;
                case "stbl":
                    stbl = bytes;
                    break;
                default:
                    break;
            }
        }
        if (track.mediaHeader == null || stbl == null) {
            throw new IOException("Damaged media information");
        }
        parseSampleTable(track, stbl);
        return track;
    }

    private void parseSampleTable(@NonNull Track track, @NonNull byte[] stbl) throws IOException {
        int[] sizes = null;
        long[] chunkOffsets = null;
        long[][] samplesPerChunk = null;
        for (int[] child : children(stbl, headerSize(stbl, 0), stbl.length)) {
            String type = fourCc(stbl, child[0] + 4);
            int p = child[0] + headerSize(stbl, child[0]);
            switch (type) {
                case "stsd":
                    track.stblCopies.add(sampleDescriptions(track, Arrays.copyOfRange(stbl, child[0], child[1])));
                    break;
                case "stsz": {
                    int fixed = (int) u32(stbl, p + 4);
                    int count = (int) u32(stbl, p + 8);
                    sizes = new int[count];
                    for (int i = 0; i < count; i++) {
                        sizes[i] = fixed != 0 ? fixed : (int) u32(stbl, p + 12 + i * 4);
                    }
                    break;
                }
                case "stz2": {
                    int fieldSize = stbl[p + 7] & 0xFF;
                    int count = (int) u32(stbl, p + 8);
                    sizes = new int[count];
                    for (int i = 0; i < count; i++) {
                        if (fieldSize == 16) {
                            sizes[i] = u16(stbl, p + 12 + i * 2);
                        } else if (fieldSize == 8) {
                            sizes[i] = stbl[p + 12 + i] & 0xFF;
                        } else {
                            throw new IOException("Unsupported stz2 field size");
                        }
                    }
                    break;
                }
                case "stco":
                case "co64": {
                    boolean wide = "co64".equals(type);
                    int count = (int) u32(stbl, p + 4);
                    chunkOffsets = new long[count];
                    for (int i = 0; i < count; i++) {
                        chunkOffsets[i] = wide
                                ? (u32(stbl, p + 8 + i * 8) << 32) | u32(stbl, p + 12 + i * 8)
                                : u32(stbl, p + 8 + i * 4);
                    }
                    break;
                }
                case "stsc": {
                    int count = (int) u32(stbl, p + 4);
                    samplesPerChunk = new long[count][];
                    for (int i = 0; i < count; i++) {
                        int at = p + 8 + i * 12;
                        samplesPerChunk[i] = new long[] {u32(stbl, at), u32(stbl, at + 4)};
                    }
                    break;
                }
                case "saiz":
                case "saio":
                case "senc":
                    throw new IOException("Encrypted samples");
                default:
                    if (STBL_COPY.contains(type)) {
                        track.stblCopies.add(Arrays.copyOfRange(stbl, child[0], child[1]));
                    }
                    break;
            }
        }
        if (sizes == null || chunkOffsets == null || samplesPerChunk == null || track.codec == null) {
            throw new IOException("Incomplete sample table");
        }
        // Where every sample is: chunks hold runs of samples, as stsc says.
        track.sampleOffsets = new long[sizes.length];
        track.sampleSizes = sizes;
        int sample = 0;
        for (int chunk = 0; chunk < chunkOffsets.length && sample < sizes.length; chunk++) {
            long perChunk = 0;
            for (long[] entry : samplesPerChunk) {
                if (entry[0] - 1 <= chunk) {
                    perChunk = entry[1];
                }
            }
            long offset = chunkOffsets[chunk];
            for (long i = 0; i < perChunk && sample < sizes.length; i++) {
                track.sampleOffsets[sample] = offset;
                offset += sizes[sample];
                sample++;
            }
        }
        if (sample != sizes.length) {
            throw new IOException("Samples outside the chunks");
        }
    }

    /** stsd with each sample entry's compressor name blanked and its children filtered. */
    @NonNull
    private byte[] sampleDescriptions(@NonNull Track track, @NonNull byte[] stsd) throws IOException {
        int p = headerSize(stsd, 0);
        int count = (int) u32(stsd, p + 4);
        if (count != 1) {
            throw new IOException("Several sample descriptions");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(stsd, p, 8);
        int entry = p + 8;
        int entryEnd = entry + boxSize(stsd, entry);
        String codec = fourCc(stsd, entry + 4);
        if (!"hvc1".equals(codec) && !"hev1".equals(codec) && !"av01".equals(codec)) {
            throw new IOException("Unsupported codec " + codec);
        }
        if (u16(stsd, entry + 8 + 6) != 1) {
            throw new IOException("External sample data");
        }
        track.codec = codec;
        int fixed = entry + 8 + 78; // VisualSampleEntry fields
        byte[] fields = Arrays.copyOfRange(stsd, entry + 8, fixed);
        Arrays.fill(fields, 42, 74, (byte) 0); // compressorname
        ByteArrayOutputStream entryBody = new ByteArrayOutputStream();
        entryBody.write(fields);
        for (int[] child : children(stsd, fixed, entryEnd)) {
            String type = fourCc(stsd, child[0] + 4);
            if (!SAMPLE_ENTRY_CHILDREN.contains(type)) {
                continue;
            }
            byte[] bytes = Arrays.copyOfRange(stsd, child[0], child[1]);
            if ("hvcC".equals(type)) {
                track.nalLengthSize = (bytes[headerSize(bytes, 0) + 21] & 3) + 1;
                bytes = HeifContainerMetadata.filterHvcC(bytes);
            } else if ("av1C".equals(type)) {
                bytes = HeifContainerMetadata.filterAv1C(bytes);
            } else if ("colr".equals(type)) {
                int q = headerSize(bytes, 0);
                String colourType = fourCc(bytes, q);
                if ("prof".equals(colourType) || "rICC".equals(colourType)) {
                    ImageContainerMetadata.clearIccHeader(bytes, q + 4);
                }
            }
            entryBody.write(bytes);
        }
        body.write(box(codec, entryBody.toByteArray()));
        return box("stsd", body.toByteArray());
    }

    /** Copies every frame, filtered, to {@code body}, which starts at {@code bodyOffset}. */
    void writeSamples(@NonNull OutputStream body, long bodyOffset) throws IOException {
        long at = bodyOffset;
        for (Track track : tracks) {
            int count = track.sampleSizes.length;
            track.newOffsets = new long[count];
            track.newSizes = new int[count];
            for (int i = 0; i < count; i++) {
                int size = track.sampleSizes[i];
                if (size > MAX_FRAME_BYTES || track.sampleOffsets[i] + size > in.length()) {
                    throw new IOException("Frame outside the file");
                }
                byte[] frame = new byte[size];
                in.seek(track.sampleOffsets[i]);
                in.readFully(frame);
                byte[] clean = "av01".equals(track.codec)
                        ? HeifContainerMetadata.filterAv1Obus(frame)
                        : HeifContainerMetadata.filterHevcNalUnits(frame, track.nalLengthSize);
                body.write(clean);
                track.newOffsets[i] = at;
                track.newSizes[i] = clean.length;
                at += clean.length;
            }
        }
    }

    /** The new moov, with frame offsets counted from {@code dataStart}, where the body begins. */
    @NonNull
    byte[] build(long dataStart) throws IOException {
        Set<Long> kept = new HashSet<>();
        for (Track track : tracks) {
            kept.add(track.id);
        }
        ByteArrayOutputStream moov = new ByteArrayOutputStream();
        moov.write(mvhd);
        for (Track track : tracks) {
            ByteArrayOutputStream stbl = new ByteArrayOutputStream();
            for (byte[] copy : track.stblCopies) {
                stbl.write(copy);
            }
            int count = track.newSizes.length;
            // One frame per chunk, so the sizes and offsets say everything.
            ByteArrayOutputStream stsz = new ByteArrayOutputStream();
            writeU32(stsz, 0);
            writeU32(stsz, 0);
            writeU32(stsz, count);
            for (int size : track.newSizes) {
                writeU32(stsz, size);
            }
            stbl.write(box("stsz", stsz.toByteArray()));
            ByteArrayOutputStream stsc = new ByteArrayOutputStream();
            writeU32(stsc, 0);
            writeU32(stsc, count > 0 ? 1 : 0);
            if (count > 0) {
                writeU32(stsc, 1);
                writeU32(stsc, 1);
                writeU32(stsc, 1);
            }
            stbl.write(box("stsc", stsc.toByteArray()));
            ByteArrayOutputStream stco = new ByteArrayOutputStream();
            writeU32(stco, 0);
            writeU32(stco, count);
            for (long offset : track.newOffsets) {
                long absolute = dataStart + offset;
                if (absolute > 0xFFFFFFFFL) {
                    throw new IOException("Too large for 32-bit chunk offsets");
                }
                writeU32(stco, absolute);
            }
            stbl.write(box("stco", stco.toByteArray()));

            ByteArrayOutputStream minf = new ByteArrayOutputStream();
            minf.write(track.mediaHeader);
            if (track.dinf != null) {
                minf.write(track.dinf);
            }
            minf.write(box("stbl", stbl.toByteArray()));

            ByteArrayOutputStream mdia = new ByteArrayOutputStream();
            mdia.write(track.mdhd);
            mdia.write(hdlr(track.handler));
            mdia.write(box("minf", minf.toByteArray()));

            ByteArrayOutputStream trak = new ByteArrayOutputStream();
            trak.write(track.tkhd);
            byte[] tref = track.tref != null ? filterTref(track.tref, kept) : null;
            if (tref != null) {
                trak.write(tref);
            }
            if (track.edts != null) {
                trak.write(track.edts);
            }
            trak.write(box("mdia", mdia.toByteArray()));
            moov.write(box("trak", trak.toByteArray()));
        }
        return box("moov", moov.toByteArray());
    }

    /** tref with only references to kept tracks; null when none is left. */
    @Nullable
    private static byte[] filterTref(@NonNull byte[] tref, @NonNull Set<Long> kept) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (int[] child : children(tref, headerSize(tref, 0), tref.length)) {
            int p = child[0] + headerSize(tref, child[0]);
            ByteArrayOutputStream ids = new ByteArrayOutputStream();
            boolean all = true;
            for (int q = p; q + 4 <= child[1]; q += 4) {
                long id = u32(tref, q);
                if (kept.contains(id)) {
                    writeU32(ids, id);
                } else {
                    all = false;
                }
            }
            if (all && ids.size() > 0) {
                body.write(box(fourCc(tref, child[0] + 4), ids.toByteArray()));
            }
        }
        return body.size() > 0 ? box("tref", body.toByteArray()) : null;
    }

    @NonNull
    private static byte[] hdlr(@NonNull String handler) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeU32(b, 0);
        writeU32(b, 0);
        b.write(ascii(handler));
        writeU32(b, 0);
        writeU32(b, 0);
        writeU32(b, 0);
        b.write(0);
        return box("hdlr", b.toByteArray());
    }

    /** A copy of mvhd, tkhd or mdhd with its creation and modification times zeroed. */
    @NonNull
    private static byte[] withZeroedTimes(@NonNull byte[] box) {
        int p = headerSize(box, 0);
        int version = box[p] & 0xFF;
        int timesLength = version == 1 ? 16 : 8;
        if (box.length >= p + 4 + timesLength) {
            Arrays.fill(box, p + 4, p + 4 + timesLength, (byte) 0);
        }
        return box;
    }

    private static void requireSelfContained(@NonNull byte[] dinf) throws IOException {
        for (int[] child : children(dinf, headerSize(dinf, 0), dinf.length)) {
            if (!"dref".equals(fourCc(dinf, child[0] + 4))) {
                continue;
            }
            int p = child[0] + headerSize(dinf, child[0]) + 8;
            for (int[] entry : children(dinf, p, child[1])) {
                long flags = u32(dinf, entry[0] + headerSize(dinf, entry[0])) & 0xFFFFFF;
                if ((flags & 1) == 0) {
                    throw new IOException("External data");
                }
            }
        }
    }

    /** The child boxes between {@code start} and {@code end}, as [start, end) pairs. */
    @NonNull
    private static List<int[]> children(@NonNull byte[] data, int start, int end) throws IOException {
        List<int[]> children = new ArrayList<>();
        int pos = start;
        while (pos + 8 <= end) {
            int size = boxSize(data, pos);
            if (pos + size > end) {
                throw new IOException("Damaged box");
            }
            children.add(new int[] {pos, pos + size});
            pos += size;
        }
        return children;
    }
}
