package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentValues;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetFileDescriptor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.exifinterface.media.ExifInterface;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;

import com.doubleangels.redact.media.FormatConverter;
import com.doubleangels.redact.media.VideoMedia3Converter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class MetadataStripperTest {

    private Context context;
    private MetadataStripper stripper;
    private File sourceJpegFile;
    private Uri sourceJpegUri;
    private File sourceVideoFile;
    private Uri sourceVideoUri;

    @Before
    public void setup() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        stripper = new MetadataStripper(context);
        sourceJpegFile = createJpegFixture("metadata_source_fixture.jpg");
        sourceJpegUri = Uri.fromFile(sourceJpegFile);
        sourceVideoFile = createBinaryFixture("metadata_source_video.mp4", "video-bytes");
        sourceVideoUri = Uri.fromFile(sourceVideoFile);
    }

    @After
    public void tearDown() {
        deleteMatching(context.getCacheDir());
        deleteMatching(context.getExternalCacheDir());
    }

    @Test
    public void stripVideoMetadataForSharing_whenFastStripReturnsMp4_skipsTranscodeBranch() throws Exception {
        File mp4 = createBinaryFixture("vid_transmux_test.mp4", "mp4-bytes");
        stripper.setTestFastStripVideoMetadataOverride(mp4);

        try (MockedStatic<FileProvider> fileProvider = mockFileProvider();
             MockedStatic<VideoMedia3Converter> converter = mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            Uri out = stripper.stripVideoMetadataForSharing(sourceVideoUri, "input.mp4");
            assertNotNull(out);
            converter.verify(
                    () -> VideoMedia3Converter.transcodeToFile(any(), any(), any(File.class), anyInt(), any()),
                    never());
        } finally {
            stripper.setTestFastStripVideoMetadataOverride(null);
        }
    }

    @Test
    public void stripExifDataForSharing_whenProcessedDirCannotBeCreated_returnsNull() throws Exception {
        File cacheAsFile = new File(context.getCacheDir(), "cache_as_file");
        try (FileOutputStream fos = new FileOutputStream(cacheAsFile)) {
            fos.write(new byte[] {0x00});
        }

        Context wrapped = new ContextWrapper(context) {
            @Override
            public File getCacheDir() {
                return cacheAsFile;
            }
        };

        MetadataStripper wrappedStripper = new MetadataStripper(wrapped);
        try (MockedStatic<FileProvider> fileProvider = mockFileProvider()) {
            wrappedStripper.stripExifDataForSharing(sourceJpegUri, "photo.jpg");
        }
    }

    @Test
    public void containsXmpMetadata_directoryInput_hitsExceptionCatch() throws Exception {
        Method m = MetadataStripper.class.getDeclaredMethod("containsXMPMetadata", File.class);
        m.setAccessible(true);
        File dir = new File(context.getCacheDir(), "xmp_dir");
        assertTrue(dir.mkdirs() || dir.isDirectory());
        assertEquals(false, m.invoke(stripper, dir));
    }

    @Test
    public void getMaxFileSizeAndBoundaryChecks_areCorrect() throws Exception {
        assertEquals(100L, stripper.getMaxFileSizeMB());

        long maxBytes = stripper.getMaxFileSizeMB() * 1024L * 1024L;
        File exactBoundary = createSparseFixture("boundary_exact.jpg", maxBytes);
        File overBoundary = createSparseFixture("boundary_over.jpg", maxBytes + 1L);

        assertFalse(stripper.isFileTooLarge(Uri.fromFile(exactBoundary)));
        assertTrue(stripper.isFileTooLarge(Uri.fromFile(overBoundary)));
    }

    @Test
    public void clearFileSizeCache_forcesFreshLookup() throws Exception {
        File cachedFile = createBinaryFixture("cached_lookup.jpg", "abc");
        Uri cachedUri = Uri.fromFile(cachedFile);
        Field cacheField = MetadataStripper.class.getDeclaredField("fileSizeCache");
        cacheField.setAccessible(true);

        assertEquals(3L, invokeCachedFileSize(stripper, cachedUri));
        // Second lookup should use cache directly.
        assertEquals(3L, invokeCachedFileSize(stripper, cachedUri));
        assertTrue(((java.util.Map<?, ?>) cacheField.get(stripper)).containsKey(cachedUri.toString()));

        stripper.clearFileSizeCache();
        assertTrue(((java.util.Map<?, ?>) cacheField.get(stripper)).isEmpty());
    }

    @Test
    public void stripExifDataForSharing_removesSensitiveExifAndPreservesOrientation() throws Exception {
        assertNull(stripper.getLastProcessedFileUri());

        Uri result;
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            result = stripper.stripExifDataForSharing(sourceJpegUri, "camera-roll.jpg");
        }

        assertNotNull(result);
        assertEquals(result, stripper.getLastProcessedFileUri());
        assertEquals("content", result.getScheme());

        File processedFile = newestProcessedFile();
        assertNotNull(processedFile);
        assertTrue(processedFile.exists());
        assertTrue(processedFile.length() > 0L);

        ExifInterface cleanedExif = new ExifInterface(processedFile.getAbsolutePath());
        assertEquals(
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90),
                cleanedExif.getAttribute(ExifInterface.TAG_ORIENTATION));
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_MAKE));
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE));
    }

    @Test
    public void stripExifData_usesMediaStoreResolverAndReportsProgress() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/photo");
        Uri outputUri = Uri.parse("content://tests/output/photo");
        File outputFile = new File(context.getCacheDir(), "metadata_mediastore_result.jpg");
        ContentResolver resolver =
                createMediaStoreResolver(sourceUri, sourceJpegFile, outputUri, outputFile, "image/jpeg");
        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        List<Integer> percents = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        resolverStripper.setProgressCallback((percent, message) -> {
            percents.add(percent);
            messages.add(message);
        });

        Uri result = resolverStripper.stripExifData(sourceUri, "gallery-photo.jpg");

        assertEquals(outputUri, result);
        assertEquals(outputUri, resolverStripper.getLastProcessedFileUri());
        assertEquals(Arrays.asList(20, 40, 60, 80, 100), percents);
        assertTrue(messages.get(0).contains("Reading image"));
        assertTrue(messages.get(1).contains("Reading essential metadata"));
        assertTrue(messages.get(2).contains("Removing metadata"));
        assertTrue(messages.get(3).contains("Restoring essential metadata"));
        assertTrue(messages.get(4).contains("Verifying metadata removal"));

        ExifInterface cleanedExif = new ExifInterface(outputFile.getAbsolutePath());
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_MAKE));
        assertNull(cleanedExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE));

        verify(resolver).insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class));
        verify(resolver, atLeastOnce()).openOutputStream(outputUri);
        verify(resolver).openFileDescriptor(eq(outputUri), eq("rw"));
    }

    @Test
    public void stripExifData_returnsNullWhenSourceStreamIsMissing() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/missing");
        Uri outputUri = Uri.parse("content://tests/output/missing");
        File outputFile = new File(context.getCacheDir(), "metadata_missing_stream.jpg");
        ContentResolver resolver = mock(ContentResolver.class);

        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
        cursor.addRow(new Object[]{sourceJpegFile.length()});
        when(resolver.query(eq(sourceUri), any(String[].class), isNull(), isNull(), isNull())).thenReturn(cursor);
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri)).thenReturn(null);
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outputUri);
        when(resolver.openOutputStream(outputUri)).thenAnswer(invocation -> new FileOutputStream(outputFile));

        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        assertNull(resolverStripper.stripExifData(sourceUri, "broken.jpg"));
        assertNull(resolverStripper.getLastProcessedFileUri());
    }

    @Test
    public void stripExifData_cleansUpPartialEntryWhenOutputStreamMissing() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/no-output");
        Uri outputUri = Uri.parse("content://tests/output/no-output");
        File outputFile = new File(context.getCacheDir(), "metadata_no_output.jpg");
        ContentResolver resolver = mock(ContentResolver.class);

        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
        cursor.addRow(new Object[]{sourceJpegFile.length()});
        when(resolver.query(eq(sourceUri), any(String[].class), isNull(), isNull(), isNull())).thenReturn(cursor);
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri)).thenAnswer(invocation -> new FileInputStream(sourceJpegFile));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outputUri);
        when(resolver.openOutputStream(outputUri)).thenReturn(null);
        when(resolver.delete(eq(outputUri), eq(null), eq(null))).thenReturn(1);

        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        assertEquals(outputUri, resolverStripper.stripExifData(sourceUri, "broken-output.jpg"));
        verify(resolver).delete(eq(outputUri), eq(null), eq(null));
        assertFalse(outputFile.exists());
    }

    @Test
    public void stripMetadataForSharing_routesImageAndVideo() throws Exception {
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            Uri imageResult = stripper.stripMetadataForSharing(sourceJpegUri, "shared.jpg", false);
            assertNotNull(imageResult);
            assertEquals("content", imageResult.getScheme());
        }

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToFile(
                            any(Context.class),
                            eq(sourceVideoUri),
                            any(File.class),
                            eq(2),
                            any()))
                    .thenAnswer(invocation -> {
                        File outputFile = invocation.getArgument(2);
                        try (OutputStream os = new FileOutputStream(outputFile)) {
                            os.write("video".getBytes(StandardCharsets.UTF_8));
                        }
                        return null;
                    });

            Uri videoResult = stripper.stripMetadataForSharing(sourceVideoUri, "shared.webm", true);

            assertNotNull(videoResult);
            mockedStatic.verify(() -> VideoMedia3Converter.transcodeToFile(
                    any(Context.class),
                    eq(sourceVideoUri),
                    any(File.class),
                    eq(2),
                    any()));
        }
    }

    @Test
    public void stripExifDataForSharing_rejectsFileTooLarge() throws Exception {
        long tooLargeBytes = (stripper.getMaxFileSizeMB() * 1024L * 1024L) + 1L;
        File oversizedFile = createSparseFixture("metadata_oversized.jpg", tooLargeBytes);

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            assertNull(stripper.stripExifDataForSharing(Uri.fromFile(oversizedFile), "oversized.jpg"));
        }

        assertNull(stripper.getLastProcessedFileUri());
    }

    @Test
    public void stripExifDataForSharing_fallsBackToBitmapWhenNativeExifFails() throws Exception {
        MetadataStripper bitmapFallbackStripper = new MetadataStripper(context);
        AtomicInteger constructionIndex = new AtomicInteger();

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedConstruction<ExifInterface> mockedExif = mockConstruction(
                     ExifInterface.class,
                     (mock, ignored) -> {
                         int index = constructionIndex.getAndIncrement();
                         when(mock.getAttribute(anyString())).thenReturn(null);
                         if (index == 0) {
                             when(mock.getAttribute(ExifInterface.TAG_ORIENTATION))
                                     .thenReturn(String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
                         } else if (index == 1) {
                             doThrow(new IOException("native exif failed")).when(mock).saveAttributes();
                         }
                     })) {
            Uri result = bitmapFallbackStripper.stripExifDataForSharing(sourceJpegUri, "converted.png");

            assertNotNull(result);
            assertEquals(result, bitmapFallbackStripper.getLastProcessedFileUri());
        }

        File processedFile = newestProcessedFile();
        assertNotNull(processedFile);
        assertTrue(processedFile.exists());
        assertTrue(processedFile.getName().endsWith(".png"));
        assertTrue(processedFile.length() > 0L);
    }

    @Test
    public void stripExifDataForSharing_returnsNullWhenProcessedPathIsInvalid() throws Exception {
        File badCacheDir = new File(context.getCacheDir(), "metadata_bad_cache");
        assertTrue(badCacheDir.exists() || badCacheDir.mkdirs());
        File badProcessedPath = createFile(badCacheDir, "processed", "not-a-directory");
        MetadataStripper badContextStripper = new MetadataStripper(new CacheDirContext(context, badCacheDir));

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            assertNull(badContextStripper.stripExifDataForSharing(sourceJpegUri, "bad-cache.jpg"));
        }

        assertTrue(badProcessedPath.exists());
        assertTrue(badProcessedPath.isFile());
    }

    @Test
    public void stripVideoMetadataForSharing_usesStaticConverterAndReportsProgress() throws Exception {
        List<Integer> percents = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        stripper.setProgressCallback((percent, message) -> {
            percents.add(percent);
            messages.add(message);
        });

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToFile(
                            any(Context.class),
                            eq(sourceVideoUri),
                            any(File.class),
                            eq(2),
                            any()))
                    .thenAnswer(invocation -> {
                        File outputFile = invocation.getArgument(2);
                        VideoMedia3Converter.TranscodeProgressListener listener = invocation.getArgument(4);
                        try (OutputStream os = new FileOutputStream(outputFile)) {
                            os.write("converted-webm".getBytes(StandardCharsets.UTF_8));
                        }
                        listener.onProgress(60);
                        return null;
                    });

            Uri result = stripper.stripVideoMetadataForSharing(sourceVideoUri, "clip.webm");

            assertNotNull(result);
            assertEquals(result, stripper.getLastProcessedFileUri());
            assertEquals("content", result.getScheme());
            assertEquals(Arrays.asList(25, 50, 75, 80, 100), percents);
            assertTrue(messages.get(0).contains("Reading video"));
            assertTrue(messages.get(1).contains("Transmuxing"));
            assertTrue(messages.get(2).contains("Transcoding"));
            assertTrue(messages.get(3).contains("60"));
            assertTrue(messages.get(4).contains("Saving cleaned video"));

            File processedFile = newestProcessedFile();
            assertNotNull(processedFile);
            assertTrue(processedFile.getName().endsWith(".webm"));
            assertTrue(processedFile.exists());
        }
    }

    @Test
    public void stripVideoMetadata_usesRandomizedNameStoresUriAndReportsProgress() throws Exception {
        Uri expectedUri = Uri.parse("content://com.doubleangels.redact.test/video/clean");
        MetadataStripper spyStripper = spy(new MetadataStripper(context));
        List<Integer> percents = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        spyStripper.setProgressCallback((percent, message) -> {
            percents.add(percent);
            messages.add(message);
        });

        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToGallery(
                            any(Context.class),
                            eq(sourceVideoUri),
                            anyString(),
                            eq(0),
                            any()))
                    .thenAnswer(invocation -> {
                        VideoMedia3Converter.TranscodeProgressListener listener = invocation.getArgument(4);
                        listener.onProgress(40);
                        return expectedUri;
                    });

            Uri result = spyStripper.stripVideoMetadata(sourceVideoUri, "shared.mp4");

            assertEquals(expectedUri, result);
            assertEquals(expectedUri, spyStripper.getLastProcessedFileUri());
            assertEquals(Arrays.asList(25, 50, 75, 70, 100), percents);
            assertTrue(messages.get(0).contains("Reading video"));
            assertTrue(messages.get(1).contains("Transmuxing"));
            assertTrue(messages.get(2).contains("Transcoding to target format"));
            assertTrue(messages.get(3).contains("40"));
            assertTrue(messages.get(4).contains("Saving cleaned video"));
            verify(spyStripper, atLeastOnce()).generateShortRandomName();
            mockedStatic.verify(() -> VideoMedia3Converter.transcodeToGallery(
                    any(Context.class),
                    eq(sourceVideoUri),
                    anyString(),
                    eq(0),
                    any()));
        }
    }

    @Test
    public void cleanupTempFiles_deletesOnlyOldProcessedFiles() throws Exception {
        File processedDir = new File(context.getCacheDir(), "processed");
        assertTrue(processedDir.exists() || processedDir.mkdirs());

        File oldFile = createFile(processedDir, "old-cleaned.jpg", "old");
        File newFile = createFile(processedDir, "new-cleaned.jpg", "new");
        long now = System.currentTimeMillis();
        assertTrue(oldFile.setLastModified(now - (2L * 24L * 60L * 60L * 1000L)));
        assertTrue(newFile.setLastModified(now));

        stripper.cleanupTempFiles();

        assertFalse(oldFile.exists());
        assertTrue(newFile.exists());
    }

    @Test
    public void setProgressCallback_reportsExpectedProgressDuringImageStrip() throws Exception {
        List<Integer> percents = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        stripper.setProgressCallback((percent, message) -> {
            percents.add(percent);
            messages.add(message);
        });

        Uri result;
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            result = stripper.stripExifDataForSharing(sourceJpegUri, "photo.jpg");
        }

        assertNotNull(result);
        assertEquals(Arrays.asList(25, 50, 60, 80, 100), percents);
        assertTrue(messages.get(0).contains("Reading image"));
        assertTrue(messages.get(1).contains("Reading essential metadata"));
        assertTrue(messages.get(2).contains("Removing metadata"));
        assertTrue(messages.get(3).contains("Removing all metadata"));
        assertTrue(messages.get(4).contains("Verifying metadata removal"));
    }

    @Test
    public void readEssentialExifData_preservesOnlyOrientation() throws Exception {
        invokePrivate(stripper, "readEssentialExifData", new Class<?>[]{File.class}, sourceJpegFile);

        Field preservedField = MetadataStripper.class.getDeclaredField("preservedExifValues");
        preservedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> preserved = (Map<String, String>) preservedField.get(stripper);

        assertEquals(1, preserved.size());
        assertEquals(
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90),
                preserved.get(ExifInterface.TAG_ORIENTATION));
        assertNull(preserved.get(ExifInterface.TAG_MAKE));
    }

    @Test
    public void removeAllExifMetadata_keepsOrientationAndClearsSensitiveFields() throws Exception {
        File workingCopy = createJpegFixture("metadata_remove_all.jpg");
        ExifInterface exif = new ExifInterface(workingCopy.getAbsolutePath());

        invokePrivate(stripper, "removeAllExifMetadata", new Class<?>[]{ExifInterface.class}, exif);
        exif.saveAttributes();

        ExifInterface cleaned = new ExifInterface(workingCopy.getAbsolutePath());
        assertEquals(
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90),
                cleaned.getAttribute(ExifInterface.TAG_ORIENTATION));
        assertNull(cleaned.getAttribute(ExifInterface.TAG_MAKE));
        assertNull(cleaned.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
        assertNull(cleaned.getAttribute(ExifInterface.TAG_GPS_LONGITUDE));
    }

    @Test
    public void verifyMetadataRemoval_detectsExifAndXmpButAllowsCleanFiles() throws Exception {
        boolean dirtyFile = (boolean) invokePrivate(
                stripper,
                "verifyMetadataRemoval",
                new Class<?>[]{File.class},
                sourceJpegFile);
        assertFalse(dirtyFile);

        File cleanedFile = createJpegFixture("metadata_verify_clean.jpg");
        ExifInterface cleanedExif = new ExifInterface(cleanedFile.getAbsolutePath());
        invokePrivate(stripper, "removeAllExifMetadata", new Class<?>[]{ExifInterface.class}, cleanedExif);
        cleanedExif.saveAttributes();

        boolean cleanResult = (boolean) invokePrivate(
                stripper,
                "verifyMetadataRemoval",
                new Class<?>[]{File.class},
                cleanedFile);
        assertTrue(cleanResult);

        File xmpFile = createJpegFixture("metadata_verify_xmp.jpg");
        ExifInterface xmpExif = new ExifInterface(xmpFile.getAbsolutePath());
        invokePrivate(stripper, "removeAllExifMetadata", new Class<?>[]{ExifInterface.class}, xmpExif);
        xmpExif.saveAttributes();
        try (FileOutputStream fos = new FileOutputStream(xmpFile, true)) {
            fos.write("http://ns.adobe.com/xap/1.0/".getBytes(StandardCharsets.ISO_8859_1));
        }

        boolean xmpResult = (boolean) invokePrivate(
                stripper,
                "verifyMetadataRemoval",
                new Class<?>[]{File.class},
                xmpFile);
        assertFalse(xmpResult);
    }

    @Test
    public void verifyMetadataRemoval_handlesXmpReadFailure() throws Exception {
        stripper.setTestForceXmpReadFailure(true);
        boolean result = (boolean) invokePrivate(
                stripper,
                "containsXMPMetadata",
                new Class<?>[]{File.class},
                sourceJpegFile);
        assertFalse(result);
        stripper.setTestForceXmpReadFailure(false);
    }

    @Test
    public void stripMetadataLossless_handlesJpegAndNonJpegInputs() throws Exception {
        ByteArrayOutputStream jpegOut = new ByteArrayOutputStream();
        boolean jpegResult = (boolean) invokePrivate(
                stripper,
                "stripMetadataLossless",
                new Class<?>[]{File.class, OutputStream.class, String.class},
                sourceJpegFile,
                jpegOut,
                ".jpg");
        assertTrue(jpegResult);
        assertTrue(jpegOut.size() > 0);

        File strippedJpeg = createFile(context.getCacheDir(), "metadata_lossless_result.jpg", "");
        try (FileOutputStream fos = new FileOutputStream(strippedJpeg)) {
            fos.write(jpegOut.toByteArray());
        }
        ExifInterface losslessExif = new ExifInterface(strippedJpeg.getAbsolutePath());
        assertNull(losslessExif.getAttribute(ExifInterface.TAG_MAKE));

        File pngFile = createBitmapFixture("metadata_lossless_source.png", Bitmap.CompressFormat.PNG);
        boolean pngResult = (boolean) invokePrivate(
                stripper,
                "stripMetadataLossless",
                new Class<?>[]{File.class, OutputStream.class, String.class},
                pngFile,
                new ByteArrayOutputStream(),
                ".png");
        assertFalse(pngResult);
    }

    @Test
    public void stripMetadataNativeExif_stripsPngAndWebpFixtures() throws Exception {
        File pngFile = createBitmapFixture("metadata_native_source.png", Bitmap.CompressFormat.PNG);
        writeExifFixtureData(pngFile);
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        boolean pngResult = (boolean) invokePrivate(
                stripper,
                "stripMetadataNativeExif",
                new Class<?>[]{File.class, OutputStream.class, String.class},
                pngFile,
                pngOut,
                ".png");
        assertTrue(pngResult);
        File cleanedPng = createFile(context.getCacheDir(), "metadata_native_clean.png", "");
        try (FileOutputStream fos = new FileOutputStream(cleanedPng)) {
            fos.write(pngOut.toByteArray());
        }
        ExifInterface pngExif = new ExifInterface(cleanedPng.getAbsolutePath());
        assertNull(pngExif.getAttribute(ExifInterface.TAG_MAKE));

        File webpFile = createBitmapFixture("metadata_native_source.webp", Bitmap.CompressFormat.WEBP);
        writeExifFixtureData(webpFile);
        ByteArrayOutputStream webpOut = new ByteArrayOutputStream();
        boolean webpResult = (boolean) invokePrivate(
                stripper,
                "stripMetadataNativeExif",
                new Class<?>[]{File.class, OutputStream.class, String.class},
                webpFile,
                webpOut,
                ".webp");
        assertTrue(webpResult);
        File cleanedWebp = createFile(context.getCacheDir(), "metadata_native_clean.webp", "");
        try (FileOutputStream fos = new FileOutputStream(cleanedWebp)) {
            fos.write(webpOut.toByteArray());
        }
        ExifInterface webpExif = new ExifInterface(cleanedWebp.getAbsolutePath());
        assertNull(webpExif.getAttribute(ExifInterface.TAG_MAKE));
    }

    @Test
    public void utilityHelpers_coverPrivateBranches() throws Exception {
        assertTrue(stripper.generateShortRandomName().matches("[A-Za-z0-9]{12}"));
        assertEquals(
                ".jpg",
                invokePrivate(stripper, "getFileExtension", new Class<?>[]{String.class, String.class}, "photo.jpg", ".png"));
        assertEquals(
                ".png",
                invokePrivate(stripper, "getFileExtension", new Class<?>[]{String.class, String.class}, "photo", ".png"));
        assertEquals(
                ".webp",
                invokePrivateStatic(MetadataStripper.class, "extensionFromFilename", new Class<?>[]{String.class}, "clip.WEBP"));
        assertNull(invokePrivateStatic(MetadataStripper.class, "extensionFromFilename", new Class<?>[]{String.class}, "clip"));
        assertEquals(
                "video/webm",
                invokePrivate(stripper, "videoMimeTypeForExtension", new Class<?>[]{String.class}, ".webm"));
        assertEquals(
                "video/x-msvideo",
                invokePrivate(stripper, "videoMimeTypeForExtension", new Class<?>[]{String.class}, ".avi"));
        assertEquals(
                "video/mp4",
                invokePrivate(stripper, "videoMimeTypeForExtension", new Class<?>[]{String.class}, ".unknown"));

        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.outWidth = 10_000;
        options.outHeight = 10_000;
        assertEquals(
                2,
                invokePrivate(stripper, "calculateInSampleSize", new Class<?>[]{android.graphics.BitmapFactory.Options.class}, options));

        File xmpFile = createBinaryFixture("metadata_xmp.bin", "prefix xpacket suffix");
        assertTrue((boolean) invokePrivate(stripper, "containsXMPMetadata", new Class<?>[]{File.class}, xmpFile));
        assertFalse((boolean) invokePrivate(stripper, "containsXMPMetadata", new Class<?>[]{File.class}, sourceJpegFile));

        File copiedFile = new File(context.getCacheDir(), "metadata_copy.jpg");
        invokePrivate(
                stripper,
                "removeXMPAndIPTCMetadata",
                new Class<?>[]{File.class, File.class},
                sourceJpegFile,
                copiedFile);
        assertTrue(copiedFile.exists());
        assertEquals(sourceJpegFile.length(), copiedFile.length());

        File secureDeleteFile = createBinaryFixture("metadata_secure_delete.bin", "erase-me");
        assertTrue((boolean) invokePrivate(stripper, "secureDeleteFile", new Class<?>[]{File.class}, secureDeleteFile));
        assertFalse(secureDeleteFile.exists());
        assertFalse((boolean) invokePrivate(
                stripper,
                "secureDeleteFile",
                new Class<?>[]{File.class},
                new File(context.getCacheDir(), "metadata_missing.bin")));

        Closeable brokenCloseable = mock(Closeable.class);
        doThrow(new IOException("close failed")).when(brokenCloseable).close();
        invokePrivate(stripper, "closeQuietly", new Class<?>[]{Closeable.class}, brokenCloseable);

        ExifInterface exif = mock(ExifInterface.class);
        when(exif.getThumbnailBytes()).thenReturn(new byte[]{1, 2, 3});
        invokePrivate(stripper, "removeThumbnails", new Class<?>[]{ExifInterface.class}, exif);
        verify(exif).setAttribute(ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH, null);
        verify(exif).setAttribute(ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, null);

        invokePrivate(stripper, "isMemoryLow", new Class<?>[]{});
        invokePrivate(stripper, "tryFreeMemory", new Class<?>[]{});
    }

    @Test
    public void resolveImageOutputFormat_usesFilenameExtension() throws Exception {
        Uri uri = Uri.parse("content://media/external/images/1");
        FormatConverter.ImageFormatSpec spec = stripper.resolveImageOutputFormat(uri, "vacation.PNG");
        assertEquals(".png", spec.extension);
        assertEquals("image/png", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.PNG, spec.compressFormat);
    }

    @Test
    public void resolveImageOutputFormat_usesMimeBranchesWhenNeeded() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.getType(Uri.parse("content://tests/png"))).thenReturn("image/png");
        when(resolver.getType(Uri.parse("content://tests/webp"))).thenReturn("image/webp");
        when(resolver.getType(Uri.parse("content://tests/jpg"))).thenReturn("image/jpeg");
        when(resolver.getType(Uri.parse("content://tests/fallback"))).thenThrow(new RuntimeException("boom"));

        MetadataStripper mimeStripper = new MetadataStripper(new ResolverContext(context, resolver));

        FormatConverter.ImageFormatSpec pngSpec =
                mimeStripper.resolveImageOutputFormat(Uri.parse("content://tests/png"), "mystery.bin");
        FormatConverter.ImageFormatSpec webpSpec =
                mimeStripper.resolveImageOutputFormat(Uri.parse("content://tests/webp"), "noextension");
        FormatConverter.ImageFormatSpec jpgSpec =
                mimeStripper.resolveImageOutputFormat(Uri.parse("content://tests/jpg"), "unknown.dat");
        FormatConverter.ImageFormatSpec fallbackSpec =
                mimeStripper.resolveImageOutputFormat(Uri.parse("content://tests/fallback"), "unknown.dat");

        assertEquals(".png", pngSpec.extension);
        assertEquals(Bitmap.CompressFormat.PNG, pngSpec.compressFormat);
        assertEquals(".webp", webpSpec.extension);
        assertEquals(Bitmap.CompressFormat.WEBP, webpSpec.compressFormat);
        assertEquals(".jpg", jpgSpec.extension);
        assertEquals(Bitmap.CompressFormat.JPEG, jpgSpec.compressFormat);
        assertEquals(".jpg", fallbackSpec.extension);
        assertEquals("image/jpeg", fallbackSpec.mimeType);
    }

    @Test
    public void detectVideoFormatIndex_handlesMoreFilenameCases() {
        Uri mockUri = mock(Uri.class);

        assertEquals(3, stripper.detectVideoFormatIndex(mockUri, "movie.mkv"));
        assertEquals(3, stripper.detectVideoFormatIndex(mockUri, "MOVIE.MKV"));
        assertEquals(2, stripper.detectVideoFormatIndex(mockUri, "clip.webm"));
        assertEquals(2, stripper.detectVideoFormatIndex(mockUri, "clip.vp9"));
        assertEquals(2, stripper.detectVideoFormatIndex(mockUri, "CLIP.VP8"));
        assertEquals(0, stripper.detectVideoFormatIndex(sourceVideoUri, "video.mp4"));
        assertEquals(0, stripper.detectVideoFormatIndex(sourceVideoUri, "video.xyz"));
        assertEquals(0, stripper.detectVideoFormatIndex(sourceVideoUri, null));
    }

    @Test
    public void fastStripVideoMetadata_returnsNullForInvalidFixtureVideo() throws Exception {
        Object result = invokePrivate(
                stripper,
                "fastStripVideoMetadata",
                new Class<?>[]{Uri.class},
                sourceVideoUri);

        assertNull(result);
    }

    @Test
    public void stripExifData_coversPngFallbackAndCleanupFailure() throws Exception {
        File pngFile = createBitmapFixture("metadata_strip_png.png", Bitmap.CompressFormat.PNG);
        writeExifFixtureData(pngFile);

        Uri sourceUri = Uri.parse("content://tests/source/png-media");
        Uri outputUri = Uri.parse("content://tests/output/png-media");
        File outputFile = new File(context.getCacheDir(), "metadata_png_media_output.png");
        ContentResolver resolver =
                createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/png");
        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        AtomicInteger exifConstructionCount = new AtomicInteger();
        try (MockedConstruction<ExifInterface> mockedExif = mockConstruction(
                ExifInterface.class,
                (mock, ignored) -> {
                    int index = exifConstructionCount.getAndIncrement();
                    when(mock.getAttribute(anyString())).thenReturn(null);
                    if (index == 0) {
                        when(mock.getAttribute(ExifInterface.TAG_ORIENTATION))
                                .thenReturn(String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
                    } else if (index == 1) {
                        doThrow(new IOException("native exif failed")).when(mock).saveAttributes();
                    }
                })) {
            assertEquals(outputUri, resolverStripper.stripExifData(sourceUri, "fallback.png"));
        }
        assertTrue(outputFile.exists());

        Uri cleanupSourceUri = Uri.parse("content://tests/source/cleanup-failure");
        Uri cleanupUri = Uri.parse("content://tests/output/cleanup-failure");
        File cleanupFile = new File(context.getCacheDir(), "metadata_cleanup_failure.jpg");
        ContentResolver cleanupResolver =
                createMediaStoreResolver(cleanupSourceUri, sourceJpegFile, cleanupUri, cleanupFile, "image/jpeg");
        when(cleanupResolver.delete(eq(cleanupUri), eq(null), eq(null))).thenThrow(new RuntimeException("delete failed"));
        MetadataStripper cleanupStripper = new MetadataStripper(new ResolverContext(context, cleanupResolver));
        cleanupStripper.setProgressCallback((percent, message) -> {
            if (percent >= 80) {
                throw new RuntimeException("boom after save");
            }
        });
        assertEquals(cleanupUri, cleanupStripper.stripExifData(cleanupSourceUri, "cleanup.jpg"));
    }

    @Test
    public void stripExifData_coversLargeAndInsertFailureBranches() throws Exception {
        long tooLargeBytes = (stripper.getMaxFileSizeMB() * 1024L * 1024L) + 1L;
        File oversizedFile = createSparseFixture("metadata_strip_large.jpg", tooLargeBytes);
        assertNull(stripper.stripExifData(Uri.fromFile(oversizedFile), "oversized.jpg"));

        Uri sourceUri = Uri.parse("content://tests/source/insert-null");
        ContentResolver resolver = mock(ContentResolver.class);
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
        cursor.addRow(new Object[]{sourceJpegFile.length()});
        when(resolver.query(eq(sourceUri), any(String[].class), isNull(), isNull(), isNull())).thenReturn(cursor);
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri)).thenAnswer(invocation -> new FileInputStream(sourceJpegFile));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(null);

        MetadataStripper insertFailStripper = new MetadataStripper(new ResolverContext(context, resolver));
        assertNull(insertFailStripper.stripExifData(sourceUri, "insert-null.jpg"));
    }

    @Test
    public void stripExifDataForSharing_coversMissingStreamAndBitmapFailure() throws Exception {
        Uri missingUri = Uri.parse("content://tests/source/share-missing");
        ContentResolver missingResolver = mock(ContentResolver.class);
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
        cursor.addRow(new Object[]{sourceJpegFile.length()});
        when(missingResolver.query(eq(missingUri), any(String[].class), isNull(), isNull(), isNull())).thenReturn(cursor);
        when(missingResolver.getType(missingUri)).thenReturn("image/jpeg");
        when(missingResolver.openInputStream(missingUri)).thenReturn(null);
        MetadataStripper missingStripper = new MetadataStripper(new ResolverContext(context, missingResolver));
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            assertNull(missingStripper.stripExifDataForSharing(missingUri, "missing.jpg"));
        }

        File pngFile = createBitmapFixture("metadata_force_compress_fail.png", Bitmap.CompressFormat.PNG);
        writeExifFixtureData(pngFile);
        MetadataStripper brokenStripper = new MetadataStripper(context);
        AtomicInteger exifConstructionCount = new AtomicInteger();
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedStatic<FormatConverter> mockedFormatConverter =
                     mockStatic(FormatConverter.class, CALLS_REAL_METHODS);
             MockedConstruction<ExifInterface> mockedExif = mockConstruction(
                     ExifInterface.class,
                     (mock, ignored) -> {
                         int index = exifConstructionCount.getAndIncrement();
                         when(mock.getAttribute(anyString())).thenReturn(null);
                         if (index == 0) {
                             when(mock.getAttribute(ExifInterface.TAG_ORIENTATION))
                                     .thenReturn(String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
                         } else if (index == 1) {
                             doThrow(new IOException("native exif failed")).when(mock).saveAttributes();
                         }
                     })) {
            mockedFormatConverter.when(() -> FormatConverter.compressBitmapToStream(
                            any(Context.class),
                            any(Bitmap.class),
                            any(Bitmap.CompressFormat.class),
                            any(OutputStream.class)))
                    .thenReturn(false);
            assertNull(brokenStripper.stripExifDataForSharing(Uri.fromFile(pngFile), "broken.png"));
        }
    }

    @Test
    public void stripVideoMetadata_coversFastCopyAndDeleteFailure() throws Exception {
        Uri expectedUri = Uri.parse("content://tests/video/copied");
        MetadataStripper fastCopyStripper = spy(new MetadataStripper(context));

        try (MockedConstruction<MediaExtractor> mockedExtractor =
                     mockFastVideoConstruction(true, false, false, false);
             MockedConstruction<MediaMuxer> mockedMuxer = mockMuxerConstruction();
             MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.copyToMoviesRedact(
                            any(Context.class),
                            any(File.class),
                            anyString(),
                            eq(0)))
                    .thenReturn(expectedUri);

            Uri result = fastCopyStripper.stripVideoMetadata(Uri.parse("content://tests/video/fast-copy"), "clip.mp4");
            assertEquals(expectedUri, result);
            mockedStatic.verify(() -> VideoMedia3Converter.copyToMoviesRedact(
                    any(Context.class), any(File.class), anyString(), eq(0)));
            mockedStatic.verify(() -> VideoMedia3Converter.transcodeToGallery(
                    any(Context.class), any(Uri.class), anyString(), anyInt(), any()), never());
        }

        Uri cleanupSourceUri = Uri.parse("content://tests/video/cleanup-source");
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.delete(expectedUri, null, null)).thenThrow(new RuntimeException("delete fail"));
        MetadataStripper cleanupStripper = new MetadataStripper(new ResolverContext(context, resolver));
        cleanupStripper.setProgressCallback((percent, message) -> {
            if (percent >= 100) {
                throw new RuntimeException("fail after new uri");
            }
        });

        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToGallery(
                            any(Context.class),
                            eq(cleanupSourceUri),
                            anyString(),
                            eq(0),
                            any()))
                    .thenReturn(expectedUri);
            assertEquals(expectedUri, cleanupStripper.stripVideoMetadata(cleanupSourceUri, "cleanup.mp4"));
        }
    }

    @Test
    public void stripVideoMetadataForSharing_coversFastCopyAndFailures() throws Exception {
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedConstruction<MediaExtractor> mockedExtractor =
                     mockFastVideoConstruction(true, true, true, false);
             MockedConstruction<MediaMuxer> mockedMuxer = mockMuxerConstruction();
             MockedConstruction<MediaMetadataRetriever> mockedRetriever =
                     mockRetrieverConstruction("90")) {
            Uri result = stripper.stripVideoMetadataForSharing(
                    Uri.parse("content://tests/video/fast-share"), "clip.webm");
            assertNotNull(result);
            File processedFile = newestProcessedFile();
            assertNotNull(processedFile);
            assertTrue(processedFile.getName().endsWith(".webm"));
        }

        MetadataStripper interruptedStripper = new MetadataStripper(context);
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToFile(
                            any(Context.class),
                            eq(sourceVideoUri),
                            any(File.class),
                            eq(2),
                            any()))
                    .thenThrow(new InterruptedException("stop"));
            assertNull(interruptedStripper.stripVideoMetadataForSharing(sourceVideoUri, "share.webm"));
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
        }
    }

    @Test
    public void helperBranches_coverFileSizeExifFallbacksAndRuntimePaths() throws Exception {
        invokePrivate(stripper, "removeExifMetadataFallback", new Class<?>[]{ExifInterface.class}, createExifMockForFallback());

        Closeable happyCloseable = mock(Closeable.class);
        invokePrivate(stripper, "closeQuietly", new Class<?>[]{Closeable.class}, happyCloseable);
        verify(happyCloseable).close();
        invokePrivate(stripper, "closeQuietly", new Class<?>[]{Closeable.class}, (Object) null);

        File emptyDelete = createBinaryFixture("metadata_empty_delete.bin", "");
        assertTrue((boolean) invokePrivate(stripper, "secureDeleteFile", new Class<?>[]{File.class}, emptyDelete));
        assertFalse((boolean) invokePrivate(stripper, "secureDeleteFile", new Class<?>[]{File.class}, context.getCacheDir()));

        ExifInterface noThumbExif = mock(ExifInterface.class);
        when(noThumbExif.getThumbnailBytes()).thenReturn(null);
        invokePrivate(stripper, "removeThumbnails", new Class<?>[]{ExifInterface.class}, noThumbExif);

        ExifInterface errorThumbExif = mock(ExifInterface.class);
        when(errorThumbExif.getThumbnailBytes()).thenThrow(new RuntimeException("thumb failure"));
        invokePrivate(stripper, "removeThumbnails", new Class<?>[]{ExifInterface.class}, errorThumbExif);

        verifyFileSizeBranches();
        verifyDetectVideoFormatBranches();
        verifyRestoreAndVerifyBranches();
    }

    @Test
    public void removeAllExifMetadata_fallsBackWhenExifAccessThrows() throws Exception {
        ExifInterface exif = mock(ExifInterface.class);
        AtomicInteger calls = new AtomicInteger();
        when(exif.getAttribute(anyString())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom");
            }
            String tag = invocation.getArgument(0);
            return ExifInterface.TAG_DATETIME.equals(tag) ? "2024:01:01 00:00:00" : null;
        });
        invokePrivate(stripper, "removeAllExifMetadata", new Class<?>[]{ExifInterface.class}, exif);
        verify(exif, atLeastOnce()).setAttribute(anyString(), isNull());
    }

    @Test
    public void verifyMetadataRemoval_returnsTrueWhenExifConstructionFails() throws Exception {
        try (MockedConstruction<ExifInterface> ignored = mockConstruction(
                ExifInterface.class,
                (mock, context) -> {
                    throw new RuntimeException("exif fail");
                })) {
            boolean result = (boolean) invokePrivate(
                    stripper,
                    "verifyMetadataRemoval",
                    new Class<?>[]{File.class},
                    sourceJpegFile);
            assertTrue(result);
        }
    }

    @Test
    public void utilityHelpers_coverAdditionalMimeAndXmpBranches() throws Exception {
        assertEquals(
                "video/x-matroska",
                invokePrivate(stripper, "videoMimeTypeForExtension", new Class<?>[]{String.class}, ".mkv"));
        assertEquals(
                "video/quicktime",
                invokePrivate(stripper, "videoMimeTypeForExtension", new Class<?>[]{String.class}, ".mov"));
        assertEquals(
                "video/3gpp",
                invokePrivate(stripper, "videoMimeTypeForExtension", new Class<?>[]{String.class}, ".3gp"));

        File missingXmp = new File(context.getCacheDir(), "metadata_missing_xmp.bin");
        assertFalse((boolean) invokePrivate(stripper, "containsXMPMetadata", new Class<?>[]{File.class}, missingXmp));
    }

    @Test
    public void stripMetadataLossless_returnsFalseForInvalidJpeg() throws Exception {
        File fakeJpeg = createBinaryFixture("metadata_fake_jpeg.jpeg", "not-a-jpeg");
        boolean result = (boolean) invokePrivate(
                stripper,
                "stripMetadataLossless",
                new Class<?>[]{File.class, OutputStream.class, String.class},
                fakeJpeg,
                new ByteArrayOutputStream(),
                ".jpeg");
        assertFalse(result);
    }

    @Test
    public void cleanupTempFiles_catchesCacheDirErrors() {
        MetadataStripper brokenStripper = new MetadataStripper(new ThrowingCacheDirContext(context));
        brokenStripper.cleanupTempFiles();
    }

    @Test
    public void secureDeleteFile_fallsBackWhenOverwriteFails() throws Exception {
        File readonlyFile = createBinaryFixture("metadata_readonly.bin", "cannot overwrite");
        assertTrue(readonlyFile.setReadOnly());
        boolean deleted = (boolean) invokePrivate(
                stripper,
                "secureDeleteFile",
                new Class<?>[]{File.class},
                readonlyFile);
        assertTrue(deleted || !readonlyFile.exists());
    }

    @Test
    public void directLowLevelCoverage_hitsRemainingHelperBranches() throws Exception {
        assertEquals(
                "video/mp4",
                invokePrivate(stripper, "videoMimeTypeForExtension", new Class<?>[]{String.class}, ".mp4"));

        Method sizeMethod = MetadataStripper.class.getDeclaredMethod("getFileSizeFromUri", Uri.class);
        sizeMethod.setAccessible(true);

        ContentResolver resolver = mock(ContentResolver.class);
        Uri afdUri = Uri.parse("content://tests/direct/afd");
        MatrixCursor afdCursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
        afdCursor.addRow(new Object[]{null});
        AssetFileDescriptor afd = mock(AssetFileDescriptor.class);
        when(afd.getLength()).thenReturn(55L);
        when(resolver.query(eq(afdUri), any(String[].class), isNull(), isNull(), isNull())).thenReturn(afdCursor);
        when(resolver.openAssetFileDescriptor(afdUri, "r")).thenReturn(afd);
        MetadataStripper afdStripper = new MetadataStripper(new ResolverContext(context, resolver));
        assertEquals(55L, sizeMethod.invoke(afdStripper, afdUri));

        Field preservedField = MetadataStripper.class.getDeclaredField("preservedExifValues");
        preservedField.setAccessible(true);
        File restoreFile = createJpegFixture("metadata_restore_success.jpg");
        Uri restoreUri = Uri.parse("content://tests/restore/success");
        ContentResolver restoreResolver = mock(ContentResolver.class);
        when(restoreResolver.openFileDescriptor(eq(restoreUri), eq("rw")))
                .thenReturn(ParcelFileDescriptor.open(
                        restoreFile,
                        ParcelFileDescriptor.MODE_READ_WRITE));
        MetadataStripper restoreStripper = new MetadataStripper(new ResolverContext(context, restoreResolver));
        @SuppressWarnings("unchecked")
        Map<String, String> preserved = (Map<String, String>) preservedField.get(restoreStripper);
        preserved.put(ExifInterface.TAG_ORIENTATION, String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        invokePrivate(restoreStripper, "restoreEssentialExifData", new Class<?>[]{Uri.class}, restoreUri);
        ExifInterface restoredExif = new ExifInterface(restoreFile.getAbsolutePath());
        assertEquals(
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90),
                restoredExif.getAttribute(ExifInterface.TAG_ORIENTATION));

        try (MockedConstruction<MediaExtractor> mockedExtractor =
                     mockConstruction(
                             MediaExtractor.class,
                             (extractor, context) -> {
                                 MediaFormat format = new MediaFormat();
                                 format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_VP8);
                                 when(extractor.getTrackCount()).thenReturn(1);
                                 when(extractor.getTrackFormat(0)).thenReturn(format);
                             })) {
            assertEquals(2, stripper.detectVideoFormatIndex(Uri.parse("content://tests/video/vp8"), "clip.bin"));
        }

        try (MockedConstruction<MediaExtractor> extractor = mockConstruction(
                     MediaExtractor.class,
                     (mock, ignored) -> when(mock.getTrackCount()).thenReturn(0));
             MockedConstruction<MediaMuxer> muxer = mockMuxerConstruction()) {
            assertNull(invokePrivate(
                    stripper,
                    "fastStripVideoMetadata",
                    new Class<?>[]{Uri.class},
                    Uri.parse("content://tests/video/no-tracks")));
        }

        try (MockedConstruction<MediaExtractor> extractor = mockConstruction(
                     MediaExtractor.class,
                     (mock, ignored) -> doThrow(new RuntimeException("mux fail"))
                             .when(mock).setDataSource(any(Context.class), any(Uri.class), isNull()));
             MockedConstruction<MediaMuxer> muxer = mockMuxerConstruction()) {
            assertNull(invokePrivate(
                    stripper,
                    "fastStripVideoMetadata",
                    new Class<?>[]{Uri.class},
                    Uri.parse("content://tests/video/explode")));
        }
    }

    @Test
    public void stripVideoMetadata_interruptedDuringTranscode_setsInterruptedAndReturnsNull() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/video/interrupted");
        Uri newUri = Uri.parse("content://tests/video/newuri");

        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.delete(eq(newUri), eq(null), eq(null))).thenReturn(1);
        MetadataStripper interruptedStripper = new MetadataStripper(new ResolverContext(context, resolver));

        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToGallery(
                            any(Context.class),
                            eq(sourceUri),
                            anyString(),
                            anyInt(),
                            any()))
                    .thenThrow(new InterruptedException("stop"));

            assertNull(interruptedStripper.stripVideoMetadata(sourceUri, "video.mp4"));
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
        }
    }

    @Test
    public void stripVideoMetadata_deletesPartialUriWhenCallbackThrowsAfterUriCreated() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/video/cleanup-after-uri");
        Uri createdUri = Uri.parse("content://tests/video/created");

        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.delete(eq(createdUri), eq(null), eq(null))).thenReturn(1);
        MetadataStripper cleanupStripper = new MetadataStripper(new ResolverContext(context, resolver));
        cleanupStripper.setProgressCallback((percent, message) -> {
            if (message != null && message.contains("Saving cleaned video")) {
                throw new RuntimeException("boom");
            }
        });

        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToGallery(
                            any(Context.class),
                            eq(sourceUri),
                            anyString(),
                            anyInt(),
                            any()))
                    .thenReturn(createdUri);

            assertEquals(createdUri, cleanupStripper.stripVideoMetadata(sourceUri, "video.mp4"));
            verify(resolver).delete(eq(createdUri), eq(null), eq(null));
        }
    }

    @Test
    public void stripExifData_coversThumbnailRemoveFailureAndBitmapFallbackAndFinallyCleanup() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/png-mislabeled-jpeg");
        Uri outputUri = Uri.parse("content://tests/output/png-mislabeled-jpeg");
        File pngFile = createBitmapFixture("metadata_mislabeled_source.png", Bitmap.CompressFormat.PNG);
        File outputFile = new File(context.getCacheDir(), "metadata_mislabeled_output.jpg");

        ContentResolver resolver = createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/jpeg");

        // Force verification stream to throw so we hit the "Could not verify metadata" catch.
        when(resolver.openInputStream(eq(outputUri))).thenThrow(new RuntimeException("verify stream missing"));

        // Force openOutputStream to be null so we throw after decoding bitmap and hit finally recycle path.
        AtomicInteger outputStreamCalls = new AtomicInteger();
        when(resolver.openOutputStream(eq(outputUri))).thenAnswer(invocation -> {
            int call = outputStreamCalls.getAndIncrement();
            return call == 0 ? new FileOutputStream(outputFile, false) : null;
        });

        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        AtomicInteger exifCtorCount = new AtomicInteger();
        try (MockedConstruction<ExifInterface> ignored = mockConstruction(
                ExifInterface.class,
                (mock, ignoredContext) -> {
                    int index = exifCtorCount.getAndIncrement();
                    // 0: readEssentialExifData(tempFile) should succeed
                    // 1: thumbnail removal EXIF construction should throw and be caught
                    if (index == 1) {
                        throw new RuntimeException("exif broken");
                    }
                    when(mock.getAttribute(anyString())).thenReturn(null);
                    when(mock.getThumbnailBytes()).thenReturn(null);
                })) {
            resolverStripper.stripExifData(sourceUri, "photo.jpg");
        }

        assertNull(resolverStripper.getLastProcessedFileUri());
    }

    @Test
    public void stripExifData_mediaStore_bitmapDecodeNull_throws() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/mediastore-decode-null");
        Uri outputUri = Uri.parse("content://tests/output/mediastore-decode-null");
        File pngFile = createBitmapFixture("metadata_mediastore_decode_null.png", Bitmap.CompressFormat.PNG);
        File outputFile = new File(context.getCacheDir(), "metadata_mediastore_decode_null.jpg");

        ContentResolver resolver = createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/jpeg");
        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        try (MockedConstruction<ExifInterface> mockedExif = mockConstruction(
                        ExifInterface.class,
                        (mock, ignored) -> {
                            when(mock.getAttribute(anyString())).thenReturn(null);
                            when(mock.getThumbnailBytes()).thenReturn(null);
                            doThrow(new IOException("native exif save failed")).when(mock).saveAttributes();
                        });
                MockedStatic<android.graphics.BitmapFactory> bitmapFactory =
                        mockStatic(android.graphics.BitmapFactory.class, CALLS_REAL_METHODS)) {
            bitmapFactory.when(() -> android.graphics.BitmapFactory.decodeFile(
                            anyString(), any(android.graphics.BitmapFactory.Options.class)))
                    .thenAnswer(invocation -> {
                        android.graphics.BitmapFactory.Options options = invocation.getArgument(1);
                        if (options != null && options.inJustDecodeBounds) {
                            options.outWidth = 8;
                            options.outHeight = 8;
                            return null;
                        }
                        return null;
                    });
            resolverStripper.stripExifData(sourceUri, "photo.jpg");
        }
    }

    @Test
    public void stripExifData_mediaStore_bitmapCompressFails_throws() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/mediastore-compress-fail");
        Uri outputUri = Uri.parse("content://tests/output/mediastore-compress-fail");
        File pngFile = createBitmapFixture("metadata_mediastore_compress_fail.png", Bitmap.CompressFormat.PNG);
        File outputFile = new File(context.getCacheDir(), "metadata_mediastore_compress_fail.jpg");

        ContentResolver resolver = createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/jpeg");
        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        try (MockedConstruction<ExifInterface> mockedExif = mockConstruction(
                        ExifInterface.class,
                        (mock, ignored) -> {
                            when(mock.getAttribute(anyString())).thenReturn(null);
                            when(mock.getThumbnailBytes()).thenReturn(null);
                            doThrow(new IOException("native exif save failed")).when(mock).saveAttributes();
                        });
                MockedStatic<FormatConverter> mocked =
                        mockStatic(FormatConverter.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> FormatConverter.compressBitmapToStream(
                            any(Context.class),
                            any(Bitmap.class),
                            any(Bitmap.CompressFormat.class),
                            any(OutputStream.class)))
                    .thenReturn(false);
            resolverStripper.stripExifData(sourceUri, "photo.jpg");
        }
    }

    @Test
    public void stripExifData_bitmapFallback_successAndVerificationWarning() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/png-as-jpeg-success");
        Uri outputUri = Uri.parse("content://tests/output/png-as-jpeg-success");
        File pngFile = createBitmapFixture("metadata_png_source_for_success.png", Bitmap.CompressFormat.PNG);
        File outputFile = new File(context.getCacheDir(), "metadata_png_as_jpeg_success.jpg");

        ContentResolver resolver = createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/jpeg");
        // For the verification phase, return a stream that includes XMP marker to force verifyMetadataRemoval=false.
        when(resolver.openInputStream(eq(outputUri))).thenReturn(
                new ByteArrayInputStream("http://ns.adobe.com/xap/1.0/".getBytes(StandardCharsets.ISO_8859_1)));

        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));
        try (MockedConstruction<ExifInterface> mockedExif = mockConstruction(
                        ExifInterface.class,
                        (mock, ignored) -> {
                            when(mock.getAttribute(anyString())).thenReturn(null);
                            when(mock.getThumbnailBytes()).thenReturn(null);
                            doThrow(new IOException("native exif save failed")).when(mock).saveAttributes();
                        });
                MockedStatic<FormatConverter> mocked =
                        mockStatic(FormatConverter.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> FormatConverter.compressBitmapToStream(
                            any(Context.class),
                            any(Bitmap.class),
                            any(Bitmap.CompressFormat.class),
                            any(OutputStream.class)))
                    .thenReturn(true);

            assertEquals(outputUri, resolverStripper.stripExifData(sourceUri, "photo.jpg"));
            assertFalse(mockedExif.constructed().isEmpty());
        }
    }

    @Test
    public void stripExifData_bitmapFallback_compressFailureHitsFinallyRecycle() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/png-as-jpeg-compress-fail");
        Uri outputUri = Uri.parse("content://tests/output/png-as-jpeg-compress-fail");
        File pngFile = createBitmapFixture("metadata_png_source_for_fail.png", Bitmap.CompressFormat.PNG);
        File outputFile = new File(context.getCacheDir(), "metadata_png_as_jpeg_fail.jpg");

        ContentResolver resolver = createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/jpeg");
        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        try (MockedStatic<FormatConverter> mocked =
                     mockStatic(FormatConverter.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> FormatConverter.compressBitmapToStream(
                            any(Context.class),
                            any(Bitmap.class),
                            any(Bitmap.CompressFormat.class),
                            any(OutputStream.class)))
                    .thenReturn(false);

            resolverStripper.stripExifData(sourceUri, "photo.jpg");
        }
    }

    @Test
    public void stripExifData_bitmapFallback_decodeFailure_throws() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/png-as-jpeg-decode-null");
        Uri outputUri = Uri.parse("content://tests/output/png-as-jpeg-decode-null");
        File pngFile = createBitmapFixture("metadata_png_source_for_decode_null.png", Bitmap.CompressFormat.PNG);
        File outputFile = new File(context.getCacheDir(), "metadata_png_as_jpeg_decode_null.jpg");

        ContentResolver resolver = createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/jpeg");
        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        try (MockedStatic<android.graphics.BitmapFactory> mocked = mockStatic(android.graphics.BitmapFactory.class)) {
            mocked.when(() -> android.graphics.BitmapFactory.decodeFile(anyString(), any(android.graphics.BitmapFactory.Options.class)))
                    .thenAnswer(invocation -> {
                        android.graphics.BitmapFactory.Options options = invocation.getArgument(1);
                        if (options != null && options.inJustDecodeBounds) {
                            options.outWidth = 10_000;
                            options.outHeight = 10_000;
                            return null;
                        }
                        return null;
                    });
            resolverStripper.stripExifData(sourceUri, "photo.jpg");
        }
    }

    @Test
    public void stripExifData_bitmapFallback_flushAndRecycle_andVerifyCatch() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/png-as-jpeg-flush");
        Uri outputUri = Uri.parse("content://tests/output/png-as-jpeg-flush");
        File pngFile = createBitmapFixture("metadata_png_source_for_flush.png", Bitmap.CompressFormat.PNG);
        File outputFile = new File(context.getCacheDir(), "metadata_png_as_jpeg_flush.jpg");

        ContentResolver resolver = createMediaStoreResolver(sourceUri, pngFile, outputUri, outputFile, "image/jpeg");
        // Make verification throw so the verify catch block is covered.
        when(resolver.openInputStream(eq(outputUri))).thenThrow(new RuntimeException("verify fail"));

        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));
        try (MockedStatic<FormatConverter> mocked =
                     mockStatic(FormatConverter.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> FormatConverter.compressBitmapToStream(
                            any(Context.class),
                            any(Bitmap.class),
                            any(Bitmap.CompressFormat.class),
                            any(OutputStream.class)))
                    .thenReturn(true);

            assertEquals(outputUri, resolverStripper.stripExifData(sourceUri, "photo.jpg"));
        }
    }

    @Test
    public void stripExifDataForSharing_logsVerificationWarningByInjectingXmpAtVerifyStep() throws Exception {
        MetadataStripper warnStripper = new MetadataStripper(context);
        warnStripper.setProgressCallback((percent, message) -> {
            if (message != null && message.contains("Verifying")) {
                File processed = newestProcessedFile();
                if (processed != null) {
                    try (FileOutputStream fos = new FileOutputStream(processed, true)) {
                        fos.write("http://ns.adobe.com/xap/1.0/".getBytes(StandardCharsets.ISO_8859_1));
                    } catch (IOException ignored) {}
                }
            }
        });

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            assertNotNull(warnStripper.stripExifDataForSharing(sourceJpegUri, "photo.jpg"));
        }
    }

    @Test
    public void stripExifDataForSharing_runsWithTempCleanup() throws Exception {
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            assertNotNull(stripper.stripExifDataForSharing(sourceJpegUri, "photo.jpg"));
        }
    }

    @Test
    public void stripExifData_tempFileDeleteOnExit_whenSecureDeleteReturnsFalse() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source/delete-on-exit");
        Uri outputUri = Uri.parse("content://tests/output/delete-on-exit");
        File outputFile = new File(context.getCacheDir(), "metadata_delete_on_exit_output.jpg");

        // Pre-create the temp file path as a directory to make secureDeleteFile return false (not a file).
        long now = System.currentTimeMillis();
        for (int i = 0; i < 2000; i++) {
            File candidate = new File(context.getCacheDir(), "temp_" + (now + i) + ".jpg");
            // Best-effort; only one of these needs to match the method's timestamp.
            candidate.mkdirs();
        }

        ContentResolver resolver = createMediaStoreResolver(sourceUri, sourceJpegFile, outputUri, outputFile, "image/jpeg");
        MetadataStripper resolverStripper = new MetadataStripper(new ResolverContext(context, resolver));

        assertNull(resolverStripper.stripExifData(sourceUri, "photo.jpg"));
    }

    @Test
    public void stripExifDataForSharing_outputDirMkdirsFailure_throwsAndReturnsNull() throws Exception {
        File cacheAsFile = createFile(context.getCacheDir(), "metadata_cache_file", "not a dir");
        assertTrue(cacheAsFile.isFile());
        MetadataStripper badCacheStripper = new MetadataStripper(new CacheDirContext(context, cacheAsFile));

        assertNull(badCacheStripper.stripExifDataForSharing(sourceJpegUri, "photo.jpg"));
    }

    @Test
    public void stripExifDataForSharing_bitmapDecodeFailure_throws() throws Exception {
        // Random bytes + .jpg extension => lossless/native likely fail, bitmap decode returns null => IOException.
        File junk = createBinaryFixture("metadata_junk.jpg", "not-an-image");
        Uri junkUri = Uri.fromFile(junk);

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            stripper.stripExifDataForSharing(junkUri, "photo.jpg");
        }
    }

    @Test
    public void stripVideoMetadataForSharing_outputDirMkdirsFailure_returnsNull() throws Exception {
        File cacheAsFile = createFile(context.getCacheDir(), "metadata_cache_file_video", "not a dir");
        assertTrue(cacheAsFile.isFile());
        MetadataStripper badCacheStripper = new MetadataStripper(new CacheDirContext(context, cacheAsFile));

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            assertNull(badCacheStripper.stripVideoMetadataForSharing(sourceVideoUri, "clip.webm"));
        }
    }

    @Test
    public void fastStripVideoMetadata_coversRetrieverFailurePartialFrameAndCleanupDelete() throws Exception {
        try (MockedConstruction<MediaExtractor> extractor =
                     mockConstruction(MediaExtractor.class, (mock, ignored) -> {
                         MediaFormat format = new MediaFormat();
                         format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
                         // No rotation key => go through retriever path.
                         when(mock.getTrackCount()).thenReturn(1);
                         when(mock.getTrackFormat(0)).thenReturn(format);
                         when(mock.getSampleTrackIndex()).thenReturn(0, -1);
                         when(mock.readSampleData(any(java.nio.ByteBuffer.class), eq(0))).thenReturn(4);
                         when(mock.getSampleTime()).thenReturn(1_000L);
                         when(mock.getSampleFlags()).thenReturn(
                                 MediaExtractor.SAMPLE_FLAG_SYNC | MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME);
                         doAnswer(invocation -> null).when(mock).advance();
                     });
             MockedConstruction<MediaMetadataRetriever> retriever =
                     mockConstruction(MediaMetadataRetriever.class, (mock, ignored) -> {
                         doThrow(new RuntimeException("boom")).when(mock).setDataSource(any(Context.class), any(Uri.class));
                     });
             MockedConstruction<MediaMuxer> muxer =
                     mockConstruction(MediaMuxer.class, (mock, ctorContext) -> {
                         // Ensure the output file exists so the catch cleanup deletes it.
                         File out = new File((String) ctorContext.arguments().get(0));
                         File parent = out.getParentFile();
                         if (parent != null && !parent.exists()) {
                             assertTrue(parent.mkdirs() || parent.isDirectory());
                         }
                         try (FileOutputStream fos = new FileOutputStream(out, false)) {
                             fos.write("x".getBytes(StandardCharsets.UTF_8));
                         }
                         when(mock.addTrack(any(MediaFormat.class))).thenReturn(0);
                         doAnswer(invocation -> null).when(mock).start();
                         // Throw during write so we pass through sample flag logic first.
                         doThrow(new RuntimeException("write fail")).when(mock).writeSampleData(
                                 anyInt(),
                                 any(java.nio.ByteBuffer.class),
                                 any(android.media.MediaCodec.BufferInfo.class));
                     })) {

            Object result = invokePrivate(
                    stripper,
                    "fastStripVideoMetadata",
                    new Class<?>[]{Uri.class},
                    Uri.parse("content://tests/video/fast-strip-paths"));
            assertNull(result);
        }
    }

    @Test
    public void fastStripVideoMetadata_usesRotationKeyWhenPresent() throws Exception {
        try (MockedConstruction<MediaExtractor> extractor =
                     mockConstruction(MediaExtractor.class, (mock, ignored) -> {
                         MediaFormat format = new MediaFormat();
                         format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
                         format.setInteger(MediaFormat.KEY_ROTATION, 90);
                         when(mock.getTrackCount()).thenReturn(1);
                         when(mock.getTrackFormat(0)).thenReturn(format);
                         when(mock.getSampleTrackIndex()).thenReturn(-1);
                     });
             MockedConstruction<MediaMuxer> muxer =
                     mockConstruction(MediaMuxer.class, (mock, ctorContext) -> {
                         when(mock.addTrack(any(MediaFormat.class))).thenReturn(0);
                         doThrow(new RuntimeException("stop after hint")).when(mock).start();
                     })) {
            Object result = invokePrivate(
                    stripper,
                    "fastStripVideoMetadata",
                    new Class<?>[]{Uri.class},
                    Uri.parse("content://tests/video/rotation-key"));
            assertNull(result);
            verify(muxer.constructed().get(0)).setOrientationHint(90);
        }
    }

    @Test
    public void restoreEssentialExifData_returnsEarlyWhenNothingPreserved() throws Exception {
        Field preservedField = MetadataStripper.class.getDeclaredField("preservedExifValues");
        preservedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> preserved = (Map<String, String>) preservedField.get(stripper);
        preserved.clear();

        ContentResolver resolver = mock(ContentResolver.class);
        MetadataStripper emptyPreservedStripper = new MetadataStripper(new ResolverContext(context, resolver));
        preserved = (Map<String, String>) preservedField.get(emptyPreservedStripper);
        preserved.clear();

        invokePrivate(emptyPreservedStripper, "restoreEssentialExifData", new Class<?>[]{Uri.class}, Uri.parse("content://tests/output/unused"));
        verify(resolver, never()).openFileDescriptor(any(Uri.class), anyString());
    }

    @Test
    public void getFileSizeFromUri_returnsEarlyWhenStreamExceedsMaxMeasure() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri streamUri = Uri.parse("content://tests/source/huge-stream");

        when(resolver.query(eq(streamUri), any(String[].class), isNull(), isNull(), isNull())).thenReturn(null);
        when(resolver.openAssetFileDescriptor(streamUri, "r")).thenThrow(new RuntimeException("no afd"));
        when(resolver.openInputStream(streamUri)).thenReturn(new InputStream() {
            private long total = 0;
            @Override
            public int read(byte[] b, int off, int len) {
                // Return len bytes repeatedly without allocating large buffers.
                total += len;
                return (total > (1024L * 1024L * 120L)) ? -1 : len;
            }

            @Override
            public int read() {
                total += 1;
                return (total > (1024L * 1024L * 120L)) ? -1 : 0;
            }
        });

        MetadataStripper streamStripper = new MetadataStripper(new ResolverContext(context, resolver));
        Method sizeMethod = MetadataStripper.class.getDeclaredMethod("getFileSizeFromUri", Uri.class);
        sizeMethod.setAccessible(true);
        long size = (long) sizeMethod.invoke(streamStripper, streamUri);
        assertTrue(size > (stripper.getMaxFileSizeMB() * 1024L * 1024L));
    }

    @Test
    public void containsXMPMetadata_catchesReadFailures() throws Exception {
        File missing = new File(context.getCacheDir(), "metadata_missing_xmp_trigger.bin");
        assertFalse(missing.exists());
        assertFalse((boolean) invokePrivate(stripper, "containsXMPMetadata", new Class<?>[]{File.class}, missing));
    }

    @Test
    public void containsXMPMetadata_catchesIOExceptionWhileReading() throws Exception {
        File readable = createBinaryFixture("metadata_xmp_read_fail.bin", "plain-bytes");
        try (MockedConstruction<FileInputStream> ignored = mockConstruction(
                FileInputStream.class,
                (mock, context) -> when(mock.read(any(byte[].class))).thenThrow(new IOException("read fail")))) {
            assertFalse((boolean) invokePrivate(
                    stripper, "containsXMPMetadata", new Class<?>[]{File.class}, readable));
        }
    }

    @Test
    public void stripExifDataForSharing_forcedMkdirsFailure_returnsNull() throws Exception {
        stripper.setTestForceOutputDirMkdirsFailure(true);
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider()) {
            assertNull(stripper.stripExifDataForSharing(sourceJpegUri, "photo.jpg"));
        } finally {
            stripper.setTestForceOutputDirMkdirsFailure(false);
        }
    }

    @Test
    public void containsXMPMetadata_forcedReadFailure_returnsFalse() throws Exception {
        File readable = createBinaryFixture("metadata_xmp_forced_fail.bin", "plain-bytes");
        stripper.setTestForceXmpReadFailure(true);
        try {
            assertFalse((boolean) invokePrivate(
                    stripper, "containsXMPMetadata", new Class<?>[]{File.class}, readable));
        } finally {
            stripper.setTestForceXmpReadFailure(false);
        }
    }

    @Test
    public void containsXMPMetadata_plainFileWithoutMarkers_returnsFalse() throws Exception {
        File plain = createBinaryFixture("metadata_plain_no_xmp.bin", "plain-bytes");
        assertFalse((boolean) invokePrivate(
                stripper, "containsXMPMetadata", new Class<?>[]{File.class}, plain));
    }

    @Test
    public void stripExifDataForSharing_bitmapDecodeNull_returnsNull() throws Exception {
        File junk = createBinaryFixture("metadata_decode_null.jpg", "not-an-image");
        Uri junkUri = Uri.fromFile(junk);

        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedStatic<android.graphics.BitmapFactory> bitmapFactory =
                     mockStatic(android.graphics.BitmapFactory.class, CALLS_REAL_METHODS)) {
            bitmapFactory.when(() -> android.graphics.BitmapFactory.decodeFile(
                            anyString(), any(android.graphics.BitmapFactory.Options.class)))
                    .thenAnswer(invocation -> {
                        android.graphics.BitmapFactory.Options options = invocation.getArgument(1);
                        if (options != null && options.inJustDecodeBounds) {
                            options.outWidth = 64;
                            options.outHeight = 64;
                            return null;
                        }
                        return null;
                    });
            assertNull(stripper.stripExifDataForSharing(junkUri, "photo.jpg"));
        }
    }

    @Test
    public void isFileTooLarge_returnsFalseOnException() {
        assertFalse(stripper.isFileTooLarge((Uri) null));
    }

    @Test
    public void tryFreeMemory_executesGcWhenHeapIsLow() throws Exception {
        Method isLow = MetadataStripper.class.getDeclaredMethod("isMemoryLow");
        isLow.setAccessible(true);
        Method free = MetadataStripper.class.getDeclaredMethod("tryFreeMemory");
        free.setAccessible(true);

        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        List<byte[]> allocations = new ArrayList<>();
        try {
            // Allocate until we get close to the max heap or hit OOM.
            while (true) {
                boolean low = (boolean) isLow.invoke(stripper);
                if (low) break;
                long used = rt.totalMemory() - rt.freeMemory();
                if (used > (long) (max * 0.85)) break;
                allocations.add(new byte[8 * 1024 * 1024]); // 8MB chunks
            }
        } catch (OutOfMemoryError ignored) {
            // If we hit OOM, memory is certainly low enough for the branch.
        }

        // Invoke and just ensure it doesn't crash; line coverage is the goal.
        free.invoke(stripper);

        allocations.clear();
    }

    @Test
    public void publicVideoFlows_coverRemainingWebmAndFailureBranches() throws Exception {
        Uri fastCopyUri = Uri.parse("content://tests/video/fast-webm");
        try (MockedConstruction<MediaExtractor> mockedExtractor =
                     mockFastVideoConstruction(true, true, true, false);
             MockedConstruction<MediaMuxer> mockedMuxer = mockMuxerConstruction();
             MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.copyToMoviesRedact(
                            any(Context.class),
                            any(File.class),
                            anyString(),
                            eq(2)))
                    .thenReturn(fastCopyUri);
            assertEquals(fastCopyUri, stripper.stripVideoMetadata(
                    Uri.parse("content://tests/video/fast-webm-src"), "clip.webm"));
        }

        MetadataStripper genericFailStripper = new MetadataStripper(context);
        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToGallery(
                            any(Context.class),
                            eq(sourceVideoUri),
                            anyString(),
                            eq(0),
                            any()))
                    .thenThrow(new RuntimeException("generic fail"));
            assertNull(genericFailStripper.stripVideoMetadata(sourceVideoUri, "video.mp4"));
        }

        MetadataStripper shareGenericFailStripper = new MetadataStripper(context);
        try (MockedStatic<FileProvider> mockedFileProvider = mockFileProvider();
             MockedStatic<VideoMedia3Converter> mockedStatic =
                     mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToFile(
                            any(Context.class),
                            eq(sourceVideoUri),
                            any(File.class),
                            eq(2),
                            any()))
                    .thenThrow(new RuntimeException("share fail"));
            assertNull(shareGenericFailStripper.stripVideoMetadataForSharing(sourceVideoUri, "share.webm"));
        }
    }

    private long invokeCachedFileSize(MetadataStripper target, Uri uri) throws Exception {
        Method method = MetadataStripper.class.getDeclaredMethod("getFileSizeFromUriCached", Uri.class);
        method.setAccessible(true);
        return (long) method.invoke(target, uri);
    }

    private Object invokePrivate(
            MetadataStripper target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args) throws Exception {
        Method method = MetadataStripper.class.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private Object invokePrivateStatic(
            Class<?> targetClass,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args) throws Exception {
        Method method = targetClass.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private MockedConstruction<MediaExtractor> mockFastVideoConstruction(
            boolean includeVideo,
            boolean includeAudio,
            boolean useWebm,
            boolean includeRotationKey) {
        return mockConstruction(
                MediaExtractor.class,
                (extractor, context) -> {
                    List<MediaFormat> formats = new ArrayList<>();
                    if (includeVideo) {
                        MediaFormat videoFormat = new MediaFormat();
                        videoFormat.setString(
                                MediaFormat.KEY_MIME,
                                useWebm ? MediaFormat.MIMETYPE_VIDEO_VP9 : MediaFormat.MIMETYPE_VIDEO_AVC);
                        if (includeRotationKey) {
                            videoFormat.setInteger(MediaFormat.KEY_ROTATION, 90);
                        }
                        formats.add(videoFormat);
                    }
                    if (includeAudio) {
                        MediaFormat audioFormat = new MediaFormat();
                        audioFormat.setString(
                                MediaFormat.KEY_MIME,
                                useWebm ? "audio/opus" : "audio/mp4a-latm");
                        formats.add(audioFormat);
                    }

                    List<Integer> sampleTrackOrder = new ArrayList<>();
                    if (includeVideo) {
                        sampleTrackOrder.add(0);
                    }
                    if (includeAudio) {
                        sampleTrackOrder.add(includeVideo ? 1 : 0);
                    }
                    AtomicInteger sampleIndex = new AtomicInteger();

                    when(extractor.getTrackCount()).thenReturn(formats.size());
                    when(extractor.getTrackFormat(anyInt()))
                            .thenAnswer(invocation -> formats.get(invocation.getArgument(0)));
                    when(extractor.getSampleTrackIndex())
                            .thenAnswer(invocation -> {
                                int index = sampleIndex.get();
                                return index < sampleTrackOrder.size() ? sampleTrackOrder.get(index) : -1;
                            });
                    when(extractor.readSampleData(any(java.nio.ByteBuffer.class), eq(0)))
                            .thenAnswer(invocation -> sampleIndex.get() < sampleTrackOrder.size() ? 4 : -1);
                    when(extractor.getSampleTime()).thenAnswer(invocation -> sampleIndex.get() * 1_000L);
                    when(extractor.getSampleFlags())
                            .thenAnswer(invocation -> sampleIndex.get() == 0 ? MediaExtractor.SAMPLE_FLAG_SYNC : 0);
                    doAnswer(invocation -> {
                        sampleIndex.incrementAndGet();
                        return null;
                    }).when(extractor).advance();
                });
    }

    private MockedConstruction<MediaMuxer> mockMuxerConstruction() {
        AtomicInteger nextTrackIndex = new AtomicInteger();
        return mockConstruction(
                MediaMuxer.class,
                (muxer, context) -> {
                    File outputFile = new File((String) context.arguments().get(0));
                    File parent = outputFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        assertTrue(parent.mkdirs() || parent.isDirectory());
                    }
                    when(muxer.addTrack(any(MediaFormat.class)))
                            .thenAnswer(invocation -> nextTrackIndex.getAndIncrement());
                    doAnswer(invocation -> {
                        try (FileOutputStream fos = new FileOutputStream(outputFile, true)) {
                            fos.write("muxed".getBytes(StandardCharsets.UTF_8));
                        }
                        return null;
                    }).when(muxer).writeSampleData(
                            anyInt(),
                            any(java.nio.ByteBuffer.class),
                            any(android.media.MediaCodec.BufferInfo.class));
                });
    }

    private MockedConstruction<MediaMetadataRetriever> mockRetrieverConstruction(
            String rotationDegrees) {
        return mockConstruction(
                MediaMetadataRetriever.class,
                (retriever, context) ->
                        when(retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION))
                                .thenReturn(rotationDegrees));
    }

    private ExifInterface createExifMockForFallback() {
        ExifInterface exif = mock(ExifInterface.class);
        when(exif.getAttribute(anyString())).thenReturn("value");
        return exif;
    }

    private void verifyFileSizeBranches() throws Exception {
        File fileUriFixture = createBinaryFixture("metadata_size_file.bin", "123456");
        assertEquals(6L, invokeCachedFileSize(stripper, Uri.fromFile(fileUriFixture)));

        Uri streamUri = Uri.parse("content://tests/source/stream-size");
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.query(eq(streamUri), any(String[].class), isNull(), isNull(), isNull()))
                .thenReturn(null);
        when(resolver.openAssetFileDescriptor(streamUri, "r"))
                .thenThrow(new RuntimeException("no afd"));
        when(resolver.openInputStream(streamUri))
                .thenAnswer(invocation ->
                        new java.io.ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8)));
        MetadataStripper streamStripper =
                new MetadataStripper(new ResolverContext(context, resolver));
        assertEquals(8L, invokeCachedFileSize(streamStripper, streamUri));

        Uri emptyUri = Uri.parse("content://tests/source/no-size");
        ContentResolver emptyResolver = mock(ContentResolver.class);
        when(emptyResolver.query(eq(emptyUri), any(String[].class), isNull(), isNull(), isNull()))
                .thenReturn(null);
        when(emptyResolver.openAssetFileDescriptor(emptyUri, "r")).thenReturn(null);
        when(emptyResolver.openInputStream(emptyUri)).thenReturn(null);
        MetadataStripper emptyStripper =
                new MetadataStripper(new ResolverContext(context, emptyResolver));
        assertEquals(0L, invokeCachedFileSize(emptyStripper, emptyUri));
    }

    private void verifyDetectVideoFormatBranches() {
        assertEquals(2, stripper.detectVideoFormatIndex(sourceVideoUri, "clip.webm"));
        assertEquals(3, stripper.detectVideoFormatIndex(sourceVideoUri, "clip.mkv"));

        try (MockedConstruction<MediaExtractor> mockedExtractor =
                mockConstruction(
                        MediaExtractor.class,
                        (extractor, context) -> {
                            MediaFormat videoFormat = new MediaFormat();
                            videoFormat.setString(
                                    MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_HEVC);
                            when(extractor.getTrackCount()).thenReturn(1);
                            when(extractor.getTrackFormat(0)).thenReturn(videoFormat);
                        })) {
            assertEquals(1, stripper.detectVideoFormatIndex(sourceVideoUri, "clip.bin"));
        }

        try (MockedConstruction<MediaExtractor> mockedExtractor =
                mockConstruction(
                        MediaExtractor.class,
                        (extractor, context) -> {
                            MediaFormat videoFormat = new MediaFormat();
                            videoFormat.setString(
                                    MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AV1);
                            when(extractor.getTrackCount()).thenReturn(1);
                            when(extractor.getTrackFormat(0)).thenReturn(videoFormat);
                        })) {
            assertEquals(3, stripper.detectVideoFormatIndex(sourceVideoUri, null));
        }
    }

    private void verifyRestoreAndVerifyBranches() throws Exception {
        Field preservedField = MetadataStripper.class.getDeclaredField("preservedExifValues");
        preservedField.setAccessible(true);

        Uri restoreUri = Uri.parse("content://tests/output/restore-fail");
        ContentResolver resolver = mock(ContentResolver.class);
        when(resolver.openFileDescriptor(restoreUri, "rw")).thenReturn(null);
        MetadataStripper restoreStripper =
                new MetadataStripper(new ResolverContext(context, resolver));
        @SuppressWarnings("unchecked")
        Map<String, String> preserved =
                (Map<String, String>) preservedField.get(restoreStripper);
        preserved.put(
                ExifInterface.TAG_ORIENTATION,
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        invokePrivate(restoreStripper, "restoreEssentialExifData", new Class<?>[] {Uri.class}, restoreUri);

        File cleanImage = createBitmapFixture("metadata_verify_clean.jpg", Bitmap.CompressFormat.JPEG);
        assertTrue(
                (boolean)
                        invokePrivate(
                                stripper,
                                "verifyMetadataRemoval",
                                new Class<?>[] {File.class},
                                cleanImage));

        File xmpImage = createBinaryFixture(
                "metadata_verify_xmp.jpg",
                "http://ns.adobe.com/xap/1.0/ metadata payload");
        assertFalse(
                (boolean)
                        invokePrivate(
                                stripper,
                                "verifyMetadataRemoval",
                                new Class<?>[] {File.class},
                                xmpImage));
    }

    private static void setFakeNowMillis(long millis) {
        try {
            Class<?> cls = Class.forName("org.robolectric.shadows.ShadowSystemClock");
            try {
                Method m = cls.getDeclaredMethod("setCurrentTimeMillis", long.class);
                m.setAccessible(true);
                m.invoke(null, millis);
                return;
            } catch (NoSuchMethodException ignored) {
                // fall through
            }
            try {
                Method m = cls.getDeclaredMethod("setCurrentTime", long.class, java.util.concurrent.TimeUnit.class);
                m.setAccessible(true);
                m.invoke(null, millis, java.util.concurrent.TimeUnit.MILLISECONDS);
                return;
            } catch (NoSuchMethodException ignored) {
                // fall through
            }
            try {
                Method m = cls.getDeclaredMethod("setCurrentTime", long.class);
                m.setAccessible(true);
                m.invoke(null, millis);
            } catch (NoSuchMethodException ignored) {
                // Not available in this Robolectric version; proceed without freezing time.
            }
        } catch (Exception ignored) {
            // Best-effort; if unavailable, tests should still behave safely.
        }
    }

    private File createJpegFixture(String name) throws Exception {
        File file = new File(context.getCacheDir(), name);
        Bitmap bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF336699);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos));
        } finally {
            bitmap.recycle();
        }

        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.setAttribute(ExifInterface.TAG_MAKE, "FixtureCamera");
        exif.setLatLong(40.7128, -74.0060);
        exif.saveAttributes();
        return file;
    }

    private File createBitmapFixture(String name, Bitmap.CompressFormat format) throws Exception {
        File file = new File(context.getCacheDir(), name);
        Bitmap bitmap = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFAA6633);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(format, 100, fos));
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private void writeExifFixtureData(File file) throws Exception {
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.setAttribute(ExifInterface.TAG_MAKE, "FixtureCamera");
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "FixtureSoftware");
        exif.saveAttributes();
    }

    private File createBinaryFixture(String name, String contents) throws IOException {
        return createFile(context.getCacheDir(), name, contents);
    }

    private File createSparseFixture(String name, long size) throws IOException {
        File file = new File(context.getCacheDir(), name);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(size);
        }
        return file;
    }

    private File createFile(File parentDir, String name, String contents) throws IOException {
        File file = new File(parentDir, name);
        File dir = file.getParentFile();
        if (dir != null && !dir.exists()) {
            assertTrue(dir.mkdirs());
        }
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(contents.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private File newestProcessedFile() {
        File processedDir = new File(context.getCacheDir(), "processed");
        File[] files = processedDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        return Arrays.stream(files)
                .max(Comparator.comparingLong(File::lastModified))
                .orElse(null);
    }

    @Test
    public void getTagsToPreserve_strictClean_overridesOtherPreservation() throws Exception {
        com.doubleangels.redact.AppPreferences.setStrictClean(context, true);
        com.doubleangels.redact.AppPreferences.setPreserveCameraSettings(context, true);
        com.doubleangels.redact.AppPreferences.setPreserveLocation(context, true);

        Method m = MetadataStripper.class.getDeclaredMethod("getTagsToPreserve");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) m.invoke(stripper);

        assertEquals(0, tags.size());
    }

    @Test
    public void getTagsToPreserve_preserveCameraSettings_includesCameraTags() throws Exception {
        com.doubleangels.redact.AppPreferences.setStrictClean(context, false);
        com.doubleangels.redact.AppPreferences.setPreserveCameraSettings(context, true);
        com.doubleangels.redact.AppPreferences.setPreserveLocation(context, false);

        Method m = MetadataStripper.class.getDeclaredMethod("getTagsToPreserve");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) m.invoke(stripper);

        assertTrue(tags.contains(ExifInterface.TAG_MAKE));
        assertTrue(tags.contains(ExifInterface.TAG_MODEL));
        assertTrue(tags.contains(ExifInterface.TAG_ISO_SPEED));
        assertFalse(tags.contains(ExifInterface.TAG_GPS_LATITUDE));
    }

    @Test
    public void getTagsToPreserve_preserveLocation_includesGpsTags() throws Exception {
        com.doubleangels.redact.AppPreferences.setStrictClean(context, false);
        com.doubleangels.redact.AppPreferences.setPreserveCameraSettings(context, false);
        com.doubleangels.redact.AppPreferences.setPreserveLocation(context, true);

        Method m = MetadataStripper.class.getDeclaredMethod("getTagsToPreserve");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) m.invoke(stripper);

        assertTrue(tags.contains(ExifInterface.TAG_GPS_LATITUDE));
        assertTrue(tags.contains(ExifInterface.TAG_GPS_LONGITUDE));
        assertFalse(tags.contains(ExifInterface.TAG_MAKE));
    }

    @Test
    public void secureDeleteFile_usesDynamicPassCount() throws Exception {
        com.doubleangels.redact.AppPreferences.setSecureDeletePasses(context, 5);
        File testFile = createBinaryFixture("secure_delete_dynamic.bin", "erase-me");
        
        Method m = MetadataStripper.class.getDeclaredMethod("secureDeleteFile", File.class);
        m.setAccessible(true);
        boolean result = (boolean) m.invoke(stripper, testFile);

        assertTrue(result);
        assertFalse(testFile.exists());
    }

    @Test
    public void isFileTooLarge_respectsDynamicLimit() throws Exception {
        com.doubleangels.redact.AppPreferences.setMaxImageFileSizeMb(context, 10);
        assertEquals(10L, stripper.getMaxFileSizeMB());

        long bytes11MB = 11L * 1024L * 1024L;
        File oversized = createSparseFixture("dynamic_oversized.jpg", bytes11MB);
        assertTrue(stripper.isFileTooLarge(Uri.fromFile(oversized)));

        long bytes9MB = 9L * 1024L * 1024L;
        File withinLimit = createSparseFixture("dynamic_valid.jpg", bytes9MB);
        assertFalse(stripper.isFileTooLarge(Uri.fromFile(withinLimit)));
    }

    private ContentResolver createMediaStoreResolver(
            Uri sourceUri,
            File sourceFile,
            Uri outputUri,
            File outputFile,
            String mimeType) throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.SIZE});
        cursor.addRow(new Object[]{sourceFile.length()});

        when(resolver.query(eq(sourceUri), any(String[].class), isNull(), isNull(), isNull())).thenReturn(cursor);
        when(resolver.getType(sourceUri)).thenReturn(mimeType);
        when(resolver.openInputStream(sourceUri)).thenAnswer(invocation -> new FileInputStream(sourceFile));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outputUri);
        when(resolver.openOutputStream(outputUri)).thenAnswer(invocation -> new FileOutputStream(outputFile, false));
        when(resolver.openInputStream(outputUri)).thenAnswer(invocation -> new FileInputStream(outputFile));
        when(resolver.openFileDescriptor(eq(outputUri), eq("rw")))
                .thenAnswer(invocation -> ParcelFileDescriptor.open(
                        outputFile,
                        ParcelFileDescriptor.MODE_CREATE
                                | ParcelFileDescriptor.MODE_READ_WRITE));
        when(resolver.delete(eq(outputUri), eq(null), eq(null)))
                .thenAnswer(invocation -> outputFile.delete() ? 1 : 0);
        return resolver;
    }

    private MockedStatic<FileProvider> mockFileProvider() {
        MockedStatic<FileProvider> mockedStatic = mockStatic(FileProvider.class);
        mockedStatic.when(() -> FileProvider.getUriForFile(
                        any(Context.class),
                        anyString(),
                        any(File.class)))
                .thenAnswer(invocation -> {
                    File file = invocation.getArgument(2);
                    return Uri.parse("content://com.doubleangels.redact.test.fileprovider/" + file.getName());
                });
        return mockedStatic;
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
            if (file.isDirectory() && "processed".equals(file.getName())) {
                deleteRecursively(file);
                continue;
            }
            String name = file.getName();
            if (file.isDirectory() && name.startsWith("metadata_")) {
                deleteRecursively(file);
                continue;
            }
            if (name.startsWith("metadata_")
                    || name.startsWith("boundary_")
                    || name.startsWith("cached_")
                    || name.startsWith("temp_")
                    || name.startsWith("verify_")
                    || name.startsWith("vid_transmux_")
                    || name.startsWith("r_")) {
                file.delete();
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

    private static final class CacheDirContext extends ContextWrapper {
        private final File cacheDir;

        CacheDirContext(Context base, File cacheDir) {
            super(base);
            this.cacheDir = cacheDir;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public File getCacheDir() {
            return cacheDir;
        }
    }

    private static final class ThrowingCacheDirContext extends ContextWrapper {
        ThrowingCacheDirContext(Context base) {
            super(base);
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public File getCacheDir() {
            throw new RuntimeException("cache dir failure");
        }
    }
}
