package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.provider.MediaStore;

import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultMuxer;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
public class VideoMedia3ConverterTest {

    private Context appContext;

    @Before
    public void setUp() {
        appContext = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        deleteMatching(appContext.getCacheDir());
        deleteMatching(appContext.getExternalCacheDir());
    }

    @Test
    public void formatMappings_coverExtensionsContainersAndVideoMimes() {
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(0));
        assertEquals(".mp4", VideoMedia3Converter.extensionForFormatIndex(1));
        assertEquals(".webm", VideoMedia3Converter.extensionForFormatIndex(2));
        assertEquals(".mkv", VideoMedia3Converter.extensionForFormatIndex(3));
        assertEquals(
                ".mp4",
                VideoMedia3Converter.extensionForFormatIndex(
                        VideoMedia3Converter.FORMAT_STRIP_METADATA));

        assertEquals("video/mp4", VideoMedia3Converter.containerMimeForFormatIndex(0));
        assertEquals("video/mp4", VideoMedia3Converter.containerMimeForFormatIndex(1));
        assertEquals("video/webm", VideoMedia3Converter.containerMimeForFormatIndex(2));
        assertEquals("video/x-matroska", VideoMedia3Converter.containerMimeForFormatIndex(3));

        assertEquals("video/avc", VideoMedia3Converter.videoMimeTypeForFormatIndex(0));
        assertEquals("video/hevc", VideoMedia3Converter.videoMimeTypeForFormatIndex(1));
        assertEquals("video/x-vnd.on2.vp9", VideoMedia3Converter.videoMimeTypeForFormatIndex(2));
        assertEquals("video/av01", VideoMedia3Converter.videoMimeTypeForFormatIndex(3));
        assertEquals("video/avc", VideoMedia3Converter.videoMimeTypeForFormatIndex(99));
    }

    @Test
    public void privateMappings_coverFallbackOrderHdrAudioAndPolling() throws Exception {
        assertArrayEquals(new int[]{0}, invokeFallbackOrder(0));
        assertArrayEquals(new int[]{1, 0}, invokeFallbackOrder(1));
        assertArrayEquals(new int[]{2, 1, 0}, invokeFallbackOrder(2));
        assertArrayEquals(new int[]{3, 1, 0}, invokeFallbackOrder(3));
        assertArrayEquals(new int[]{}, invokeFallbackOrder(-1));
        assertArrayEquals(new int[]{0}, invokeFallbackOrder(99));

        assertEquals("converted", invokeStringHelper("stripExtension", null));
        assertEquals("converted", invokeStringHelper("stripExtension", ""));
        assertEquals("movie", invokeStringHelper("stripExtension", "movie.mp4"));
        assertEquals("movie", invokeStringHelper("stripExtension", "movie"));
        assertEquals("bad_name_", invokeStringHelper("sanitizeFileName", "bad name!"));
        assertEquals("converted", invokeStringHelper("sanitizeFileName", ""));
        assertEquals(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                invokeStringHelper("sanitizeFileName", repeat('a', 81)));

        assertEquals(
                Composition.HDR_MODE_KEEP_HDR,
                invokeIntStringHelper("hdrModeForMp4VideoMime", MimeTypes.VIDEO_H264));
        assertEquals(
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
                invokeIntStringHelper("hdrModeForMp4VideoMime", MimeTypes.VIDEO_VP9));
        assertEquals(
                MimeTypes.AUDIO_AAC,
                invokeStringHelper("audioMimeTypeForVideoMime", MimeTypes.VIDEO_H264));
        assertEquals(
                MimeTypes.AUDIO_OPUS,
                invokeStringHelper("audioMimeTypeForVideoMime", MimeTypes.VIDEO_VP8));

        Handler handler = mock(Handler.class);
        AtomicBoolean polling = new AtomicBoolean(true);
        Runnable runnable = () -> {};
        invokeStopPolling(handler, polling, runnable);
        assertFalse(polling.get());
        verify(handler).removeCallbacks(runnable);

        AtomicBoolean secondPolling = new AtomicBoolean(true);
        invokeStopPolling(handler, secondPolling, null);
        assertFalse(secondPolling.get());
    }

    @Test
    public void transcodeToFile_negativeFormatIndex_throwsDefaultFailure() throws Exception {
        File out = new File(appContext.getCacheDir(), "vid_fail.mp4");
        Uri src = Uri.parse("content://media/video/1");
        try {
            VideoMedia3Converter.transcodeToFile(appContext, src, out, -1);
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Video conversion failed"));
        }
    }

    @Test
    public void transcodeToPath_successfullyWritesOutputAndReportsProgress() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_success.mp4");
        Uri sourceUri = Uri.parse("content://tests/video-source");
        List<Integer> progress = new ArrayList<>();

        withTransformerScenario(
                new TransformerScenario()
                        .withProgress(42)
                        .withStartAction(path -> writeText(new File(path), "converted-h264"))
                        .withAwaitResults(true),
                () -> {
                    VideoMedia3Converter.transcodeToPath(
                            appContext, sourceUri, outputFile.getAbsolutePath(), 0, progress::add);
                    return null;
                });

        assertTrue(outputFile.exists());
        assertTrue(outputFile.length() > 0L);
        assertEquals(List.of(42), progress);
    }

    @Test
    public void transcodeToPath_overloadWithoutListenerAlsoSucceeds() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_success_no_listener.mp4");
        Uri sourceUri = Uri.parse("content://tests/video-source");

        withTransformerScenario(
                new TransformerScenario()
                        .withStartAction(path -> writeText(new File(path), "converted-no-listener"))
                        .withAwaitResults(true),
                () -> {
                    VideoMedia3Converter.transcodeToPath(
                            appContext, sourceUri, outputFile.getAbsolutePath(), 1);
                    return null;
                });

        assertTrue(outputFile.exists());
    }

    @Test
    public void transcodeToPath_invokesOnCompletedListenerAndStopsPolling() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_completed.mp4");
        Uri sourceUri = Uri.parse("content://tests/video-completed");
        AtomicInteger progressCalls = new AtomicInteger();

        withTransformerScenario(
                new TransformerScenario()
                        .withProgress(7)
                        .withStartAction(
                                (path, listener) -> {
                                    writeText(new File(path), "converted-complete");
                                    listener.onCompleted(null, mock(ExportResult.class));
                                })
                        .withAwaitResults(true),
                () -> {
                    VideoMedia3Converter.transcodeToPath(
                            appContext,
                            sourceUri,
                            outputFile.getAbsolutePath(),
                            0,
                            p -> progressCalls.incrementAndGet());
                    return null;
                });

        assertTrue(outputFile.exists());
    }

    @Test
    public void transcodeToPath_retriesFallbackAfterCodecFailure() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_retry.mp4");
        Uri sourceUri = Uri.parse("content://tests/video-retry");

        AtomicInteger startCalls = new AtomicInteger();
        TransformerScenario scenario =
                new TransformerScenario()
                        .withAwaitResults(true)
                        .withStartAction(
                                (path, listener) -> {
                                    int call = startCalls.getAndIncrement();
                                    if (call == 0) {
                                        ExportException exportException = mock(ExportException.class);
                                        when(exportException.getMessage()).thenReturn("AV1 encoder failed");
                                        listener.onError(
                                                null,
                                                mock(ExportResult.class),
                                                exportException);
                                    } else {
                                        writeText(new File(path), "fallback-hevc");
                                    }
                                });

        withTransformerScenario(
                scenario,
                () -> {
                    VideoMedia3Converter.transcodeToPath(
                            appContext, sourceUri, outputFile.getAbsolutePath(), 3);
                    return null;
                });

        assertEquals(2, startCalls.get());
        assertTrue(outputFile.exists());
    }

    @Test
    public void transcodeToPath_throwsLastFailureAfterExhaustingFallbacks() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_fail_all.webm");
        Uri sourceUri = Uri.parse("content://tests/video-fail-all");
        AtomicInteger startCalls = new AtomicInteger();

        try {
            withTransformerScenario(
                    new TransformerScenario()
                            .withAwaitResults(true)
                            .withStartAction(
                                    (path, listener) -> {
                                        startCalls.incrementAndGet();
                                        ExportException exportException = mock(ExportException.class);
                                        when(exportException.getMessage())
                                                .thenReturn("codec failed " + startCalls.get());
                                        listener.onError(null, mock(ExportResult.class), exportException);
                                    }),
                    () -> {
                        VideoMedia3Converter.transcodeToPath(
                                appContext, sourceUri, outputFile.getAbsolutePath(), 2);
                        return null;
                    });
            fail("Expected all fallbacks to fail");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("codec failed 3"));
        }

        assertEquals(3, startCalls.get());
        assertFalse(outputFile.exists());
    }

    @Test
    public void transcodeToPath_wrapsSynchronousTransformerFailures() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_sync_fail.mp4");
        Uri sourceUri = Uri.parse("content://tests/video-sync-fail");

        try {
            withTransformerScenario(
                    new TransformerScenario()
                            .withAwaitResults(true)
                            .withStartThrowable(new RuntimeException("builder boom")),
                    () -> {
                        VideoMedia3Converter.transcodeToPath(
                                appContext, sourceUri, outputFile.getAbsolutePath(), 0);
                        return null;
                    });
            fail("Expected synchronous failure");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Video conversion failed: builder boom"));
        }

        assertFalse(outputFile.exists());
    }

    @Test
    public void transcodeToPath_rejectsMissingOutputFile() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_missing_output.mp4");
        Uri sourceUri = Uri.parse("content://tests/video-missing-output");

        try {
            withTransformerScenario(
                    new TransformerScenario().withAwaitResults(true),
                    () -> {
                        VideoMedia3Converter.transcodeToPath(
                                appContext, sourceUri, outputFile.getAbsolutePath(), 0);
                        return null;
                    });
            fail("Expected missing output failure");
        } catch (IOException e) {
            assertEquals("Video conversion produced no output", e.getMessage());
        }

        assertFalse(outputFile.exists());
    }

    @Test
    public void transcodeToPath_timesOutAndCancelsTransformer() throws Exception {
        File outputFile = new File(appContext.getCacheDir(), "vm3_timeout.mp4");
        Uri sourceUri = Uri.parse("content://tests/video-timeout");
        AtomicReference<Transformer> transformerRef = new AtomicReference<>();

        try {
            withTransformerScenario(
                    new TransformerScenario()
                            .withStartAction(path -> writeText(new File(path), "stuck-video"))
                            .withAwaitResults(false, true)
                            .captureBuiltTransformer(transformerRef),
                    () -> {
                        VideoMedia3Converter.transcodeToPath(
                                appContext, sourceUri, outputFile.getAbsolutePath(), 0);
                        return null;
                    });
            fail("Expected timeout");
        } catch (IOException e) {
            assertEquals("Video conversion timed out", e.getMessage());
        }

        verify(transformerRef.get()).cancel();
        assertFalse(outputFile.exists());
    }

    @Test
    public void transcodeToFile_createsParentDirectoriesAndDelegates() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/video-file");
        File outputFile = new File(appContext.getCacheDir(), "nested/transcodes/movie.mp4");

        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToPath(
                            eq(appContext),
                            eq(sourceUri),
                            eq(outputFile.getAbsolutePath()),
                            eq(1),
                            eq(null)))
                    .thenAnswer(invocation -> null);

            VideoMedia3Converter.transcodeToFile(appContext, sourceUri, outputFile, 1);

            assertTrue(outputFile.getParentFile().exists());
            mockedStatic.verify(() -> VideoMedia3Converter.transcodeToPath(
                    eq(appContext),
                    eq(sourceUri),
                    eq(outputFile.getAbsolutePath()),
                    eq(1),
                    eq(null)));
        }
    }

    @Test
    public void transcodeToFile_throwsWhenParentDirectoryCannotBeCreated() {
        Context context = appContext;
        Uri sourceUri = Uri.parse("content://tests/video-file");
        File outputFile = mock(File.class);
        File parent = mock(File.class);
        when(outputFile.getParentFile()).thenReturn(parent);
        when(parent.exists()).thenReturn(false);
        when(parent.mkdirs()).thenReturn(false);

        try {
            VideoMedia3Converter.transcodeToFile(context, sourceUri, outputFile, 0);
            fail("Expected directory creation failure");
        } catch (IOException e) {
            assertEquals("Failed to create output directory", e.getMessage());
        } catch (InterruptedException e) {
            fail("Did not expect interruption");
        }
    }

    @Test
    public void transcodeToGallery_usesCacheFileAndDeletesItAfterCopy() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/video-gallery");
        Uri expectedUri = Uri.parse("content://tests/gallery-result");
        AtomicReference<File> copiedFile = new AtomicReference<>();

        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToPath(
                            eq(appContext),
                            eq(sourceUri),
                            anyString(),
                            eq(2),
                            eq(null)))
                    .thenAnswer(invocation -> {
                        writeText(new File((String) invocation.getArgument(2)), "gallery-webm");
                        return null;
                    });
            mockedStatic.when(() -> VideoMedia3Converter.copyToMoviesRedact(
                            eq(appContext), any(File.class), eq("share name"), eq(2)))
                    .thenAnswer(invocation -> {
                        File file = invocation.getArgument(1);
                        copiedFile.set(file);
                        assertTrue(file.exists());
                        return expectedUri;
                    });

            Uri result =
                    VideoMedia3Converter.transcodeToGallery(appContext, sourceUri, "share name", 2);

            assertEquals(expectedUri, result);
            assertNotNull(copiedFile.get());
            assertFalse(copiedFile.get().exists());
        }
    }

    @Test
    public void copyToMoviesRedact_sanitizesNameAndCopiesBytes() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Context context = new ResolverContext(appContext, resolver);
        File sourceFile = new File(appContext.getCacheDir(), "vm3_copy_source.webm");
        File destFile = new File(appContext.getCacheDir(), "vm3_copy_dest.webm");
        Uri outUri = Uri.parse("content://tests/movies-copy");
        ArgumentCaptor<ContentValues> valuesCaptor = ArgumentCaptor.forClass(ContentValues.class);
        writeText(sourceFile, "copy-bytes");

        when(resolver.insert(eq(MediaStore.Video.Media.EXTERNAL_CONTENT_URI), valuesCaptor.capture()))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri))
                .thenAnswer(invocation -> new FileOutputStream(destFile));

        Uri result =
                VideoMedia3Converter.copyToMoviesRedact(
                        context, sourceFile, "bad name!!.clip.mp4", 2);

        assertEquals(outUri, result);
        assertEquals(
                "bad_name__.clip.webm",
                valuesCaptor.getValue().getAsString(MediaStore.Video.Media.DISPLAY_NAME));
        assertEquals(
                "video/webm",
                valuesCaptor.getValue().getAsString(MediaStore.Video.Media.MIME_TYPE));
        assertEquals("copy-bytes", readText(destFile));
    }

    @Test
    public void copyToMoviesRedact_throwsWhenInsertFails() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Context context = new ResolverContext(appContext, resolver);
        File sourceFile = new File(appContext.getCacheDir(), "vm3_insert_fail.mp4");
        writeText(sourceFile, "insert-fail");
        when(resolver.insert(eq(MediaStore.Video.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(null);

        try {
            VideoMedia3Converter.copyToMoviesRedact(context, sourceFile, "clip.mp4", 0);
            fail("Expected insert failure");
        } catch (IOException e) {
            assertEquals("MediaStore insert failed", e.getMessage());
        }
    }

    @Test
    public void copyToMoviesRedact_deletesUriWhenOutputStreamMissing() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Context context = new ResolverContext(appContext, resolver);
        File sourceFile = new File(appContext.getCacheDir(), "vm3_output_missing.mp4");
        Uri outUri = Uri.parse("content://tests/output-missing");
        writeText(sourceFile, "output-missing");
        when(resolver.insert(eq(MediaStore.Video.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri)).thenReturn(null);

        try {
            VideoMedia3Converter.copyToMoviesRedact(context, sourceFile, "clip.mp4", 0);
            fail("Expected output stream failure");
        } catch (IOException e) {
            assertEquals("Cannot open output stream", e.getMessage());
        }

        verify(resolver, org.mockito.Mockito.atLeastOnce()).delete(outUri, null, null);
    }

    @Test
    public void copyToMoviesRedact_deletesUriWhenCopyingFails() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Context context = new ResolverContext(appContext, resolver);
        File sourceFile = new File(appContext.getCacheDir(), "vm3_copy_fail.mp4");
        Uri outUri = Uri.parse("content://tests/copy-fail");
        writeText(sourceFile, "copy-fail");
        when(resolver.insert(eq(MediaStore.Video.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri))
                .thenReturn(
                        new OutputStream() {
                            @Override
                            public void write(int b) throws IOException {
                                throw new IOException("write boom");
                            }
                        });

        try {
            VideoMedia3Converter.copyToMoviesRedact(context, sourceFile, "clip.mp4", 0);
            fail("Expected copy failure");
        } catch (IOException e) {
            assertEquals("write boom", e.getMessage());
        }

        verify(resolver).delete(outUri, null, null);
    }

    @Test
    public void copyToMoviesRedact_preservesOriginalFailureWhenCleanupDeleteAlsoFails() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Context context = new ResolverContext(appContext, resolver);
        File sourceFile = new File(appContext.getCacheDir(), "vm3_copy_fail_cleanup.mp4");
        Uri outUri = Uri.parse("content://tests/copy-fail-cleanup");
        writeText(sourceFile, "copy-fail");
        when(resolver.insert(eq(MediaStore.Video.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri))
                .thenReturn(
                        new OutputStream() {
                            @Override
                            public void write(int b) throws IOException {
                                throw new IOException("write boom");
                            }
                        });
        when(resolver.delete(outUri, null, null)).thenThrow(new RuntimeException("cleanup boom"));

        try {
            VideoMedia3Converter.copyToMoviesRedact(context, sourceFile, "clip.mp4", 0);
            fail("Expected copy failure");
        } catch (IOException e) {
            assertEquals("write boom", e.getMessage());
        }
    }

    private static int[] invokeFallbackOrder(int requestedFormatIndex) throws Exception {
        Method method =
                VideoMedia3Converter.class.getDeclaredMethod(
                        "encoderFallbackOrder", int.class);
        method.setAccessible(true);
        return (int[]) method.invoke(null, requestedFormatIndex);
    }

    private static int invokeIntStringHelper(String methodName, String value) throws Exception {
        Method method = VideoMedia3Converter.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, value);
    }

    private static String invokeStringHelper(String methodName, String value) throws Exception {
        Method method = VideoMedia3Converter.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static void invokeStopPolling(
            Handler handler, AtomicBoolean pollingActive, Runnable runnable)
            throws Exception {
        Method method =
                VideoMedia3Converter.class.getDeclaredMethod(
                        "stopPolling", Handler.class, AtomicBoolean.class, Runnable.class);
        method.setAccessible(true);
        method.invoke(null, handler, pollingActive, runnable);
    }

    private <T> T withTransformerScenario(TransformerScenario scenario, ThrowingSupplier<T> action)
            throws Exception {
        AtomicReference<Transformer.Listener> listenerRef = new AtomicReference<>();
        AtomicInteger latchIndex = new AtomicInteger();

        try (MockedConstruction<DefaultMuxer.Factory> ignoredMuxerFactory =
                        mockConstruction(DefaultMuxer.Factory.class);
             MockedConstruction<Handler> ignoredHandlers =
                        mockConstruction(
                                Handler.class,
                                (handler, context) -> {
                                    doAnswer(invocation -> {
                                        Runnable runnable = invocation.getArgument(0);
                                        runnable.run();
                                        return true;
                                    }).when(handler).post(any(Runnable.class));
                                    when(handler.postDelayed(any(Runnable.class), anyLong())).thenReturn(true);
                                });
             MockedConstruction<CountDownLatch> ignoredLatches =
                        mockConstruction(
                                CountDownLatch.class,
                                (latch, context) -> when(latch.await(anyLong(), any()))
                                        .thenReturn(scenario.awaitResults.get(
                                                Math.min(
                                                        latchIndex.getAndIncrement(),
                                                        scenario.awaitResults.size() - 1))));
             MockedConstruction<Transformer.Builder> ignoredBuilders =
                        mockConstruction(
                                Transformer.Builder.class,
                                (builder, context) -> {
                                    Transformer transformer = mock(Transformer.class);
                                    if (scenario.transformerCapture != null) {
                                        scenario.transformerCapture.set(transformer);
                                    }

                                    when(builder.setMuxerFactory(any())).thenReturn(builder);
                                    when(builder.setVideoMimeType(anyString())).thenReturn(builder);
                                    when(builder.setAudioMimeType(anyString())).thenReturn(builder);
                                    when(builder.addListener(any())).thenAnswer(invocation -> {
                                        listenerRef.set(invocation.getArgument(0));
                                        return builder;
                                    });
                                    when(builder.build()).thenReturn(transformer);
                                    when(transformer.getProgress(any())).thenAnswer(invocation -> {
                                        ProgressHolder holder = invocation.getArgument(0);
                                        holder.progress = scenario.progressPercent;
                                        return scenario.progressPercent >= 0
                                                ? Transformer.PROGRESS_STATE_AVAILABLE
                                                : Transformer.PROGRESS_STATE_UNAVAILABLE;
                                    });

                                    if (scenario.startThrowable != null) {
                                        doThrow(scenario.startThrowable)
                                                .when(transformer)
                                                .start(any(Composition.class), anyString());
                                    } else {
                                        doAnswer(invocation -> {
                                            String path = invocation.getArgument(1);
                                            if (scenario.startActionWithListener != null) {
                                                scenario.startActionWithListener.run(
                                                        path, listenerRef.get());
                                            } else if (scenario.startAction != null) {
                                                scenario.startAction.run(path);
                                            }
                                            return null;
                                        }).when(transformer).start(any(Composition.class), anyString());
                                    }
                                })) {
            return action.get();
        }
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            assertTrue(parent.mkdirs());
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String readText(File file) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private void deleteMatching(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("vm3_") || name.startsWith("vid_transform_") || file.isDirectory()) {
                deleteRecursively(file);
            }
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    private static final class ResolverContext extends ContextWrapper {
        private final ContentResolver resolver;

        ResolverContext(Context base, ContentResolver resolver) {
            super(base);
            this.resolver = resolver;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ContentResolver getContentResolver() {
            return resolver;
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private interface StartAction {
        void run(String outputPath) throws Exception;
    }

    private interface StartActionWithListener {
        void run(String outputPath, Transformer.Listener listener) throws Exception;
    }

    private static final class TransformerScenario {
        private final List<Boolean> awaitResults = new ArrayList<>(List.of(true));
        private int progressPercent = -1;
        private StartAction startAction;
        private StartActionWithListener startActionWithListener;
        private Throwable startThrowable;
        private AtomicReference<Transformer> transformerCapture;

        TransformerScenario withProgress(int percent) {
            this.progressPercent = percent;
            return this;
        }

        TransformerScenario withStartAction(StartAction action) {
            this.startAction = action;
            return this;
        }

        TransformerScenario withStartAction(StartActionWithListener action) {
            this.startActionWithListener = action;
            return this;
        }

        TransformerScenario withStartThrowable(Throwable throwable) {
            this.startThrowable = throwable;
            return this;
        }

        TransformerScenario withAwaitResults(boolean... results) {
            this.awaitResults.clear();
            for (boolean result : results) {
                this.awaitResults.add(result);
            }
            return this;
        }

        TransformerScenario captureBuiltTransformer(AtomicReference<Transformer> transformerRef) {
            this.transformerCapture = transformerRef;
            return this;
        }
    }
}
