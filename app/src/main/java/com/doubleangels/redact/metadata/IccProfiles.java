package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rebuilds a colour profile with only what turns its numbers into colours, for Strict Clean and
 * shares. A clean always clears the header's date, device and creator ({@link
 * ImageContainerMetadata#clearIccHeader}); this also drops the device maker and model text, the
 * calibration date and target, the profile sequence and any private tag, and gives a profile that
 * is not a well-known one a neutral name. A profile made by calibrating someone's own display
 * names that display, and often the day it was calibrated, in exactly those places.
 *
 * <p>A profile that can be followed is never dropped: that would change the colours. One whose
 * tag table cannot be followed is dropped instead of kept as it is, which would keep its text.
 */
final class IccProfiles {

    /** The name a profile that is not a well-known one is given. */
    static final String NEUTRAL_NAME = "Colour profile";

    /**
     * The tags the ICC specification (v2 and v4) defines that say how to turn the file's numbers
     * into colours, plus the description and copyright, which every profile is meant to have and
     * whose text is replaced unless the profile is a well-known one.
     */
    private static final Set<String> COLOUR_TAGS = Set.of(
            "A2B0", "A2B1", "A2B2", "B2A0", "B2A1", "B2A2",
            "D2B0", "D2B1", "D2B2", "D2B3", "B2D0", "B2D1", "B2D2", "B2D3",
            "rXYZ", "gXYZ", "bXYZ", "rTRC", "gTRC", "bTRC", "kTRC",
            "wtpt", "bkpt", "chad", "chrm", "lumi", "meas", "gamt", "pre0", "pre1", "pre2",
            "view", "tech", "cicp", "clro", "clrt", "clot", "ncl2", "rig0", "rig2", "ciis",
            "desc", "cprt");

    /**
     * The full names of the profiles cameras, phones and editors ship, which say nothing about a
     * person, lower-cased with runs of spaces made one. Only a whole name matches: a description
     * that merely starts with one ("sRGB - Jordan's monitor, 2026-03-01") is someone's own. A
     * standard profile missing here only gets the neutral name, which changes no colour.
     */
    private static final Set<String> WELL_KNOWN = Set.of(
            "srgb", "srgb iec61966-2.1", "srgb iec61966-2-1", "srgb built-in", "srgb2014",
            "srgb v4 icc preference perceptual intent beta", "srgb linear", "linear srgb", "scrgb",
            "display p3", "display p3 d65", "display p3 linear", "display p3 pq", "display p3 hlg",
            "p3 d65", "dci-p3", "dci(p3) rgb",
            "adobe rgb", "adobe rgb (1998)", "prophoto rgb", "apple rgb", "colormatch rgb",
            "ecirgb v2", "ecirgb_v2", "wide gamut rgb",
            "rec. 2020", "rec.2020", "rec 2020", "itu-r bt.2020", "bt.2020",
            "rec. 2100 pq", "rec. 2100 hlg", "bt.2100 pq", "bt.2100 hlg", "itu-r bt.2100 pq", "itu-r bt.2100 hlg",
            "rec. 709", "bt.709", "itu-r bt.709",
            "generic gray profile", "generic gray gamma 2.2 profile", "gray gamma 2.2", "gray gamma 1.8",
            "dot gain 10%", "dot gain 15%", "dot gain 20%", "dot gain 25%", "dot gain 30%");

    private IccProfiles() {
    }

