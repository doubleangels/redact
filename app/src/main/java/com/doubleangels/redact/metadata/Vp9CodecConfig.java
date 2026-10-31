package com.doubleangels.redact.metadata;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * Builds the {@code vpcC} configuration an MP4 needs for a VP9 track, from the stream's first key
 * frame.
 *
 * <p>A WebM usually stores no codec configuration for VP9 at all, while an MP4 has to declare the
 * profile, bit depth, chroma subsampling and colour of the stream. They are all in the key frame's
 * uncompressed header (VP9 bitstream specification, section 6.2), so this reads them from there
 * rather than guessing. Colour the container declares takes precedence, because a VP9 header only
 * names the matrix and the container also has the primaries and transfer.
 */
public final class Vp9CodecConfig {

    /** {@code android.media.MediaFormat} colour constants, so this class stays plain Java. */
    @VisibleForTesting static final int STANDARD_BT709 = 1;
    @VisibleForTesting static final int STANDARD_BT601_PAL = 2;
    @VisibleForTesting static final int STANDARD_BT601_NTSC = 4;
    @VisibleForTesting static final int STANDARD_BT2020 = 6;
    @VisibleForTesting static final int TRANSFER_LINEAR = 1;
    @VisibleForTesting static final int TRANSFER_SDR = 3;
    @VisibleForTesting static final int TRANSFER_ST2084 = 6;
    @VisibleForTesting static final int TRANSFER_HLG = 7;
    @VisibleForTesting static final int RANGE_FULL = 1;
    @VisibleForTesting static final int RANGE_LIMITED = 2;

    /** VP9 levels: {level, max luma picture size, max luma sample rate}. */
    private static final long[][] LEVELS = {
            {10, 36_864L, 829_440L},
            {11, 73_728L, 2_764_800L},
            {20, 122_880L, 4_608_000L},
            {21, 245_760L, 9_216_000L},
            {30, 552_960L, 20_736_000L},
            {31, 983_040L, 36_864_000L},
            {40, 2_228_224L, 83_558_400L},
            {41, 2_228_224L, 160_432_128L},
            {50, 8_912_896L, 311_951_360L},
            {51, 8_912_896L, 588_251_136L},
            {52, 8_912_896L, 1_176_502_272L},
            {60, 35_651_584L, 1_176_502_272L},
            {61, 35_651_584L, 2_353_004_544L},
            {62, 35_651_584L, 4_706_009_088L},
    };

    private Vp9CodecConfig() {
    }

    /**
     * The body of a {@code vpcC} box (version 1, starting with its version and flags), or null
     * when {@code keyFrame} is not a VP9 key frame.
     *
     * @param colorStandard the container's {@code MediaFormat} colour standard, or 0 when unknown
     * @param colorTransfer the container's colour transfer, or 0 when unknown
     * @param colorRange the container's colour range, or 0 when unknown
     */
    @Nullable
    public static byte[] vpcC(@NonNull byte[] keyFrame, int width, int height, float frameRate,
            int colorStandard, int colorTransfer, int colorRange) {
        Bits bits = new Bits(keyFrame);
        try {
            if (bits.read(2) != 2) {
                return null; // frame_marker
            }
            int profile = bits.read(1) | (bits.read(1) << 1);
            if (profile == 3) {
                bits.read(1);
            }
            if (bits.read(1) == 1) {
                return null; // show_existing_frame
            }
            if (bits.read(1) != 0) {
                return null; // not a key frame
            }
            bits.read(2); // show_frame, error_resilient_mode
            if (bits.read(24) != 0x498342) {
                return null; // frame_sync_code
            }
            int bitDepth = 8;
            if (profile >= 2) {
                bitDepth = bits.read(1) == 1 ? 12 : 10;
            }
            int colorSpace = bits.read(3);
            int fullRange;
            int subsamplingX = 1;
            int subsamplingY = 1;
            if (colorSpace != 7) {
                fullRange = bits.read(1);
                if (profile == 1 || profile == 3) {
                    subsamplingX = bits.read(1);
                    subsamplingY = bits.read(1);
                }
            } else {
                fullRange = 1;
                if (profile == 1 || profile == 3) {
                    subsamplingX = 0;
                    subsamplingY = 0;
                }
            }
            if (colorRange == RANGE_FULL) {
                fullRange = 1;
            } else if (colorRange == RANGE_LIMITED) {
                fullRange = 0;
            }
            int[] colour = colour(colorSpace, bitDepth, colorStandard, colorTransfer);
            byte[] box = new byte[12];
            box[0] = 1; // version 1, flags 0
            box[4] = (byte) profile;
            box[5] = (byte) level(width, height, frameRate);
            box[6] = (byte) ((bitDepth << 4) | (chromaSubsampling(subsamplingX, subsamplingY) << 1) | fullRange);
            box[7] = (byte) colour[0];
            box[8] = (byte) colour[1];
            box[9] = (byte) colour[2];
            // codecIntializationDataSize = 0: VP9 has none.
            return box;
        } catch (IndexOutOfBoundsException truncated) {
            return null;
        }
    }

