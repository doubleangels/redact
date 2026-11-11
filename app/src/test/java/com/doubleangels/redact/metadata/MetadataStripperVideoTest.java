package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowMediaExtractor;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Video cleaning with Robolectric's emulated MediaExtractor / MediaMuxer / MediaMetadataRetriever. */
@RunWith(RobolectricTestRunner.class)
public class MetadataStripperVideoTest {

    /** The result of the latest clean in this test, for the checks that follow it. */
    private com.doubleangels.redact.metadata.CleanResult lastResult;

    @Rule
    public Timeout timeout = Timeout.seconds(40);

    private Application app;
    private MetadataStripper stripper;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        stripper = new MetadataStripper(app);
        AppPreferences.setStrictClean(app, false);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        ShadowMediaExtractor.class.getName();
        for (File f : files) {
            f.delete();
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private File videoFile(String ext) throws IOException {
        File f = File.createTempFile("vid_", ext, app.getFilesDir());
        files.add(f);
        try (FileOutputStream out = new FileOutputStream(f)) {
            // A minimal real container: verification reads the boxes and refuses what it can't.
            out.write(TestVideos.cleanMp4());
        }
        return f;
    }

    private static MediaFormat videoFormat(String mime) {
        return MediaFormat.createVideoFormat(mime, 320, 240);
    }

    private static MediaFormat audioFormat(String mime) {
        return MediaFormat.createAudioFormat(mime, 44100, 2);
    }

    private DataSource source(Uri uri) {
        return DataSource.toDataSource(app, uri);
    }

    private Uri withTracks(File file, MediaFormat... formats) {
        Uri uri = Uri.fromFile(file);
        for (MediaFormat format : formats) {
            ShadowMediaExtractor.addTrack(source(uri), format, new byte[] {1, 2, 3, 4});
        }
        return uri;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private MetadataStripper.VideoPrivacySnapshot snapshot(String location, String date) {
        return new MetadataStripper.VideoPrivacySnapshot(location, date);
    }

    // ---- detectVideoFormatIndex -------------------------------------------------------------------

    @Test
    public void detectFormat_prefersTheFileExtension() throws Exception {
        Uri uri = Uri.fromFile(videoFile(".bin"));
        assertEquals(2, stripper.detectVideoFormatIndex(uri, "clip.WEBM"));
        assertEquals(2, stripper.detectVideoFormatIndex(uri, "clip.vp9"));
        assertEquals(2, stripper.detectVideoFormatIndex(uri, "clip.vp8"));
        assertEquals(0, stripper.detectVideoFormatIndex(uri, "clip.mp4"));
        assertEquals(0, stripper.detectVideoFormatIndex(uri, "clip.m4v"));
    }

    @Test
    public void detectFormat_readsTheCodecFromTheContainerOtherwise() throws Exception {
        assertEquals(1, stripper.detectVideoFormatIndex(
                withTracks(videoFile(".mkv"), videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC)), "clip.mkv"));
        assertEquals(2, stripper.detectVideoFormatIndex(
                withTracks(videoFile(".mkv"), videoFormat(MediaFormat.MIMETYPE_VIDEO_VP9)), "clip.mkv"));
        assertEquals(2, stripper.detectVideoFormatIndex(
                withTracks(videoFile(".mkv"), videoFormat(MediaFormat.MIMETYPE_VIDEO_VP8)), null));
        assertEquals(3, stripper.detectVideoFormatIndex(
                withTracks(videoFile(".mkv"), videoFormat(MediaFormat.MIMETYPE_VIDEO_AV1)), "clip.mkv"));
        assertEquals(0, stripper.detectVideoFormatIndex(
                withTracks(videoFile(".mkv"), videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC)), "clip.mkv"));
    }

    @Test
    public void detectFormat_unreadableContainerDefaultsToH264() {
        assertEquals(0, stripper.detectVideoFormatIndex(Uri.fromFile(new File("/nonexistent/x.mkv")), "x.mkv"));
    }

