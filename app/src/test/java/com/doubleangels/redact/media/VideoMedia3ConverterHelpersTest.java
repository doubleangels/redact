package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.media3.common.MimeTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** The converter's small decisions that run before Media3 does any work. */
@RunWith(RobolectricTestRunner.class)
public class VideoMedia3ConverterHelpersTest {

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = VideoMedia3Converter.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static int[] fallbackOrder(int requested) throws Exception {
        return (int[]) call("encoderFallbackOrder", new Class<?>[] {int.class}, requested);
    }

    @Test
    public void encoderFallbackOrder_triesTheRequestedCodecFirstThenSaferOnes() throws Exception {
        assertArrayEquals(new int[] {}, fallbackOrder(-1));
        assertArrayEquals(new int[] {3, 1, 0}, fallbackOrder(3));
        assertEquals(2, fallbackOrder(2)[0]);
        assertEquals(0, fallbackOrder(0)[0]);
    }

    @Test
    public void everyExportReEncodes_soNothingInsideTheSourceStreamsSurvives() {
        VideoMedia3Converter.AlwaysEncodeFactory factory = new VideoMedia3Converter.AlwaysEncodeFactory(
                new androidx.media3.transformer.DefaultEncoderFactory.Builder(
                        RuntimeEnvironment.getApplication()).build());

        assertTrue(factory.videoNeedsEncoding());
        assertTrue(factory.audioNeedsEncoding());
    }

    @Test
    public void alwaysEncoding_stillAsksTheRealFactoryWhatTheDeviceCanEncode() {
        // Media3 falls back to tone mapping HDR only when the factory says the device cannot
        // encode it, so the wrapper must not answer yes for everything.
        androidx.media3.transformer.Codec.EncoderFactory noHdr =
                new androidx.media3.transformer.Codec.EncoderFactory() {
                    @Override
                    public androidx.media3.transformer.Codec createForAudioEncoding(
                            androidx.media3.common.Format format, android.media.metrics.LogSessionId id) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public androidx.media3.transformer.Codec createForVideoEncoding(
                            androidx.media3.common.Format format, android.media.metrics.LogSessionId id) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public boolean isVideoFormatSupported(androidx.media3.common.Format format) {
                        return false;
                    }
                };

        assertFalse(new VideoMedia3Converter.AlwaysEncodeFactory(noHdr)
                .isVideoFormatSupported(new androidx.media3.common.Format.Builder().build()));
    }

    @Test
    public void audioFollowsTheVideoCodecsUsualPairing() throws Exception {
        Class<?>[] types = {String.class};
        assertEquals(MimeTypes.AUDIO_OPUS, call("audioMimeTypeForVideoMime", types, MimeTypes.VIDEO_VP9));
        assertEquals(MimeTypes.AUDIO_OPUS, call("audioMimeTypeForVideoMime", types, MimeTypes.VIDEO_VP8));
        assertFalse(MimeTypes.AUDIO_OPUS.equals(call("audioMimeTypeForVideoMime", types, MimeTypes.VIDEO_H264)));
    }

    @Test
    public void parseIntOrZero_acceptsPaddedNumbersOnly() throws Exception {
        Class<?>[] types = {String.class};
        assertEquals(0, call("parseIntOrZero", types, (Object) null));
        assertEquals(0, call("parseIntOrZero", types, "wide"));
        assertEquals(1080, call("parseIntOrZero", types, " 1080 "));
    }