    /** vpcC chroma subsampling: 4:2:0 co-located with luma, 4:2:2 or 4:4:4. */
    private static int chromaSubsampling(int x, int y) {
        if (x == 1 && y == 1) {
            return 1;
        }
        if (x == 1) {
            return 2;
        }
        return 3;
    }

    /** {primaries, transfer, matrix} as ISO/IEC 23091-2 code points. */
    @NonNull
    private static int[] colour(int vp9ColorSpace, int bitDepth, int standard, int transfer) {
        int primaries;
        int characteristics;
        int matrix;
        switch (vp9ColorSpace) {
            case 1: // BT.601
            case 3: // SMPTE 170
                primaries = 6;
                characteristics = 6;
                matrix = 6;
                break;
            case 2: // BT.709
                primaries = 1;
                characteristics = 1;
                matrix = 1;
                break;
            case 4: // SMPTE 240
                primaries = 7;
                characteristics = 7;
                matrix = 7;
                break;
            case 5: // BT.2020
                primaries = 9;
                characteristics = bitDepth == 12 ? 15 : bitDepth == 10 ? 14 : 1;
                matrix = 9;
                break;
            case 7: // sRGB
                primaries = 1;
                characteristics = 13;
                matrix = 0;
                break;
            default:
                primaries = 2;
                characteristics = 2;
                matrix = 2;
                break;
        }
        switch (standard) {
            case STANDARD_BT709:
                primaries = 1;
                matrix = 1;
                break;
            case STANDARD_BT601_PAL:
                primaries = 5;
                matrix = 5;
                break;
            case STANDARD_BT601_NTSC:
                primaries = 6;
                matrix = 6;
                break;
            case STANDARD_BT2020:
                primaries = 9;
                matrix = 9;
                break;
            default:
                break;
        }
        switch (transfer) {
            case TRANSFER_LINEAR:
                characteristics = 8;
                break;
            case TRANSFER_SDR:
                characteristics = primaries == 9 ? characteristics : 1;
                break;
            case TRANSFER_ST2084:
                characteristics = 16;
                break;
            case TRANSFER_HLG:
                characteristics = 18;
                break;
            default:
                break;
        }
        return new int[] {primaries, characteristics, matrix};
    }

    /** The lowest VP9 level that holds the picture size and sample rate; 0 when unknown. */
    @VisibleForTesting
    static int level(int width, int height, float frameRate) {
        if (width <= 0 || height <= 0) {
            return 0;
        }
        long size = (long) width * height;
        long rate = (long) Math.ceil(size * (frameRate > 0 ? frameRate : 30f));
        for (long[] level : LEVELS) {
            if (size <= level[1] && rate <= level[2]) {
                return (int) level[0];
            }
        }
        return 62;
    }

    /** Reads bits most significant first. */
    private static final class Bits {
        private final byte[] data;
        private int position;

        Bits(@NonNull byte[] data) {
            this.data = data;
        }

        int read(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                int b = data[position >>> 3] & 0xFF;
                value = (value << 1) | ((b >>> (7 - (position & 7))) & 1);
                position++;
            }
            return value;
        }
    }
}