    // ---- transmux ---------------------------------------------------------------------------------------

    @Test
    public void transmux_keepsFirstVideoAndAudioTrackInAnMp4() throws Exception {
        Uri uri = withTracks(videoFile(".mp4"),
                videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC), audioFormat(MediaFormat.MIMETYPE_AUDIO_AAC));

        File out = stripper.transmuxVideoWithoutMetadata(uri);

        assertNotNull(out);
        assertTrue(out.getName().endsWith(".mp4"));
        stripper.deleteTempFile(out);
    }

    @Test
    public void transmux_usesWebmForVp9OrOpusSources() throws Exception {
        Uri vp9 = withTracks(videoFile(".webm"), videoFormat(MediaFormat.MIMETYPE_VIDEO_VP9));
        File out = stripper.transmuxVideoWithoutMetadata(vp9);
        assertNotNull(out);
        assertTrue(out.getName().endsWith(".webm"));
        stripper.deleteTempFile(out);

        Uri opus = withTracks(videoFile(".webm"), audioFormat("audio/opus"));
        File audioOnly = stripper.transmuxVideoWithoutMetadata(opus);
        assertNotNull(audioOnly);
        assertTrue(audioOnly.getName().endsWith(".webm"));
        stripper.deleteTempFile(audioOnly);
    }

    @Test
    public void transmux_appliesTheRotationFromTheFormatOrTheRetriever() throws Exception {
        MediaFormat rotated = videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC);
        rotated.setInteger(MediaFormat.KEY_ROTATION, 90);
        File viaFormat = stripper.transmuxVideoWithoutMetadata(withTracks(videoFile(".mp4"), rotated));
        assertNotNull(viaFormat);
        stripper.deleteTempFile(viaFormat);

        File src = videoFile(".mp4");
        Uri uri = withTracks(src, videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC));
        ShadowMediaMetadataRetriever.addMetadata(source(uri), MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION, "180");
        File viaRetriever = stripper.transmuxVideoWithoutMetadata(uri);
        assertNotNull(viaRetriever);
        stripper.deleteTempFile(viaRetriever);
    }

    @Test
    public void transmux_extraTracksAreRefusedInStrictMode_andTrimmedOtherwise() throws Exception {
        Uri uri = withTracks(videoFile(".mp4"),
                videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC), videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC),
                audioFormat(MediaFormat.MIMETYPE_AUDIO_AAC));

        AppPreferences.setStrictClean(app, true);
        assertNull(stripper.transmuxVideoWithoutMetadata(uri));

        AppPreferences.setStrictClean(app, false);
        File out = stripper.transmuxVideoWithoutMetadata(uri);
        assertNotNull(out);
        stripper.deleteTempFile(out);
    }

    @Test
    public void transmux_withoutTracksOrWhenCancelled_returnsNull() throws Exception {
        assertNull(stripper.transmuxVideoWithoutMetadata(Uri.fromFile(videoFile(".mp4"))));

        Uri uri = withTracks(videoFile(".mp4"), videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC));
        stripper.requestCancellation();
        assertNull(stripper.transmuxVideoWithoutMetadata(uri));
    }

    @Test
    public void transmux_stopsWhenTheExternalCancellationCheckFires() throws Exception {
        Uri uri = withTracks(videoFile(".mp4"), videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC));
        int[] polls = {0};
        stripper.setCancellationCheck(() -> {
            polls[0]++;
            return true;
        });

        assertNull(stripper.transmuxVideoWithoutMetadata(uri));
        assertTrue("the remux loop must poll the check", polls[0] > 0);

        // Without a firing check the same source transmuxes normally.
        stripper.setCancellationCheck(() -> false);
        File out = stripper.transmuxVideoWithoutMetadata(uri);
        assertNotNull(out);
        stripper.deleteTempFile(out);
    }

    @Test
    public void transmux_unreadableSource_returnsNull() {
        assertNull(stripper.transmuxVideoWithoutMetadata(Uri.fromFile(new File("/nonexistent/x.mp4"))));
    }

    @Test
    public void transmux_testOverrideShortCircuits() throws Exception {
        File forced = videoFile(".mp4");
        stripper.setTestFastStripVideoMetadataOverride(forced);
        assertEquals(forced, stripper.transmuxVideoWithoutMetadata(Uri.fromFile(new File("/nonexistent/x.mp4"))));
    }

    // ---- verification -----------------------------------------------------------------------------------

    private File outputWith(int key, String value) throws IOException {
        File f = videoFile(".mp4");
        ShadowMediaMetadataRetriever.addMetadata(f.getAbsolutePath(), key, value);
        return f;
    }

    @Test
    public void verify_passesForAFileWithoutPrivacyMetadata() throws Exception {
        assertTrue(stripper.verifyVideoMetadataRemoval(videoFile(".mp4"), snapshot(null, null)));
    }

    @Test
    public void verify_failsOnRemainingLocation() throws Exception {
        File f = outputWith(MediaMetadataRetriever.METADATA_KEY_LOCATION, "+39.6594-104.9620/");
        assertFalse(stripper.verifyVideoMetadataRemoval(f, snapshot(null, null)));
    }

    @Test
    public void verify_failsWhenTheSourceDateSurvives() throws Exception {
        File f = outputWith(MediaMetadataRetriever.METADATA_KEY_DATE, "20240102T030405.000Z");
        assertFalse(stripper.verifyVideoMetadataRemoval(f, snapshot(null, "20240102T030405.000Z")));
        // A different (new) date is fine.
        assertTrue(stripper.verifyVideoMetadataRemoval(f, snapshot(null, "20200101T000000.000Z")));
        assertTrue(stripper.verifyVideoMetadataRemoval(f, snapshot(null, null)));
    }

    @Test
    public void verify_failsOnEachAdditionalPrivacyKey() throws Exception {
        int[] keys = {
                MediaMetadataRetriever.METADATA_KEY_AUTHOR, MediaMetadataRetriever.METADATA_KEY_WRITER,
                MediaMetadataRetriever.METADATA_KEY_ALBUM, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
                MediaMetadataRetriever.METADATA_KEY_ARTIST, MediaMetadataRetriever.METADATA_KEY_COMPOSER,
                MediaMetadataRetriever.METADATA_KEY_TITLE};
        for (int key : keys) {
            File f = outputWith(key, "someone");
            assertFalse("key " + key, stripper.verifyVideoMetadataRemoval(f, snapshot(null, null)));
        }
        assertTrue("whitespace-only values do not count",
                stripper.verifyVideoMetadataRemoval(
                        outputWith(MediaMetadataRetriever.METADATA_KEY_TITLE, "   "), snapshot(null, null)));
    }

    @Test
    public void verify_unreadableFileCountsAsFailure() {
        File broken = new File("/nonexistent/none.mp4");
        ShadowMediaMetadataRetriever.addException(
                DataSource.toDataSource(broken.getAbsolutePath()), new RuntimeException("unreadable"));
        assertFalse(stripper.verifyVideoMetadataRemoval(broken, snapshot(null, null)));
    }

    @Test
    public void requireConvertedVideoClean_readsTheSourceSnapshotAndVerifies() throws Exception {
        File source = videoFile(".mp4");
        Uri sourceUri = Uri.fromFile(source);
        ShadowMediaMetadataRetriever.addMetadata(
                source(sourceUri), MediaMetadataRetriever.METADATA_KEY_DATE, "20240102T030405.000Z");
        ShadowMediaMetadataRetriever.addMetadata(
                source(sourceUri), MediaMetadataRetriever.METADATA_KEY_LOCATION, "+10.0+20.0/");

        stripper.requireConvertedVideoClean(videoFile(".mp4"), sourceUri);

        File leaky = outputWith(MediaMetadataRetriever.METADATA_KEY_DATE, "20240102T030405.000Z");
        try {
            stripper.requireConvertedVideoClean(leaky, sourceUri);
            fail("expected the surviving date to fail verification");
        } catch (IOException expected) {
            assertEquals("Video metadata verification failed", expected.getMessage());
        }
    }

    @Test
    public void requireConvertedVideoClean_ignoresTheUnsetDatePlaceholder() throws Exception {
        File source = videoFile(".mp4");
        Uri sourceUri = Uri.fromFile(source);
        ShadowMediaMetadataRetriever.addMetadata(
                source(sourceUri), MediaMetadataRetriever.METADATA_KEY_DATE, "19040101T000000.000Z");
        File out = outputWith(MediaMetadataRetriever.METADATA_KEY_DATE, "19040101T000000.000Z");

        stripper.requireConvertedVideoClean(out, sourceUri);
    }

    @Test
    public void requireConvertedVideoClean_unreadableSourceStillVerifiesTheOutput() throws Exception {
        stripper.requireConvertedVideoClean(videoFile(".mp4"), Uri.fromFile(new File("/nonexistent/src.mp4")));
    }

    // ---- end to end through the fast path ------------------------------------------------------------------

    @Test
    public void stripVideo_fastPath_savesTheTransmuxedVideoToMovies() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setVideoFallbackCopy(app, true);
        File clean = videoFile(".mp4");
        stripper.setTestFastStripVideoMetadataOverride(clean);
        Uri source = Uri.fromFile(videoFile(".mp4"));
        List<Integer> progress = new ArrayList<>();
        stripper.setProgressCallback((p, m) -> progress.add(p));

        Uri out = (lastResult = stripper.cleanVideo(source, "holiday.mp4")).uri();

        assertNotNull(out);
        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertTrue(entry.displayName().endsWith(".mp4"));
        assertFalse(entry.displayName().contains("holiday"));
        assertEquals("video/mp4", entry.mimeType());
        assertFalse(entry.isPending());
        assertEquals(TestVideos.cleanMp4().length, entry.file.length());
        assertEquals(Integer.valueOf(100), progress.get(progress.size() - 1));
    }

    private File writeVideo(byte[] content) throws IOException {
        File f = TestVideos.write(app.getFilesDir(), "v.mp4", content);
        files.add(f);
        return f;
    }

    @Test
    public void stripVideo_scrubsWhatTheMuxerLeftBehind_beforeSaving() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setVideoFallbackCopy(app, true);
        // As MediaMuxer writes it: the Android version key and the time of cleaning in every
        // header, plus a location the platform retriever cannot see.
        File muxed = writeVideo(TestVideos.mp4(TestVideos.RECORDED_1904,
                TestVideos.mdtaMeta("com.android.version", "17"),
                TestVideos.box("udta", TestVideos.box("loci", TestVideos.bytes(TestVideos.LOCATION)))));
        long length = muxed.length();
        stripper.setTestFastStripVideoMetadataOverride(muxed);

        Uri out = (lastResult = stripper.cleanVideo(Uri.fromFile(writeVideo(TestVideos.cleanMp4())), "a.mp4")).uri();

        assertNotNull(out);
        File saved = FakeMediaStoreProvider.only().file;
        assertEquals(length, saved.length());
        assertTrue(VideoContainerMetadata.scan(saved).fields.toString(), VideoContainerMetadata.scan(saved).isClean());
        assertFalse(TestVideos.contains(saved, TestVideos.LOCATION));
        assertFalse(TestVideos.contains(saved, "com.android.version"));
        assertTrue(TestVideos.contains(saved, TestVideos.SAMPLES));
    }

    @Test
    public void stripVideo_countsTagsThePlatformCannotSee() throws Exception {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setVideoFallbackCopy(app, true);
        stripper.setTestFastStripVideoMetadataOverride(writeVideo(TestVideos.cleanMp4()));
        File source = TestVideos.resource(app.getFilesDir(), "video/ffmpeg_location_keys.mp4");
        files.add(source);

        assertNotNull((lastResult = stripper.cleanVideo(Uri.fromFile(source), "a.mp4")).uri());

        CleanStats stats = lastResult.stats();
        assertNotNull(stats);
        assertTrue(stats.hadLocation);
        assertTrue(String.valueOf(stats.removedFields), stats.removedFields >= 3);
    }

    private void requireClean(File f) throws Exception {
        call(stripper, "requireVideoMetadataClean",
                new Class<?>[]{File.class, MetadataStripper.VideoPrivacySnapshot.class},
                f, snapshot(null, null));
    }

    @Test
    public void verification_neverPassesAFileWithAMetadataTrack() throws Exception {
        // A metadata track's samples cannot be blanked, so the clean must re-encode instead.
        File f = writeVideo(TestVideos.mp4(0, TestVideos.trak("meta", 0)));
        try {
            requireClean(f);
            fail("a metadata track passed verification");
        } catch (IOException expected) {
            assertEquals("Video metadata verification failed", expected.getMessage());
        }
    }

    @Test
    public void verification_neverPassesAnOutputItCannotRead() throws Exception {
        for (byte[] content : new byte[][]{new byte[64], TestVideos.bytes("not a video")}) {
            try {
                requireClean(writeVideo(content));
                fail("an unreadable output passed verification");
            } catch (IOException expected) {
                // Refused, as it should be.
            }
        }
    }

    @Test
    public void verification_scrubsAndPasses_aMuxerOutputWithLeftovers() throws Exception {
        File f = writeVideo(TestVideos.mp4(TestVideos.RECORDED_1904,
                TestVideos.mdtaMeta("com.android.version", "17"),
                TestVideos.box("udta", TestVideos.mdtaMeta("location", TestVideos.LOCATION))));

        requireClean(f);

        assertTrue(VideoContainerMetadata.scan(f).isClean());
        assertFalse(TestVideos.contains(f, TestVideos.LOCATION));
    }

    @Test
    public void countVideoPrivacyFields_usesTheContainerCountWhenItSeesMore() {
        CleanStats hidden = MetadataStripper.countVideoPrivacyFields(
                new MetadataStripper.VideoPrivacySnapshot(null, null, 0, 3, true));
        org.junit.Assert.assertEquals(3, hidden.removedFields);
        org.junit.Assert.assertTrue(hidden.hadLocation);

        CleanStats retrieverSeesMore = MetadataStripper.countVideoPrivacyFields(
                new MetadataStripper.VideoPrivacySnapshot("+40.7-074.0/", "20250101T000000.000Z", 2, 1, false));
        org.junit.Assert.assertEquals(4, retrieverSeesMore.removedFields);
        org.junit.Assert.assertTrue(retrieverSeesMore.hadLocation);
    }

    @Test
    public void countVideoPrivacyFields_reportsDatesDevicesAndNames() {
        CleanStats stats = MetadataStripper.countVideoPrivacyFields(
                new MetadataStripper.VideoPrivacySnapshot(null, "20250101T000000.000Z", 1,
                        java.util.List.of("©mak", "©mod"), false, true));
        org.junit.Assert.assertEquals(2, stats.removedFields);
        org.junit.Assert.assertTrue(stats.hadDate);
        org.junit.Assert.assertTrue(stats.hadDevice);
        org.junit.Assert.assertTrue(stats.hadName);
        org.junit.Assert.assertFalse(stats.hadLocation);
    }

    // ---- progress mapping ------------------------------------------------------------------------------------

    @Test
    public void transcodeProgress_isMappedIntoTheSecondHalfOfTheItem() throws Exception {
        List<String> seen = new ArrayList<>();
        List<Integer> percents = new ArrayList<>();
        stripper.setProgressCallback((p, m) -> {
            percents.add(p);
            seen.add(m);
        });

        call(stripper, "reportTranscodeProgress", new Class<?>[] {int.class}, 0);
        call(stripper, "reportTranscodeProgress", new Class<?>[] {int.class}, 100);
        call(stripper, "reportTranscodeProgress", new Class<?>[] {int.class}, 250);

        assertEquals(List.of(50, 100, 100), percents);
        assertEquals(3, seen.size());
    }

    @Test
    public void transcodeProgress_afterCancellation_throws() throws Exception {
        stripper.requestCancellation();
        try {
            call(stripper, "reportTranscodeProgress", new Class<?>[] {int.class}, 10);
            fail("expected a cancellation");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
    }

    @Test
    public void cancellation_helpers_throwOnlyWhileCancelled() throws Exception {
        call(stripper, "throwIfCancelled", new Class<?>[0]);
        stripper.requestCancellation();
        try {
            call(stripper, "throwIfCancelled", new Class<?>[0]);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("Processing cancelled", expected.getMessage());
        }
        try {
            call(stripper, "checkCancelled", new Class<?>[0]);
            fail("expected RuntimeException");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof IOException);
        }
        stripper.resetCancellation();
        call(stripper, "checkCancelled", new Class<?>[0]);
    }

    @Test
    public void countVideoPrivacyFields_addsLocationDateAndTheOtherKeys() {
        CleanStats all = MetadataStripper.countVideoPrivacyFields(
                new MetadataStripper.VideoPrivacySnapshot("+40.7-074.0/", "20250101T000000.000Z", 2));
        org.junit.Assert.assertEquals(4, all.removedFields);
        org.junit.Assert.assertTrue(all.hadLocation);

        CleanStats none = MetadataStripper.countVideoPrivacyFields(
                new MetadataStripper.VideoPrivacySnapshot(null, null));
        org.junit.Assert.assertEquals(0, none.removedFields);
        org.junit.Assert.assertFalse(none.hadLocation);
    }

    // ---- VideoStreamMetadata.scan -----------------------------------------------------------------

    /** An Annex B H.264 SEI NAL unit holding one user_data_unregistered message. */
    private static byte[] seiNal(String payload) {
        byte[] text = payload.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[] {0, 0, 0, 1, 0x06, 0x05, (byte) (16 + text.length)}, 0, 7);
        out.write(new byte[16], 0, 16);
        out.write(text, 0, text.length);
        out.write(0x80);
        return out.toByteArray();
    }

    @Test
    public void streamScan_readsTheCodecConfigAndSamplesOfTheVideoTrack() throws Exception {
        Uri uri = Uri.fromFile(videoFile(".mp4"));
        MediaFormat video = videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC);
        video.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(seiNal("x264 - core 164")));
        ShadowMediaExtractor.addTrack(source(uri), audioFormat(MediaFormat.MIMETYPE_AUDIO_AAC), new byte[] {1, 2});
        ShadowMediaExtractor.addTrack(source(uri), video, seiNal("camcorder MDPM"));

        VideoStreamMetadata.Report r = VideoStreamMetadata.scan(app, uri);

        assertNotNull(r);
        assertTrue(r.userDataMessages >= 2);
        assertEquals("x264 - core 164", r.text);
    }

    @Test
    public void streamScan_ofAFileWithoutAnH264OrH265Track_findsNothing() throws Exception {
        Uri uri = withTracks(videoFile(".webm"), videoFormat(MediaFormat.MIMETYPE_VIDEO_VP9),
                audioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS));

        VideoStreamMetadata.Report r = VideoStreamMetadata.scan(app, uri);

        assertNotNull(r);
        assertEquals(0, r.userDataMessages);
        assertNull(r.text);
    }

    @Test
    public void streamScan_ofAnAv1Track_findsItsUserData() throws Exception {
        Uri uri = Uri.fromFile(videoFile(".mp4"));
        // A metadata OBU with a size field: ITU-T T.35, country code 0x26, then the payload.
        byte[] text = "drone telemetry".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] obu = new byte[3 + 2 + text.length];
        obu[0] = (byte) (5 << 3 | 0x02);
        obu[1] = (byte) (2 + text.length);
        obu[2] = 4;
        obu[3] = 0x26;
        obu[4] = 0x00;
        System.arraycopy(text, 0, obu, 5, text.length);
        ShadowMediaExtractor.addTrack(source(uri), videoFormat(MediaFormat.MIMETYPE_VIDEO_AV1), obu);

        VideoStreamMetadata.Report r = VideoStreamMetadata.scan(app, uri);

        assertNotNull(r);
        assertEquals(1, r.userDataMessages);
    }

    @Test
    public void streamScan_ofACodecItCannotRead_answersNull() throws Exception {
        Uri uri = withTracks(videoFile(".3gp"), videoFormat(MediaFormat.MIMETYPE_VIDEO_H263),
                audioFormat(MediaFormat.MIMETYPE_AUDIO_AAC));

        assertNull(VideoStreamMetadata.scan(app, uri));
    }

    @Test
    public void streamScan_ofAnHevcTrackWithoutCodecConfig_readsItsSamples() throws Exception {
        Uri uri = Uri.fromFile(videoFile(".mp4"));
        byte[] sei = seiNal("hevc encoder");
        sei[4] = (byte) (39 << 1); // HEVC prefix SEI header: two bytes
        byte[] hevc = new byte[sei.length + 1];
        System.arraycopy(sei, 0, hevc, 0, 5);
        hevc[5] = 0x01;
        System.arraycopy(sei, 5, hevc, 6, sei.length - 5);
        ShadowMediaExtractor.addTrack(source(uri), videoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC), hevc);

        VideoStreamMetadata.Report r = VideoStreamMetadata.scan(app, uri);

        assertNotNull(r);
        assertTrue(r.userDataMessages >= 1);
        assertEquals("hevc encoder", r.text);
    }

    // ---- remuxWebmToMp4 ---------------------------------------------------------------------------

    /** A VP9 key frame header from libvpx (1280x720, 8-bit 4:2:0), as Vp9CodecConfigTest uses. */
    private static final byte[] VP9_KEY_FRAME = {
            (byte) 0x82, 0x49, (byte) 0x83, 0x42, 0x40, 0x4f, (byte) 0xf0, 0x2c, (byte) 0xf6, 0x0e, 0x38, 0x24};

    private Uri webm(MediaFormat video, byte[] videoSample, MediaFormat audio) throws IOException {
        Uri uri = Uri.fromFile(videoFile(".webm"));
        ShadowMediaExtractor.addTrack(source(uri), video, videoSample);
        if (audio != null) {
            ShadowMediaExtractor.addTrack(source(uri), audio, new byte[] {1, 2, 3, 4});
        }
        return uri;
    }

    private File remux(Uri uri, double[] location) throws Exception {
        return (File) call(stripper, "remuxWebmToMp4", new Class<?>[] {Uri.class, double[].class}, uri, location);
    }

    @Test
    public void remuxWebmToMp4_copiesVp9IntoAnMp4_withOnlyTheKeptLocation() throws Exception {
        MediaFormat vp9 = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, 1280, 720);
        vp9.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        Uri uri = webm(vp9, VP9_KEY_FRAME, null);

        File out = remux(uri, new double[] {52.37, 4.89});

        assertNotNull(out);
        files.add(out);
        // Robolectric's extractor hands out no samples, so the MP4 holds nothing to check here;
        // what this covers is that a VP9 WebM with a key frame goes the whole way to an MP4.
        assertTrue(out.getName().endsWith(".mp4"));
        assertTrue(out.exists());
    }

    @Test
    public void remuxWebmToMp4_leavesEverythingElseAsWebm() throws Exception {
        MediaFormat vp9 = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP9, 1280, 720);
        // Not a WebM at all.
        assertNull(remux(withTracks(videoFile(".mp4"), videoFormat(MediaFormat.MIMETYPE_VIDEO_AVC)), null));
        // VP8, which an MP4 can't hold.
        assertNull(remux(webm(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_VP8, 320, 240),
                new byte[] {1, 2, 3, 4}, audioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS)), null));
        // Sound in neither Opus nor Vorbis.
        assertNull(remux(webm(vp9, VP9_KEY_FRAME, audioFormat(MediaFormat.MIMETYPE_AUDIO_AAC)), null));
        // A first frame that is not a key frame gives no configuration to write.
        assertNull(remux(webm(vp9, new byte[] {(byte) 0x86, 0, 0, 0}, null), null));
    }

    @Test
    public void keptVideoLocation_onlyWithKeepLocation_andOnlyAValidOne() throws Exception {
        Class<?>[] types = {MetadataStripper.VideoPrivacySnapshot.class, Uri.class};
        Uri uri = Uri.fromFile(videoFile(".mp4"));
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveLocation(app, true);

        double[] kept = (double[]) call(stripper, "keptVideoLocation", types, snapshot("+52.3700+004.8900/", null), uri);
        assertNotNull(kept);
        assertEquals(52.37, kept[0], 1e-6);
        assertEquals(4.89, kept[1], 1e-6);
        assertNull(call(stripper, "keptVideoLocation", types, snapshot(null, null), uri));
        assertNull("out of range", call(stripper, "keptVideoLocation", types, snapshot("+95.0000+195.0000/", null), uri));

        AppPreferences.setStrictClean(app, true);
        assertNull(call(stripper, "keptVideoLocation", types, snapshot("+52.3700+004.8900/", null), uri));
    }

    // ---- movie times with 64-bit fields -----------------------------------------------------------

    private static byte[] u32(long v) {
        return java.nio.ByteBuffer.allocate(4).putInt((int) v).array();
    }

    private static byte[] u64(long v) {
        return java.nio.ByteBuffer.allocate(8).putLong(v).array();
    }

    private static byte[] cat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private static byte[] box(String type, byte[] body) {
        return cat(u32(8 + body.length), type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), body);
    }

    @Test
    public void movieTimes_readsAndWritesVersionOneHeaders_pastLargeAndOpenEndedBoxes() throws Exception {
        long created = 3_900_000_000L;
        // mvhd version 1: 64-bit creation and modification times, then the rest of the header.
        byte[] mvhd = box("mvhd", cat(new byte[] {1, 0, 0, 0}, u64(created), u64(created + 60), new byte[96]));
        // An mdat with a 64-bit size, and a free box with size 0, which runs to the end of the file.
        byte[] mdat = cat(u32(1), "mdat".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), u64(16 + 4), new byte[4]);
        byte[] open = cat(u32(0), "free".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), new byte[6]);
        byte[] ftyp = box("ftyp", cat("isom".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), u32(0)));
        File f = File.createTempFile("vid_", ".mp4", app.getFilesDir());
        files.add(f);
        java.nio.file.Files.write(f.toPath(), cat(ftyp, mdat, box("moov", mvhd), open));

        long[] times = VideoContainerMetadata.movieTimes(app, Uri.fromFile(f));
        assertNotNull(times);
        assertEquals(created, times[0]);
        assertEquals(created + 60, times[1]);

        VideoContainerMetadata.writeMovieTimes(f, new long[] {created + 1, created + 2});
        long[] after = VideoContainerMetadata.movieTimes(app, Uri.fromFile(f));
        assertEquals(created + 1, after[0]);
        assertEquals(created + 2, after[1]);
    }

    @Test
    public void movieTimes_ofAWebm_comeFromItsMatroskaDate() throws Exception {
        File webm = File.createTempFile("vid_", ".webm", app.getFilesDir());
        files.add(webm);
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("video/dated.webm")) {
            java.nio.file.Files.write(webm.toPath(), in.readAllBytes());
        }
        assertNotNull(VideoContainerMetadata.movieTimes(app, Uri.fromFile(webm)));
        assertNull(VideoContainerMetadata.movieTimes(app, Uri.fromFile(new File("/no/such/file.mp4"))));
    }
}
