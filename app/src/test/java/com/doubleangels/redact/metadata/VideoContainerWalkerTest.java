package com.doubleangels.redact.metadata;

import static com.doubleangels.redact.metadata.TestVideos.SAMPLES;
import static com.doubleangels.redact.metadata.TestVideos.box;
import static com.doubleangels.redact.metadata.TestVideos.bytes;
import static com.doubleangels.redact.metadata.TestVideos.concat;
import static com.doubleangels.redact.metadata.TestVideos.contains;
import static com.doubleangels.redact.metadata.TestVideos.ebml;
import static com.doubleangels.redact.metadata.TestVideos.ebmlUnknownSize;
import static com.doubleangels.redact.metadata.TestVideos.ftyp;
import static com.doubleangels.redact.metadata.TestVideos.hdlr;
import static com.doubleangels.redact.metadata.TestVideos.header;
import static com.doubleangels.redact.metadata.TestVideos.largeBox;
import static com.doubleangels.redact.metadata.TestVideos.mp4;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The MP4 and WebM walk on hand-built files, without Robolectric: the less common boxes and
 * elements, the shapes muxers actually write, and damaged files that must never pass as clean.
 */
public class VideoContainerWalkerTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final long SEGMENT = 0x18538067L;
    private static final long INFO = 0x1549A966L;
    private static final long TITLE = 0x7BA9L;
    private static final long CLUSTER = 0x1F43B675L;
    private static final long TAGS = 0x1254C367L;
    private static final long TAG = 0x7373L;
    private static final long SIMPLE_TAG = 0x67C8L;
    private static final long TAG_NAME = 0x45A3L;
    private static final long TAG_STRING = 0x4487L;
    private static final long ATTACHMENTS = 0x1941A469L;
    private static final long ATTACHED_FILE = 0x61A7L;

    private static byte[] be32(long v) {
        return ByteBuffer.allocate(4).putInt((int) v).array();
    }

    private File file(byte[] content) throws IOException {
        return TestVideos.write(folder.getRoot(), "v.bin", content);
    }

    private VideoContainerMetadata.Report scan(byte[] content) throws IOException {
        return VideoContainerMetadata.scan(file(content));
    }

    /** Scrubs and checks the file is clean afterwards, the same length, with its samples kept. */
    private void scrubAndCheck(byte[] content) throws IOException {
        File f = file(content);
        VideoContainerMetadata.scrub(f);
        assertEquals(content.length, f.length());
        assertTrue(contains(f, SAMPLES));
        VideoContainerMetadata.Report after = VideoContainerMetadata.scan(f);
        assertTrue("left behind: " + after.fields, after.isClean());
    }

    private static byte[] webm(byte[]... segmentChildren) {
        byte[] cluster = ebml(CLUSTER, ebml(0xE7L, new byte[] {0}), ebml(0xA3L, SAMPLES));
        return concat(ebml(0x1A45DFA3L, ebml(0x4282L, bytes("webm"))),
                ebml(SEGMENT, concat(concat(segmentChildren), cluster)));
    }

    // ---- MP4 ----------------------------------------------------------------------------------

    @Test
    public void userData_readsEveryChildShape() throws Exception {
        byte[] otherUuid = box("uuid", new byte[16], bytes("<location>here</location>"));
        byte[] untilTheEnd = concat(be32(0), "©cmt".getBytes(StandardCharsets.ISO_8859_1), bytes("runs to the end"));
        byte[] mp4 = mp4(0, box("udta", box("free", new byte[4]), box("XMP_", bytes("<GPSLatitude/>")),
                otherUuid, largeBox("©nam", bytes("Name")), untilTheEnd));

        VideoContainerMetadata.Report r = scan(mp4);
        assertEquals(List.of("XMP", "uuid", "©nam", "©cmt"), r.fields);
        assertTrue(r.hasLocation);
        scrubAndCheck(mp4);
    }

    @Test
    public void userData_endsAtZeroPadding_andCountsWhatCannotBeParsed() throws Exception {
        assertEquals(List.of("©mak"), scan(mp4(0, box("udta", box("©mak", bytes("Acme")), new byte[8]))).fields);
        assertEquals(List.of("udta"), scan(mp4(0, box("udta", be32(100), "©mak".getBytes(StandardCharsets.ISO_8859_1)))).fields);
    }

    @Test
    public void isoMeta_readsEveryChildType() throws Exception {
        byte[] meta = box("meta", new byte[4], hdlr("mdir"), box("free", new byte[2]),
                box("uuid", new byte[4]), box("xml ", bytes("<GPS/>")), box("abcd", new byte[1]));

        VideoContainerMetadata.Report r = scan(mp4(0, meta));
        assertEquals(List.of("uuid", "XMP", "abcd"), r.fields);
        assertTrue(r.hasLocation);
        scrubAndCheck(mp4(0, meta));
    }

    @Test
    public void metaBoxes_thatCannotBeParsed_stillCount_butEmptyOnesDoNot() throws Exception {
        assertEquals(List.of("meta"), scan(mp4(0, box("meta", new byte[4], be32(100), bytes("hdlr")))).fields);
        assertTrue(scan(mp4(0, box("meta", new byte[4]))).isClean());
    }

    @Test
    public void mdtaItems_withoutUsableKeys_areStillFound() throws Exception {
        byte[] item = concat(be32(16), be32(1), box("data", new byte[0]));
        byte[] shortKeys = box("meta", hdlr("mdta"), box("keys", new byte[4]), box("ilst", item));
        byte[] badKey = box("meta", hdlr("mdta"), box("keys", be32(0), be32(1), be32(4), bytes("mdta")),
                box("ilst", item));

        assertEquals(1, scan(mp4(0, shortKeys)).fields.size());
        assertEquals(1, scan(mp4(0, badKey)).fields.size());
        assertEquals(List.of("ilst"), scan(mp4(0, box("meta", hdlr("mdta"),
                box("ilst", be32(100), "©nam".getBytes(StandardCharsets.ISO_8859_1))))).fields);
    }

    @Test
    public void tracksWithoutAReadableHandler_areNotTimedMetadata() throws Exception {
        byte[] noMedia = box("trak", header("tkhd", 0, 72));
        byte[] shortHandler = box("trak", box("mdia", box("hdlr", new byte[4])));

        assertTrue(scan(mp4(0, noMedia, shortHandler)).isClean());
    }

    @Test
    public void headerTimes_readBothVersions_andSkipShortHeaders() throws Exception {
        byte[] version1 = box("mvhd", new byte[] {1, 0, 0, 0}, new byte[] {0, 0, 0, 0, 1, 2, 3, 4},
                new byte[] {0, 0, 0, 0, 1, 2, 3, 4});
        byte[] tooShortForFlags = box("mvhd", new byte[] {0, 0});
        byte[] tooShortForTimes = box("mvhd", new byte[] {0, 0, 0, 0, 1});

        assertEquals(List.of("date"), scan(mp4(0, version1, tooShortForFlags, tooShortForTimes)).fields);
        assertTrue(scan(mp4(0, tooShortForFlags, tooShortForTimes)).isClean());
        scrubAndCheck(mp4(0, version1));
    }

    @Test
    public void aLastBoxOfSizeZero_runsToTheEnd() throws Exception {
        byte[] mp4 = concat(ftyp(), box("moov", header("mvhd", 0, 96)), be32(0), bytes("mdat"), SAMPLES);

        assertTrue(scan(mp4).isClean());
    }

    @Test
    public void damagedBoxes_areNotRecognized() throws Exception {
        byte[][] damaged = {
                concat(ftyp(), be32(1), bytes("free"), new byte[4]), // large size cut off
                concat(ftyp(), be32(4), bytes("free")), // smaller than its header
                mp4(0, box("trak", box("mdia", be32(4), bytes("abcd")))), // child smaller than a header
        };
        for (byte[] content : damaged) {
            assertFalse(scan(content).recognized);
            File f = file(content);
            assertThrows(IOException.class, () -> VideoContainerMetadata.scrub(f));
        }
    }

    // ---- MP4: what each field holds -----------------------------------------------------------

    private static byte[] iso(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] ilstItem(String type, int dataType, byte[] payload) {
        return concat(be32(8 + 16 + payload.length), iso(type), be32(16 + payload.length), bytes("data"),
                be32(dataType), be32(0), payload);
    }

    @Test
    public void anXmpPacketWithGps_isALocationField_butOneWithoutIsNot() throws Exception {
        VideoContainerMetadata.Report withGps = scan(mp4(0, box("udta",
                box("XMP_", bytes("<x:xmpmeta><exif:GPSLatitude>40,41.3N</exif:GPSLatitude></x:xmpmeta>")))));
        assertTrue(withGps.isLocationField("XMP"));
        assertTrue(withGps.values.get("XMP").contains("GPSLatitude"));

        VideoContainerMetadata.Report without = scan(mp4(0, box("udta",
                box("XMP_", bytes("<x:xmpmeta><dc:title>Holiday</dc:title></x:xmpmeta>")))));
        assertFalse(without.isLocationField("XMP"));
        // A tag named for a place counts whatever it holds.
        assertTrue(without.isLocationField("©xyz"));
    }

    @Test
    public void userDataAtoms_showTheirText_andTheirSize() throws Exception {
        byte[] make = concat(new byte[] {0, 4, 0x15, (byte) 0xC7}, bytes("Acme"));
        VideoContainerMetadata.Report r = scan(mp4(0, box("udta", concat(be32(8 + make.length), iso("©mak"), make),
                TestVideos.itunesMeta("©nam", "Holiday"))));

        assertEquals("Acme", r.values.get("©mak"));
        assertEquals("Holiday", r.values.get("©nam"));
        assertEquals(Long.valueOf(make.length), r.sizes.get("©mak"));
    }

    @Test
    public void ilstItems_areReadByTheirDataType() throws Exception {
        byte[] ilst = box("ilst",
                ilstItem("tmpo", 21, new byte[] {0x07, (byte) 0xE8}),
                ilstItem("cpil", 22, new byte[] {(byte) 0xFF}),
                ilstItem("©cmt", 2, "Hi".getBytes(StandardCharsets.UTF_16BE)),
                ilstItem("covr", 13, new byte[] {(byte) 0xFF, (byte) 0xD8}),
                ilstItem("©too", 0, concat(new byte[] {1, 2}, bytes("Lavf60.3.100"))));
        VideoContainerMetadata.Report r = scan(mp4(0, box("udta", box("meta", new byte[4], hdlr("mdir"), ilst))));

        assertEquals("2024", r.values.get("tmpo"));
        assertEquals("255", r.values.get("cpil"));
        assertEquals("Hi", r.values.get("©cmt"));
        assertFalse(r.values.containsKey("covr"));
        assertTrue(r.sizes.containsKey("covr"));
        assertEquals("Lavf60.3.100", r.values.get("©too"));
    }

    @Test
    public void mdtaKeys_showTheirValues_andALocationKeyGivesCoordinates() throws Exception {
        VideoContainerMetadata.Report r = scan(mp4(0, TestVideos.mdtaMeta(
                "com.apple.quicktime.location.ISO6709", "+40.6892-074.0445+010.000/",
                "com.apple.quicktime.make", "Apple")));

        assertEquals("Apple", r.values.get("com.apple.quicktime.make"));
        assertEquals(40.6892, r.coordinates[0], 1e-9);
        assertEquals(-74.0445, r.coordinates[1], 1e-9);
    }

    @Test
    public void lociBoxes_giveTheirPlaceAndCoordinates() throws Exception {
        byte[] loci = box("loci", new byte[4], new byte[] {0x15, (byte) 0xC7}, bytes("Paris"), new byte[] {0, 0},
                be32(154154), be32(3201866), be32(0), bytes("earth"), new byte[] {0, 0});
        VideoContainerMetadata.Report r = scan(mp4(0, box("udta", loci)));

        assertEquals("Paris", r.values.get("loci"));
        assertTrue(r.hasLocation);
        assertEquals(48.8566, r.coordinates[0], 1e-4);
        assertEquals(2.3522, r.coordinates[1], 1e-4);

        byte[] unnamed = box("loci", new byte[6], new byte[] {0, 0}, be32(154154), be32(3201866), be32(0));
        assertTrue(scan(mp4(0, box("udta", unnamed))).values.get("loci").startsWith("+48.8565"));
        assertFalse(scan(mp4(0, box("udta", box("loci", new byte[20])))).values.containsKey("loci"));
    }

    @Test
    public void iso6709Atoms_giveCoordinates() throws Exception {
        VideoContainerMetadata.Report r = scan(mp4(0, box("udta", box("©xyz", bytes(TestVideos.LOCATION)))));

        assertEquals(TestVideos.LOCATION, r.values.get("©xyz"));
        assertEquals(40.6892, r.coordinates[0], 1e-9);
    }

    @Test
    public void theRecordingDate_isShown() throws Exception {
        assertEquals("2026-01-18 10:30:00 UTC", scan(mp4(TestVideos.RECORDED_1904)).values.get("date"));
    }

    @Test
    public void scrubbing_keepsNoValues() throws Exception {
        byte[] mp4 = mp4(TestVideos.RECORDED_1904, box("udta", box("©xyz", bytes(TestVideos.LOCATION))));
        scrubAndCheck(mp4);
    }

    // ---- WebM: what each field holds ----------------------------------------------------------

    @Test
    public void webmTitleDateTagsAndAttachments_showWhatTheyHold() throws Exception {
        byte[] date = ByteBuffer.allocate(8).putLong(790_425_000_000_000_000L).array();
        byte[] info = ebml(INFO, ebml(TITLE, bytes("Holiday at home")), ebml(0x4461L, date));
        byte[] tags = ebml(TAGS, ebml(TAG,
                ebml(SIMPLE_TAG, ebml(TAG_NAME, bytes("LOCATION")), ebml(TAG_STRING, bytes(TestVideos.LOCATION))),
                ebml(SIMPLE_TAG, ebml(TAG_NAME, bytes("COMMENT")), ebml(TAG_STRING, bytes("secret comment"))),
                ebml(SIMPLE_TAG, ebml(TAG_NAME, bytes("BLOB")), ebml(0x4485L, concat(new byte[] {1}, bytes("Inside blob"))))));
        byte[] attachments = ebml(ATTACHMENTS, ebml(ATTACHED_FILE,
                ebml(0x466EL, bytes("photo.jpg")), ebml(0x4660L, bytes("image/jpeg")), ebml(0x465CL, new byte[] {1})));
        VideoContainerMetadata.Report r = scan(webm(info, tags, attachments));

        assertEquals("Holiday at home", r.values.get("title"));
        assertEquals("2026-01-18 10:30:00 UTC", r.values.get("date"));
        assertEquals("secret comment", r.values.get("COMMENT"));
        assertEquals("Inside blob", r.values.get("BLOB"));
        assertEquals("photo.jpg (image/jpeg)", r.values.get("attachment"));
        assertEquals(40.6892, r.coordinates[0], 1e-9);
        scrubAndCheck(webm(info, tags, attachments));
    }

    // ---- WebM ---------------------------------------------------------------------------------

    @Test
    public void attachments_areFound_andVoided() throws Exception {
        byte[] named = ebml(ATTACHMENTS, ebml(ATTACHED_FILE, ebml(0x466EL, bytes("photo.jpg"))));
        byte[] unnamed = ebml(ATTACHMENTS, ebml(0x4242L, bytes("x")));
        byte[] unknownSize = ebml(ATTACHMENTS, ebmlUnknownSize(ATTACHED_FILE));

        assertEquals(List.of("attachment", "attachments", "attachments"), scan(webm(named, unnamed, unknownSize)).fields);
        scrubAndCheck(webm(named, unnamed));
    }

    @Test
    public void tags_withoutReadableSimpleTags_stillCount() throws Exception {
        byte[] noSimpleTag = ebml(TAGS, ebml(TAG, ebml(0x63C0L, new byte[0])));
        byte[] unknownSizeTag = ebml(TAGS, ebmlUnknownSize(TAG));

        assertEquals(List.of("tags", "tags"), scan(webm(noSimpleTag, unknownSizeTag)).fields);
        scrubAndCheck(webm(noSimpleTag));
    }

    @Test
    public void simpleTags_withoutAName_andNestedOnes_areEachFound() throws Exception {
        byte[] tags = ebml(TAGS, ebml(TAG,
                ebml(SIMPLE_TAG, ebml(TAG_STRING, bytes("value"))),
                ebml(SIMPLE_TAG, ebml(TAG_NAME, bytes("ARTIST")),
                        ebml(SIMPLE_TAG, ebml(TAG_NAME, bytes("GPS_POSITION")))),
                ebml(SIMPLE_TAG, ebmlUnknownSize(TAG_STRING))));

        VideoContainerMetadata.Report r = scan(webm(tags));
        assertEquals(List.of("tag", "ARTIST", "GPS_POSITION", "tag"), r.fields);
        assertTrue(r.hasLocation);
    }

    @Test
    public void aShortTitle_isVoidedWithAOneByteSize() throws Exception {
        byte[] title = {0x7B, (byte) 0xA9, (byte) 0x82, 'h', 'i'};
        byte[] webm = webm(ebml(INFO, title));
        assertEquals(List.of("title"), scan(webm).fields);

        File f = file(webm);
        VideoContainerMetadata.scrub(f);
        assertTrue(contains(f, new byte[] {(byte) 0xEC, (byte) 0x83, 0, 0, 0}));
        assertTrue(VideoContainerMetadata.scan(f).isClean());
    }

    @Test
    public void infoWithAnUnknownSizeChild_stopsThere() throws Exception {
        assertTrue(scan(webm(ebml(INFO, ebmlUnknownSize(TITLE)))).isClean());
    }

    @Test
    public void aSegmentOfUnknownSize_isReadToTheEnd() throws Exception {
        byte[] webm = concat(ebml(0x1A45DFA3L, ebml(0x4282L, bytes("webm"))),
                ebmlUnknownSize(SEGMENT, ebml(INFO, ebml(TITLE, bytes("Holiday")))));

        assertEquals(List.of("title"), scan(webm).fields);
    }

    @Test
    public void damagedElements_areNotRecognized() throws Exception {
        byte[][] segments = {
                {0x00, (byte) 0x81, 0}, // an id cannot start with zero
                {0x08, 0, 0, 0, 0, (byte) 0x81, 0}, // a five-byte id
                {(byte) 0xEC, 0x00}, // a size cannot start with zero
                {(byte) 0xEC, (byte) 0x85, 1}, // runs past its parent
        };
        for (byte[] segment : segments) {
            byte[] content = concat(ebml(0x1A45DFA3L, ebml(0x4282L, bytes("webm"))), ebml(SEGMENT, segment));
            assertFalse(scan(content).recognized);
            File f = file(content);
            assertThrows(IOException.class, () -> VideoContainerMetadata.scrub(f));
        }
    }

    @Test
    public void aCleanWebm_staysClean() throws Exception {
        byte[] webm = webm(ebml(INFO, ebml(0x4D80L, bytes("Lavf"))));

        assertTrue(scan(webm).isClean());
    }
}