    /** Whether {@code description} names one of the common, impersonal profiles. */
    static boolean isWellKnown(@Nullable String description) {
        if (description == null) {
            return false;
        }
        return WELL_KNOWN.contains(description.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }

    /**
     * {@code profile} with only its colour tags, and a neutral description and copyright unless
     * it is a well-known profile. Null when its tag table cannot be followed: the profile is then
     * left out. Kept as it was, its name, device and calibration text would stay, and a table
     * Redact can't follow is one no viewer can use to show colours either.
     */
    @Nullable
    static byte[] neutralize(@NonNull byte[] profile) {
        try {
            return rebuild(profile);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static byte[] rebuild(@NonNull byte[] p) {
        if (p.length < 132) {
            return null;
        }
        int count = (int) u32(p, 128);
        if (count < 0 || count > 1000 || 132L + 12L * count > p.length) {
            return null;
        }
        boolean wellKnown = isWellKnown(ImageContainerMetadata.IccText.read(p, "desc"));
        boolean v4 = (p[8] & 0xFF) >= 4;

        // Kept tags in their order; tags that shared data (rTRC, gTRC and bTRC often do) still share.
        List<String> signatures = new ArrayList<>();
        List<byte[]> data = new ArrayList<>();
        Map<Long, Integer> shared = new LinkedHashMap<>();
        List<Integer> dataIndex = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int entry = 132 + 12 * i;
            String sig = new String(p, entry, 4, StandardCharsets.ISO_8859_1);
            if (!COLOUR_TAGS.contains(sig)) {
                continue;
            }
            long offset = u32(p, entry + 4);
            long size = u32(p, entry + 8);
            if (offset < 128 || size < 8 || offset + size > p.length) {
                return null; // a table this cannot follow, which no viewer could use either
            }
            byte[] bytes;
            if (!wellKnown && (sig.equals("desc") || sig.equals("cprt"))) {
                // v4 profiles hold both as multiLocalizedUnicode; v2 as textDescription and text.
                if (v4) {
                    bytes = mluc(NEUTRAL_NAME);
                } else if (sig.equals("desc")) {
                    bytes = textDescription(NEUTRAL_NAME);
                } else {
                    bytes = text(NEUTRAL_NAME);
                }
                signatures.add(sig);
                dataIndex.add(data.size());
                data.add(bytes);
                continue;
            }
            long key = offset << 32 | size;
            Integer existing = shared.get(key);
            if (existing != null) {
                signatures.add(sig);
                dataIndex.add(existing);
                continue;
            }
            bytes = new byte[(int) size];
            System.arraycopy(p, (int) offset, bytes, 0, (int) size);
            shared.put(key, data.size());
            signatures.add(sig);
            dataIndex.add(data.size());
            data.add(bytes);
        }

        int tableEnd = 132 + 12 * signatures.size();
        int[] offsets = new int[data.size()];
        int pos = align(tableEnd);
        for (int i = 0; i < data.size(); i++) {
            offsets[i] = pos;
            pos = align(pos + data.get(i).length);
        }
        byte[] result = new byte[pos];
        System.arraycopy(p, 0, result, 0, 128);
        ByteBuffer out = ByteBuffer.wrap(result);
        out.putInt(0, result.length);
        out.putInt(128, signatures.size());
        for (int i = 0; i < signatures.size(); i++) {
            int entry = 132 + 12 * i;
            int d = dataIndex.get(i);
            System.arraycopy(signatures.get(i).getBytes(StandardCharsets.ISO_8859_1), 0, result, entry, 4);
            out.putInt(entry + 4, offsets[d]);
            out.putInt(entry + 8, data.get(d).length);
        }
        for (int i = 0; i < data.size(); i++) {
            System.arraycopy(data.get(i), 0, result, offsets[i], data.get(i).length);
        }
        // The header's profile ID is an MD5 of the old contents; zero means "not computed".
        Arrays.fill(result, 84, 100, (byte) 0);
        return result;
    }

    private static int align(int n) {
        return (n + 3) & ~3;
    }

    /** A v2 textDescriptionType holding {@code text} in ASCII, with empty Unicode and ScriptCode parts. */
    @NonNull
    private static byte[] textDescription(@NonNull String text) {
        byte[] ascii = (text + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(12 + ascii.length + 8 + 3 + 67);
        b.put("desc".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(ascii.length).put(ascii);
        b.putInt(0).putInt(0); // Unicode language code and count
        b.putShort((short) 0).put((byte) 0); // ScriptCode code and count; 67 bytes of zeros follow
        return b.array();
    }

    /** A v2 textType holding {@code text}. */
    @NonNull
    private static byte[] text(@NonNull String text) {
        byte[] ascii = (text + "\0").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('t');
        out.write('e');
        out.write('x');
        out.write('t');
        out.write(new byte[4], 0, 4);
        out.write(ascii, 0, ascii.length);
        return out.toByteArray();
    }

    /** A v4 multiLocalizedUnicodeType with one en-US record holding {@code text}. */
    @NonNull
    private static byte[] mluc(@NonNull String text) {
        byte[] utf16 = text.getBytes(StandardCharsets.UTF_16BE);
        ByteBuffer b = ByteBuffer.allocate(28 + utf16.length);
        b.put("mluc".getBytes(StandardCharsets.US_ASCII)).putInt(0).putInt(1).putInt(12);
        b.put("enUS".getBytes(StandardCharsets.US_ASCII)).putInt(utf16.length).putInt(28).put(utf16);
        return b.array();
    }

    private static long u32(byte[] b, int at) {
        return (b[at] & 0xFFL) << 24 | (b[at + 1] & 0xFFL) << 16 | (b[at + 2] & 0xFFL) << 8 | (b[at + 3] & 0xFFL);
    }
}