    @Test
    public void readSourceShortSide_usesTheSmallerKnownDimension() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File file = File.createTempFile("side_", ".mp4", app.getCacheDir());
        file.deleteOnExit();
        Uri uri = Uri.fromFile(file);
        Class<?>[] types = {android.content.Context.class, Uri.class};

        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app, uri),
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, "3840");
        ShadowMediaMetadataRetriever.addMetadata(DataSource.toDataSource(app, uri),
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, "2160");
        assertEquals(2160, call("readSourceShortSide", types, app, uri));

        File unknown = File.createTempFile("side_", ".mp4", app.getCacheDir());
        unknown.deleteOnExit();
        assertEquals(0, call("readSourceShortSide", types, app, Uri.fromFile(unknown)));

        Uri broken = Uri.fromFile(new File(app.getCacheDir(), "missing.mp4"));
        ShadowMediaMetadataRetriever.addException(DataSource.toDataSource(app, broken),
                new IllegalArgumentException("unreadable"));
        assertEquals(0, call("readSourceShortSide", types, app, broken));
    }

    @Test
    public void downscaledVideo_tellsTheUserItsNewSize() {
        Application app = RuntimeEnvironment.getApplication();
        VideoMedia3Converter.warnDownscaled(app, 720);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(app.getString(com.doubleangels.redact.R.string.video_downscaled, 720),
                org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("720p"));
    }

    @Test
    public void fallbackShortSides_onlyStepsDownFromTheSource() {
        for (int side : VideoMedia3Converter.fallbackShortSides(1080)) {
            assertTrue(side < 1080);
        }
        assertTrue(VideoMedia3Converter.fallbackShortSides(0).length > 0);
    }

    @Test
    public void softwareShortSide_scalesLargeOrUnknownSourcesTo720p_andKeepsSmallOnes() {
        assertEquals(720, VideoMedia3Converter.softwareShortSide(2160));
        assertEquals(720, VideoMedia3Converter.softwareShortSide(0));
        assertEquals(0, VideoMedia3Converter.softwareShortSide(720));
        assertEquals(0, VideoMedia3Converter.softwareShortSide(480));
    }

    @Test
    public void describeAttemptFailure_namesTheCodecSizeAndHowFarItGot() {
        assertEquals(
                "The video/hevc export at full size failed with ERROR_CODE_MUXING_TIMEOUT"
                        + " before any progress after 21 s.",
                VideoMedia3Converter.describeAttemptFailure(
                        MimeTypes.VIDEO_H265, 0, "ERROR_CODE_MUXING_TIMEOUT", -1, 21_400));
        assertEquals(
                "The video/avc export at 720p failed with STALLED at 42% after 600 s.",
                VideoMedia3Converter.describeAttemptFailure(
                        MimeTypes.VIDEO_H264, 720, "STALLED", 42, 600_000));
    }

    @Test
    public void describeVideoFormat_leavesOutWhatTheContainerDoesNotDeclare() {
        assertEquals("video/hevc 3840x2160 60fps 70Mbps",
                VideoMedia3Converter.describeVideoFormat(
                        MimeTypes.VIDEO_H265, 3840, 2160, 60, 69_671_571));
        assertEquals("video/avc",
                VideoMedia3Converter.describeVideoFormat(MimeTypes.VIDEO_H264, 0, 0, 0, 0));
    }

    @Test
    public void transcodeToFile_whenItsFolderCannotBeMade_fails() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File blocker = File.createTempFile("blocker_", ".tmp", app.getCacheDir());
        blocker.deleteOnExit();
        File output = new File(new File(blocker, "sub"), "out.mp4");
        try {
            VideoMedia3Converter.transcodeToFile(app, Uri.fromFile(blocker), output, 0);
            throw new AssertionError("expected a failure");
        } catch (IOException expected) {
            assertEquals("Failed to create output directory", expected.getMessage());
        }
    }

    @Test
    public void transcodeToFile_withAListenerOrOwner_failsTheSameWayWhenItsFolderCannotBeMade() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File blocker = File.createTempFile("blocker_", ".tmp", app.getCacheDir());
        blocker.deleteOnExit();
        File output = new File(new File(blocker, "sub"), "out.mp4");
        try {
            VideoMedia3Converter.transcodeToFile(app, Uri.fromFile(blocker), output, 0, null, 7L);
            throw new AssertionError("expected a failure");
        } catch (IOException expected) {
            assertEquals("Failed to create output directory", expected.getMessage());
        }
    }

    private static androidx.media3.transformer.ExportException exportFailure(Throwable cause) {
        return androidx.media3.transformer.ExportException.createForAssetLoader(
                cause, androidx.media3.transformer.ExportException.ERROR_CODE_UNSPECIFIED);
    }

    @Test
    public void encoderResolutionFailures_areFoundAnywhereInTheCauseChain() {
        assertTrue(VideoMedia3Converter.isEncoderResolutionFailure(
                exportFailure(new IllegalArgumentException("unsupported width"))));
        assertTrue(VideoMedia3Converter.isEncoderResolutionFailure(exportFailure(
                new RuntimeException(new IllegalArgumentException("unsupported height 4320")))));

        assertFalse(VideoMedia3Converter.isEncoderResolutionFailure(
                exportFailure(new IllegalArgumentException("bad bitrate"))));
        assertFalse(VideoMedia3Converter.isEncoderResolutionFailure(
                exportFailure(new IllegalArgumentException())));
        assertFalse(VideoMedia3Converter.isEncoderResolutionFailure(
                exportFailure(new IllegalStateException("unsupported width"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void cancelActiveTranscode_onlyCancelsItsOwnersExport() throws Exception {
        java.lang.reflect.Field active = VideoMedia3Converter.class.getDeclaredField("activeTransformer");
        java.lang.reflect.Field owner = VideoMedia3Converter.class.getDeclaredField("activeTranscodeOwner");
        active.setAccessible(true);
        owner.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<androidx.media3.transformer.Transformer> transformer =
                (java.util.concurrent.atomic.AtomicReference<androidx.media3.transformer.Transformer>) active.get(null);
        java.util.concurrent.atomic.AtomicLong ownerId = (java.util.concurrent.atomic.AtomicLong) owner.get(null);

        // Nothing running: nothing to cancel, whoever asks.
        VideoMedia3Converter.cancelActiveTranscode();
        VideoMedia3Converter.cancelActiveTranscode(3L);

        transformer.set(new androidx.media3.transformer.Transformer.Builder(RuntimeEnvironment.getApplication()).build());
        ownerId.set(3L);
        try {
            android.os.Looper main = android.os.Looper.getMainLooper();
            org.robolectric.Shadows.shadowOf(main).idle();
            VideoMedia3Converter.cancelActiveTranscode(4L); // someone else's export
            assertTrue(org.robolectric.Shadows.shadowOf(main).isIdle());

            VideoMedia3Converter.cancelActiveTranscode(3L);
            assertFalse(org.robolectric.Shadows.shadowOf(main).isIdle());
            org.robolectric.Shadows.shadowOf(main).idle(); // a transformer never started cancels quietly
        } finally {
            transformer.set(null);
            ownerId.set(-1L);
        }
    }

    @Test
    public void trackTypes_includeAudioOnlyWhenTheSourceHasIt() {
        assertTrue(VideoMedia3Converter.trackTypes(true).contains(androidx.media3.common.C.TRACK_TYPE_AUDIO));
        assertTrue(VideoMedia3Converter.trackTypes(true).contains(androidx.media3.common.C.TRACK_TYPE_VIDEO));
        assertFalse(VideoMedia3Converter.trackTypes(false).contains(androidx.media3.common.C.TRACK_TYPE_AUDIO));
        assertTrue(VideoMedia3Converter.trackTypes(false).contains(androidx.media3.common.C.TRACK_TYPE_VIDEO));
    }

    @Test
    public void ownerIds_ofDifferentJobs_neverMatch() {
        for (int run = 0; run < 50; run++) {
            long clean = VideoMedia3Converter.cleanOwner(run);
            long convert = VideoMedia3Converter.convertOwner(run);
            long share = VideoMedia3Converter.shareOwner(run);
            assertTrue(clean != convert && clean != share && convert != share);
            assertTrue("never the cancel-anything id", clean >= 0 && convert >= 0 && share >= 0);
        }
        assertEquals(VideoMedia3Converter.cleanOwner(3), VideoMedia3Converter.cleanOwner(3));
    }

    @Test
    public void aJobWaitingForAnotherExport_stopsAsSoonAsItIsCancelled() throws Exception {
        java.lang.reflect.Field lockField = VideoMedia3Converter.class.getDeclaredField("TRANSCODE_LOCK");
        lockField.setAccessible(true);
        java.util.concurrent.locks.ReentrantLock lock =
                (java.util.concurrent.locks.ReentrantLock) lockField.get(null);
        java.io.File out = java.io.File.createTempFile("vid_transform_", ".mp4",
                RuntimeEnvironment.getApplication().getCacheDir());
        java.util.concurrent.atomic.AtomicReference<Throwable> thrown =
                new java.util.concurrent.atomic.AtomicReference<>();
        lock.lock(); // another job's export is running
        try {
            Thread waiting = new Thread(() -> {
                try {
                    VideoMedia3Converter.transcodeToPath(RuntimeEnvironment.getApplication(),
                            android.net.Uri.fromFile(out), out.getAbsolutePath(), 0, null, 7L);
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            waiting.start();
            Thread.sleep(300);
            assertTrue("it waits while the other export runs", waiting.isAlive());

            VideoMedia3Converter.cancelActiveTranscode(8L); // someone else's job
            Thread.sleep(400);
            assertTrue(waiting.isAlive());

            VideoMedia3Converter.cancelActiveTranscode(7L);
            waiting.join(3000);
            assertFalse("cancelled without waiting for the other export", waiting.isAlive());
            assertTrue(thrown.get() instanceof InterruptedException);
        } finally {
            lock.unlock();
            out.delete();
        }
    }

    @Test
    public void metadataDroppingMuxer_writesTracksAndSamples_butNoMetadata() throws Exception {
        java.util.List<String> calls = new java.util.ArrayList<>();
        androidx.media3.muxer.Muxer.Factory recording = new androidx.media3.muxer.Muxer.Factory() {
            @Override
            public androidx.media3.muxer.Muxer create(String path) {
                calls.add("create " + path);
                return new androidx.media3.muxer.Muxer() {
                    @Override
                    public int addTrack(androidx.media3.common.Format format) {
                        calls.add("track " + format.sampleMimeType);
                        return 7;
                    }

                    @Override
                    public void writeSampleData(int trackId, java.nio.ByteBuffer data,
                                                androidx.media3.muxer.BufferInfo bufferInfo) {
                        calls.add("sample " + trackId);
                    }

                    @Override
                    public void addMetadataEntry(androidx.media3.common.Metadata.Entry entry) {
                        calls.add("metadata");
                    }

                    @Override
                    public void close() {
                        calls.add("close");
                    }
                };
            }

            @Override
            public com.google.common.collect.ImmutableList<String> getSupportedSampleMimeTypes(int trackType) {
                return com.google.common.collect.ImmutableList.of("video/avc");
            }
        };

        VideoMedia3Converter.MetadataDroppingMuxerFactory factory =
                new VideoMedia3Converter.MetadataDroppingMuxerFactory(recording);
        androidx.media3.muxer.Muxer muxer = factory.create("/out.mp4");
        int track = muxer.addTrack(new androidx.media3.common.Format.Builder()
                .setSampleMimeType("video/avc").build());
        muxer.addMetadataEntry(new androidx.media3.common.Metadata.Entry() { });
        muxer.writeSampleData(track, java.nio.ByteBuffer.allocate(4),
                new androidx.media3.muxer.BufferInfo(0, 4, 0));
        muxer.close();

        assertEquals(java.util.Arrays.asList("create /out.mp4", "track video/avc", "sample 7", "close"), calls);
        assertEquals(com.google.common.collect.ImmutableList.of("video/avc"),
                factory.getSupportedSampleMimeTypes(androidx.media3.common.C.TRACK_TYPE_VIDEO));
    }
}
