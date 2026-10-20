package com.doubleangels.redact.metadata;

import static com.doubleangels.redact.metadata.TestVideos.LOCATION;
import static com.doubleangels.redact.metadata.TestVideos.RECORDED_1904;
import static com.doubleangels.redact.metadata.TestVideos.SAMPLES;
import static com.doubleangels.redact.metadata.TestVideos.box;
import static com.doubleangels.redact.metadata.TestVideos.bytes;
import static com.doubleangels.redact.metadata.TestVideos.concat;
import static com.doubleangels.redact.metadata.TestVideos.contains;
import static com.doubleangels.redact.metadata.TestVideos.ftyp;
import static com.doubleangels.redact.metadata.TestVideos.itunesMeta;
import static com.doubleangels.redact.metadata.TestVideos.mdtaMeta;
import static com.doubleangels.redact.metadata.TestVideos.mp4;
import static com.doubleangels.redact.metadata.TestVideos.xmpUuid;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class VideoContainerMetadataTest {

    private File dir;
    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        dir = app.getCacheDir();
    }

    private File file(String name, byte[] content) throws IOException {
        return TestVideos.write(dir, name, content);
    }

    private static VideoContainerMetadata.Report scan(File f) {
        return VideoContainerMetadata.scan(f);
    }

    /** Scrubs and checks the promises scrubbing makes: same length, samples untouched, clean. */
    private static void scrubAndCheck(File f) throws IOException {
        long length = f.length();
        VideoContainerMetadata.scrub(f);
        assertEquals("scrubbing never changes the length", length, f.length());
        assertTrue("the samples survive untouched", contains(f, SAMPLES));
        VideoContainerMetadata.Report after = scan(f);
        assertTrue("left behind: " + after.fields, after.isClean());
    }

    // ---- MP4: finding metadata ----------------------------------------------------------------

    @Test
    public void aCleanMp4_isClean() throws Exception {
        VideoContainerMetadata.Report r = scan(file("clean.mp4", TestVideos.cleanMp4()));

        assertTrue(r.recognized);
        assertTrue(r.isClean());
        assertFalse(r.hasLocation);
    }

    @Test
    public void quickTimeKeys_thePlatformCannotSee_areFound() throws Exception {
        File f = file("keys.mp4", mp4(0, box("udta",
                mdtaMeta("location", LOCATION, "comment", "secret", "major_brand", "isom"),
                box("loci", new byte[20]))));

        VideoContainerMetadata.Report r = scan(f);

        assertEquals(Arrays.asList("location", "comment", "loci"), r.fields);
        assertTrue(r.hasLocation);
        assertFalse(r.isClean());
    }

    @Test
    public void appleLocationKey_inMoovMeta_isFound() throws Exception {
        File f = file("apple.mov", mp4(0, mdtaMeta(
                "com.apple.quicktime.location.ISO6709", LOCATION,
                "com.apple.quicktime.make", "Apple",
                "com.apple.quicktime.model", "iPhone")));

        VideoContainerMetadata.Report r = scan(f);

        assertEquals(3, r.fields.size());
        assertTrue(r.hasLocation);
    }

    @Test
    public void classicUserDataAtoms_areFound() throws Exception {
        File f = file("udta.mp4", mp4(0, box("udta",
                box("©xyz", bytes(LOCATION)), box("©mak", bytes("Google")),
                box("©mod", bytes("Pixel")), box("free", new byte[4]))));

        VideoContainerMetadata.Report r = scan(f);

        assertEquals(List.of("©xyz", "©mak", "©mod"), r.fields);
        assertTrue(r.hasLocation);
    }

    @Test
    public void iTunesStyleTags_areFound() throws Exception {
        VideoContainerMetadata.Report r = scan(file("itunes.mp4",
                mp4(0, box("udta", itunesMeta("©nam", "Holiday")))));

        assertEquals(List.of("©nam"), r.fields);
        assertFalse(r.hasLocation);
    }

    @Test
    public void xmp_isFound_andGpsInsideItCountsAsALocation() throws Exception {
        VideoContainerMetadata.Report withGps = scan(file("xmp.mp4", concat(ftyp(),
                xmpUuid("<x:xmpmeta><exif:GPSLatitude>40,41N</exif:GPSLatitude></x:xmpmeta>"),
                box("moov", TestVideos.header("mvhd", 0, 96)), TestVideos.mdat())));
        VideoContainerMetadata.Report plain = scan(file("xmp2.mp4",
                mp4(0, box("udta", box("XMP_", bytes("<x:xmpmeta>creator</x:xmpmeta>"))))));

        assertEquals(List.of("XMP"), withGps.fields);
        assertTrue(withGps.hasLocation);
        assertEquals(List.of("XMP"), plain.fields);
        assertFalse(plain.hasLocation);
    }

    @Test
    public void theRecordingDate_countsOnceThoughEveryHeaderHasIt() throws Exception {
        VideoContainerMetadata.Report r = scan(file("dated.mp4", mp4(RECORDED_1904)));

        assertEquals(List.of("date"), r.fields);
    }

    @Test
    public void theAndroidVersionKeyThatMediaMuxerWrites_isFound() throws Exception {
        VideoContainerMetadata.Report r = scan(file("muxer.mp4", mp4(RECORDED_1904,
                mdtaMeta("com.android.version", "17"))));

        assertTrue(r.fields.contains("com.android.version"));
        assertTrue(r.fields.contains("date"));
    }

    @Test
    public void aTimedMetadataTrack_isFound() throws Exception {
        VideoContainerMetadata.Report r = scan(file("gopro.mp4", mp4(0, TestVideos.trak("meta", 0))));

        assertEquals(List.of("track:meta"), r.fields);
    }

    @Test
    public void anUnknownTopLevelBox_isFound() throws Exception {
        VideoContainerMetadata.Report r = scan(file("extra.mp4",
                concat(TestVideos.cleanMp4(), box("Xtra", bytes("WM/Author=Bob")))));

        assertEquals(List.of("Xtra"), r.fields);
    }

    @Test
    public void largeSizeBoxes_areRead() throws Exception {
        File f = file("large.mp4", concat(ftyp(),
                TestVideos.largeBox("moov", concat(TestVideos.header("mvhd", 0, 96),
                        box("udta", box("©xyz", bytes(LOCATION))))),
                TestVideos.largeBox("mdat", SAMPLES)));

        VideoContainerMetadata.Report r = scan(f);

        assertTrue(r.hasLocation);
        scrubAndCheck(f);
    }

    @Test
    public void aRealFfmpegFile_withLocationKeysAndLoci() throws Exception {
        File f = TestVideos.resource(dir, "video/ffmpeg_location_keys.mp4");

        VideoContainerMetadata.Report r = scan(f);

        assertTrue(r.fields.containsAll(List.of("location", "comment", "loci")));
        assertTrue(r.hasLocation);
        assertTrue(contains(f, LOCATION));
    }

    // ---- MP4: scrubbing -----------------------------------------------------------------------

    @Test
    public void scrub_removesEveryKindOfMetadata_inPlace() throws Exception {
        File f = file("all.mp4", concat(ftyp(),
                xmpUuid("<exif:GPSLatitude>40</exif:GPSLatitude>"),
                box("moov",
                        TestVideos.header("mvhd", RECORDED_1904, 96),
                        TestVideos.trak("vide", RECORDED_1904),
                        mdtaMeta("com.android.version", "17"),
                        box("udta", mdtaMeta("location", LOCATION), box("loci", bytes(LOCATION)),
                                box("©xyz", bytes(LOCATION)))),
                TestVideos.mdat(),
                box("Xtra", bytes("WM/Author=Bob"))));

        scrubAndCheck(f);

        assertFalse("no copy of the location is left anywhere", contains(f, LOCATION));
        assertFalse(contains(f, "GPSLatitude"));
        assertFalse(contains(f, "com.android.version"));
        assertFalse(contains(f, "Bob"));
    }

    @Test
    public void scrub_keepsTheMediaStructure() throws Exception {
        File f = file("keep.mp4", mp4(RECORDED_1904, box("udta", box("©xyz", bytes(LOCATION)))));

        VideoContainerMetadata.scrub(f);

        assertTrue(contains(f, "ftyp"));
        assertTrue(contains(f, "mvhd"));
        assertTrue(contains(f, "tkhd"));
        assertTrue(contains(f, "hdlr"));
        assertTrue(contains(f, "vide"));
        assertTrue(contains(f, "stsd"));
        assertTrue(contains(f, "mdat"));
    }

    @Test
    public void scrub_ofACleanFile_changesNothing() throws Exception {
        byte[] clean = TestVideos.cleanMp4();
        File f = file("clean.mp4", clean);

        assertEquals(0, VideoContainerMetadata.scrub(f));

        assertTrue(Arrays.equals(clean, java.nio.file.Files.readAllBytes(f.toPath())));
    }

    @Test
    public void scrub_isSafeToRepeat() throws Exception {
        File f = file("twice.mp4", mp4(RECORDED_1904, box("udta", box("loci", bytes(LOCATION)))));

        VideoContainerMetadata.scrub(f);
        byte[] once = java.nio.file.Files.readAllBytes(f.toPath());
        assertEquals(0, VideoContainerMetadata.scrub(f));

        assertTrue(Arrays.equals(once, java.nio.file.Files.readAllBytes(f.toPath())));
    }

    @Test
    public void scrub_ofTheRealFfmpegFile_leavesNoLocation() throws Exception {
        File f = TestVideos.resource(dir, "video/ffmpeg_location_keys.mp4");
        long length = f.length();

        VideoContainerMetadata.scrub(f);

        assertEquals(length, f.length());
        assertTrue(scan(f).isClean());
        assertFalse(contains(f, LOCATION));
        assertFalse(contains(f, "secret comment"));
    }

    @Test
    public void aTimedMetadataTrack_cannotBeScrubbed_soTheFileNeverPassesAsClean() throws Exception {
        File f = file("gopro.mp4", mp4(0, TestVideos.trak("meta", 0)));

        VideoContainerMetadata.scrub(f);

        assertFalse(scan(f).isClean());
    }

    // ---- WebM / Matroska ----------------------------------------------------------------------

    @Test
    public void webm_titleDateAndTags_areFound() throws Exception {
        VideoContainerMetadata.Report r = scan(file("tagged.webm", TestVideos.webm(true, false)));

        assertTrue(r.recognized);
        assertEquals(List.of("title", "date", "LOCATION", "COMMENT"), r.fields);
        assertTrue(r.hasLocation);
    }

    @Test
    public void webm_withoutTags_isClean() throws Exception {
        assertTrue(scan(file("plain.webm", TestVideos.webm(false, false))).isClean());
        assertTrue(scan(file("live.webm", TestVideos.webm(false, true))).isClean());
    }

    @Test
    public void webm_scrub_voidsTheTagsAndKeepsTheVideo() throws Exception {
        File f = file("tagged.webm", TestVideos.webm(true, false));

        scrubAndCheck(f);

        assertFalse(contains(f, LOCATION));
        assertFalse(contains(f, "Holiday"));
        assertFalse(contains(f, "secret comment"));
        assertTrue("the muxing app is not identifying and stays", contains(f, "Lavf"));
    }

    @Test
    public void webm_tagsAfterAClusterOfUnknownSize_areStillFound() throws Exception {
        File f = file("live.webm", TestVideos.webm(true, true));

        assertTrue(scan(f).hasLocation);
        scrubAndCheck(f);
    }

    @Test
    public void aRealFfmpegWebm_withTags() throws Exception {
        File f = TestVideos.resource(dir, "video/ffmpeg_tagged.webm");

        VideoContainerMetadata.Report r = scan(f);

        assertTrue(r.fields.toString(), r.hasLocation);
        assertTrue(r.fields.contains("title"));

        byte[] before = java.nio.file.Files.readAllBytes(f.toPath());
        int cluster = indexOf(before, new byte[]{0x1F, 0x43, (byte) 0xB6, 0x75});
        assertTrue(cluster > 0);
        VideoContainerMetadata.scrub(f);
        byte[] after = java.nio.file.Files.readAllBytes(f.toPath());

        assertEquals(before.length, after.length);
        assertTrue(scan(f).fields.toString(), scan(f).isClean());
        assertFalse(contains(f, "Holiday at home"));
        assertFalse(contains(f, LOCATION));
        assertFalse(contains(f, "secret comment"));
        // The cluster holding the frames is byte for byte what it was.
        int frames = Math.min(64, before.length - cluster);
        assertTrue(Arrays.equals(Arrays.copyOfRange(before, cluster, cluster + frames),
                Arrays.copyOfRange(after, cluster, cluster + frames)));
    }

    @Test
    public void webm_streamDescriptionTags_areNotCounted_butAreStillRemoved() throws Exception {
        byte[] header = TestVideos.ebml(0x1A45DFA3L, TestVideos.ebml(0x4282L, bytes("webm")));
        byte[] tags = TestVideos.ebml(0x1254C367L, TestVideos.ebml(0x7373L,
                TestVideos.ebml(0x67C8L, TestVideos.ebml(0x45A3L, bytes("DURATION")),
                        TestVideos.ebml(0x4487L, bytes("00:00:00.200000000")))));
        byte[] cluster = TestVideos.ebml(0x1F43B675L, TestVideos.ebml(0xA3L, SAMPLES));
        File f = file("stats.webm", concat(header, TestVideos.ebml(0x18538067L, concat(cluster, tags))));

        assertTrue(scan(f).fields.toString(), scan(f).isClean());
        VideoContainerMetadata.scrub(f);
        assertFalse(contains(f, "DURATION"));
        assertTrue(contains(f, SAMPLES));
    }

    @Test
    public void aRealFfmpegWebm_countsOnlyIdentifyingTags() throws Exception {
        VideoContainerMetadata.Report r = scan(TestVideos.resource(dir, "video/ffmpeg_tagged.webm"));

        assertFalse(r.fields.toString(), r.fields.contains("DURATION"));
        assertTrue(r.fields.toString(), r.fields.containsAll(List.of("title", "LOCATION", "COMMENT")));
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ---- what is not a video -------------------------------------------------------------------

    @Test
    public void notAVideo_isNotRecognized_andIsNeverClean() throws Exception {
        for (byte[] content : new byte[][]{
                new byte[0], new byte[64], bytes("not a video at all"),
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'}}) {
            File f = file("junk.mp4", content);
            VideoContainerMetadata.Report r = scan(f);
            assertFalse(r.recognized);
            assertFalse(r.isClean());
            assertThrows(IOException.class, () -> VideoContainerMetadata.scrub(f));
        }
    }

    @Test
    public void aDamagedMp4_isNotRecognized() throws Exception {
        byte[] good = mp4(0, box("udta", box("loci", bytes(LOCATION))));
        // Cuts into the mdat box, which then claims more bytes than the file has.
        File truncated = file("cut.mp4", Arrays.copyOf(good, good.length - 10));
        byte[] lying = good.clone();
        // ftyp claims to be far longer than the file.
        lying[2] = 0x7F;
        File oversized = file("lie.mp4", lying);

        assertFalse(scan(truncated).recognized);
        assertFalse(scan(oversized).recognized);
        assertThrows(IOException.class, () -> VideoContainerMetadata.scrub(truncated));
    }

    @Test
    public void scanningAUri_readsTheFile() throws Exception {
        File f = file("keys.mp4", mp4(0, box("udta", box("loci", bytes(LOCATION)))));

        assertTrue(VideoContainerMetadata.scan(app, Uri.fromFile(f)).hasLocation);
        assertFalse(VideoContainerMetadata.scan(app, Uri.fromFile(new File(dir, "missing.mp4"))).recognized);
    }

    @Test
    public void locationNames() {
        for (String name : List.of("©xyz", "loci", "location", "location-eng",
                "com.apple.quicktime.location.ISO6709", "GPSCoordinates", "RECORDING_LOCATION")) {
            assertTrue(name, VideoContainerMetadata.isLocationName(name));
        }
        for (String name : List.of("©mak", "comment", "encoder", "title", "date")) {
            assertFalse(name, VideoContainerMetadata.isLocationName(name));
        }
    }
}
