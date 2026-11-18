package com.doubleangels.redact.metadata;

import static com.doubleangels.redact.metadata.TestVideos.box;
import static com.doubleangels.redact.metadata.TestVideos.bytes;
import static com.doubleangels.redact.metadata.TestVideos.concat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Feeds every parser files that are almost right: small, valid seeds of each format with bytes
 * flipped, size and length fields set to the values that break arithmetic (0, 1, 0xFFFFFFFF, a
 * 64-bit size near Long.MAX_VALUE), cut short or spliced. Each input must finish quickly, never
 * grow the file it scrubs, and never come out of a sanitize carrying anything a scan would find.
 * The hand-made edge cases elsewhere test what someone thought of; this tests what nobody did.
 *
 * <p>The seed is fixed, so a failure names the input that caused it and reproduces every time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ParserFuzzTest {

    private static final long SEED = 0xC0FFEEL;
    private static final int MUTATIONS_PER_SEED = 250;
    private static final long PER_INPUT_SECONDS = 5;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test(timeout = 300_000)
    public void mutatedFiles_finishQuickly_neverGrow_andNeverComeOutDirty() throws Exception {
        Random random = new Random(SEED);
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "fuzz-input");
            t.setDaemon(true);
            return t;
        });
        try {
            for (Map.Entry<String, byte[]> seed : seeds().entrySet()) {
                for (int i = 0; i < MUTATIONS_PER_SEED; i++) {
                    byte[] input = mutate(seed.getValue(), random);
                    String name = seed.getKey() + " #" + i;
                    Future<?> run = worker.submit(() -> {
                        exercise(name, input);
                        return null;
                    });
                    try {
                        run.get(PER_INPUT_SECONDS, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        run.cancel(true);
                        fail(name + " did not finish in " + PER_INPUT_SECONDS + " s: " + hex(input));
                    } catch (java.util.concurrent.ExecutionException e) {
                        AssertionError error = new AssertionError(name + ": " + e.getCause() + " for " + hex(input));
                        error.initCause(e.getCause());
                        throw error;
                    }
                }
            }
        } finally {
            worker.shutdownNow();
        }
    }

    /** Runs every entry point on one input; only assertion failures and errors escape. */
    private void exercise(String name, byte[] input) throws Exception {
        File in = write(input);

        // Scans swallow what they can't read; they must not throw or hang.
        ImageContainerMetadata.scan(in);
        VideoContainerMetadata.scan(in);
        MotionPhoto.find(in);
        quietly(() -> VideoStreamMetadata.scanBuffer(input, true));
        quietly(() -> VideoStreamMetadata.scanBuffer(input, false));
        quietly(() -> HeifContainerMetadata.filterHevcNalUnits(input, 4));
        quietly(() -> HeifContainerMetadata.filterAv1Obus(input));
        quietly(() -> ExifBlock.read(input));

        // A scrub rewrites in place and must never make the file longer.
        File scrubbed = write(input);
        quietly(() -> VideoContainerMetadata.scrub(scrubbed));
        assertEquals(name + ": a scrub never grows the file", input.length, scrubbed.length());

        // A sanitize that succeeds must leave nothing a scan would report.
        sanitizedIsClean(name, in, ImageContainerMetadata::sanitize);
        sanitizedIsClean(name, in, HeifContainerMetadata::sanitize);
        sanitizedIsClean(name, in, GifContainerMetadata::sanitize);
    }

    private interface Sanitizer {
        void sanitize(File source, File target) throws IOException;
    }

    private void sanitizedIsClean(String name, File in, Sanitizer sanitizer) throws IOException {
        File out = folder.newFile();
        try {
            sanitizer.sanitize(in, out);
        } catch (IOException | RuntimeException expected) {
            return; // refusing a file it can't follow is the safe answer
        }
        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(out);
        assertTrue(name + ": a sanitized file holds " + report.findings.size() + " findings",
                !report.recognized || report.findings.isEmpty());
    }

    private interface Body {
        Object run() throws Exception;
    }

    private static void quietly(Body body) {
        try {
            body.run();
        } catch (Exception expected) {
            // Damaged input may be refused; it must not hang, loop or throw an Error.
        }
    }

    private File write(byte[] content) throws IOException {
        File f = folder.newFile();
        Files.write(f.toPath(), content);
        return f;
    }

    // ---- mutations --------------------------------------------------------------------------

    private static final long[] EDGE_32 = {0, 1, 7, 8, 0xFF, 0xFFFF, 0x7FFFFFFFL, 0x80000000L, 0xFFFFFFFFL};
    private static final long[] EDGE_64 = {0, 1, 0x7FFFFFFFFFFFFFF8L, Long.MAX_VALUE, -1L, 0x100000000L};

    static byte[] mutate(byte[] seed, Random random) {
        byte[] b = seed.clone();
        int edits = 1 + random.nextInt(3);
        for (int e = 0; e < edits && b.length > 0; e++) {
            int at = random.nextInt(b.length);
            switch (random.nextInt(7)) {
                case 0: // flip a byte
                    b[at] = (byte) random.nextInt(256);
                    break;
                case 1: // a 32-bit size or length set to a value that breaks arithmetic
                    if (at + 4 <= b.length) {
                        ByteBuffer.wrap(b, at, 4).putInt((int) EDGE_32[random.nextInt(EDGE_32.length)]);
                    }
                    break;
                case 2: // a 64-bit largesize, behind the "1" that announces it
                    if (at + 16 <= b.length) {
                        ByteBuffer.wrap(b, at, 4).putInt(1);
                        ByteBuffer.wrap(b, at + 8, 8).putLong(EDGE_64[random.nextInt(EDGE_64.length)]);
                    }
                    break;
                case 3: // cut short
                    b = java.util.Arrays.copyOf(b, at);
                    break;
                case 6: // a real box header's size set to a 64-bit largesize that breaks arithmetic
                    List<Integer> boxes = boxHeaders(b);
                    if (!boxes.isEmpty()) {
                        int box = boxes.get(random.nextInt(boxes.size()));
                        if (box + 16 <= b.length) {
                            ByteBuffer.wrap(b, box, 4).putInt(1);
                            ByteBuffer.wrap(b, box + 8, 8).putLong(EDGE_64[random.nextInt(EDGE_64.length)]);
                        }
                    }
                    break;
                case 4: // a run of 0xFF, as in SEI sizes and JPEG fill bytes
                    int run = Math.min(b.length - at, 1 + random.nextInt(64));
                    java.util.Arrays.fill(b, at, at + run, (byte) 0xFF);
                    break;
                default: // splice a piece of the file elsewhere
                    int from = random.nextInt(b.length);
                    int len = Math.min(b.length - Math.max(at, from), random.nextInt(32));
                    if (len > 0) {
                        System.arraycopy(b.clone(), from, b, at, len);
                    }
                    break;
            }
        }
        return b;
    }

    /**
     * Where an ISO BMFF box (MP4, MOV, HEIF) starts: four bytes of size, then a four-letter
     * type. Random offsets rarely hit one, and box sizes are where the parsers' arithmetic is.
     */
    static List<Integer> boxHeaders(byte[] b) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 8 <= b.length; i++) {
            boolean type = true;
            for (int k = 4; k < 8; k++) {
                int c = b[i + k] & 0xFF;
                type &= (c >= 'a' && c <= 'z') || c == 0xA9 || c == ' ';
            }
            if (type) {
                starts.add(i);
            }
        }
        return starts;
    }

    // ---- seeds ------------------------------------------------------------------------------

    /** Small, valid files of each format the parsers read, with metadata a clean must remove. */
    static Map<String, byte[]> seeds() throws IOException {
        Map<String, byte[]> seeds = new LinkedHashMap<>();
        seeds.put("mp4", TestVideos.mp4(TestVideos.RECORDED_1904,
                box("udta", box("©xyz", bytes(TestVideos.LOCATION))),
                TestVideos.xmpUuid("<x:xmpmeta>secret</x:xmpmeta>")));
        seeds.put("webm", TestVideos.webm(true, false));
        seeds.put("jpeg", jpeg());
        seeds.put("png", png());
        seeds.put("webp", webp());
        seeds.put("gif", gif());
        seeds.put("heif", heif());
        seeds.put("hevc-nals", hevcNals());
        return seeds;
    }

    private static byte[] segment(int marker, byte[] body) {
        int length = body.length + 2;
        return concat(new byte[] {(byte) 0xFF, (byte) marker, (byte) (length >> 8), (byte) length}, body);
    }

    private static byte[] jpeg() {
        byte[] sof = {8, 0, 1, 0, 1, 1, 1, 0x11, 0};
        return concat(new byte[] {(byte) 0xFF, (byte) 0xD8},
                segment(0xE0, concat(bytes("JFIF\0"), new byte[] {1, 1, 0, 0, 1, 0, 1, 0, 0})),
                segment(0xE1, concat(bytes("http://ns.adobe.com/xap/1.0/\0"), bytes("<x:xmpmeta>secret</x:xmpmeta>"))),
                segment(0xFE, bytes("secret comment")),
                segment(0xDB, new byte[65]),
                segment(0xC0, sof),
                segment(0xC4, new byte[20]),
                segment(0xDA, new byte[] {1, 1, 0, 0, 0x3F, 0}),
                new byte[] {0x12, 0x34, (byte) 0xFF, 0x00, 0x56},
                new byte[] {(byte) 0xFF, (byte) 0xD9},
                bytes("trailing secret"));
    }

    private static byte[] pngChunk(String type, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(12 + data.length);
        b.putInt(data.length).put(bytes(type)).put(data).putInt(0);
        return b.array();
    }

    private static byte[] png() {
        byte[] ihdr = ByteBuffer.allocate(13).putInt(1).putInt(1).put((byte) 8).put((byte) 0).array();
        return concat(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
                pngChunk("IHDR", ihdr),
                pngChunk("tEXt", bytes("Author\0secret")),
                pngChunk("IDAT", new byte[] {0x78, 1, 1, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0}),
                pngChunk("IEND", new byte[0]));
    }

    private static byte[] riffChunk(String type, byte[] data) {
        ByteBuffer b = ByteBuffer.allocate(8 + data.length + (data.length & 1)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(bytes(type)).putInt(data.length).put(data);
        return b.array();
    }

    private static byte[] webp() {
        byte[] body = concat(bytes("WEBP"),
                riffChunk("VP8X", new byte[10]),
                riffChunk("VP8L", new byte[] {0x2F, 0, 0, 0, 0}),
                riffChunk("XMP ", bytes("<x:xmpmeta>secret</x:xmpmeta>")),
                riffChunk("EXIF", bytes("MM\0*secret")));
        ByteBuffer head = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        head.put(bytes("RIFF")).putInt(body.length);
        return concat(head.array(), body);
    }

    private static byte[] gif() {
        return concat(bytes("GIF89a"), new byte[] {1, 0, 1, 0, (byte) 0x80, 0, 0}, new byte[6],
                new byte[] {0x21, (byte) 0xFE, 6}, bytes("secret"), new byte[] {0},
                new byte[] {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0},
                new byte[] {0x3B});
    }

    private static byte[] fullBox(String type, byte[]... body) {
        return box(type, concat(new byte[4], concat(body)));
    }

    private static byte[] heif() {
        byte[] ftyp = box("ftyp", bytes("heic"), new byte[4], bytes("mif1heic"));
        byte[] hdlr = fullBox("hdlr", new byte[4], bytes("pict"), new byte[13]);
        byte[] pitm = fullBox("pitm", new byte[] {0, 1});
        byte[] infe = box("infe", new byte[] {2, 0, 0, 0, 0, 1, 0, 0}, bytes("hvc1"), new byte[] {0});
        byte[] exifInfe = box("infe", new byte[] {2, 0, 0, 0, 0, 2, 0, 0}, bytes("Exif"), new byte[] {0});
        byte[] iinf = fullBox("iinf", new byte[] {0, 2}, infe, exifInfe);
        byte[] iloc = fullBox("iloc", new byte[] {0x44, 0, 0, 2},
                new byte[] {0, 1, 0, 0, 0, 1}, ByteBuffer.allocate(8).putInt(200).putInt(8).array(),
                new byte[] {0, 2, 0, 0, 0, 1}, ByteBuffer.allocate(8).putInt(208).putInt(8).array());
        byte[] meta = fullBox("meta", hdlr, pitm, iinf, iloc);
        byte[] mdat = box("mdat", new byte[] {0, 0, 0, 4, 2, 1, 9, 9}, bytes("secret!!"));
        return concat(ftyp, meta, mdat);
    }

    private static byte[] hevcNals() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<byte[]> nals = new ArrayList<>();
        nals.add(new byte[] {(byte) (32 << 1), 1, 1, 2, 3});
        nals.add(concat(new byte[] {(byte) (39 << 1), 1, 5, 20}, new byte[16], bytes("secr"), new byte[] {(byte) 0x80}));
        nals.add(new byte[] {(byte) (39 << 1), 1, (byte) 144, 4, 0, 100, 0, 50, (byte) 0x80});
        nals.add(new byte[] {(byte) (1 << 1), 1, 9, 9, 9, 9});
        for (byte[] nal : nals) {
            out.write(ByteBuffer.allocate(4).putInt(nal.length).array(), 0, 4);
            out.write(nal, 0, nal.length);
        }
        return out.toByteArray();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(b.length, 96); i++) {
            s.append(String.format(java.util.Locale.ROOT, "%02x", b[i]));
        }
        return s + (b.length > 96 ? "… (" + b.length + " bytes)" : "");
    }
}
