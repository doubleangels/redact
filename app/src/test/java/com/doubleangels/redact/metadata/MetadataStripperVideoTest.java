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

        Uri out = stripper.stripVideoMetadata(source, "holiday.mp4");

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

        Uri out = stripper.stripVideoMetadata(Uri.fromFile(writeVideo(TestVideos.cleanMp4())), "a.mp4");

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

        assertNotNull(stripper.stripVideoMetadata(Uri.fromFile(source), "a.mp4"));

        CleanStats stats = stripper.getLastCleanStats();
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
}
