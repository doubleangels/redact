package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Size;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class FormatConverterImageFormatTest {

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
    public void resolveImageFormat_pngFromExtension() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".png", null);
        assertEquals(".png", spec.extension);
        assertEquals("image/png", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.PNG, spec.compressFormat);
        assertTrue(spec.bitmapFallbackSupported);
    }

    @Test
    public void resolveImageFormat_jpegFromExtension() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".jpg", null);
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.JPEG, spec.compressFormat);
    }

    @Test
    public void resolveImageFormat_prefersExtensionBeforeMime() throws IOException {
        FormatConverter.ImageFormatSpec spec =
                FormatConverter.resolveImageFormat(".jpeg", "image/png");
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.JPEG, spec.compressFormat);
    }

    @Test
    public void resolveImageFormat_usesMimeWhenExtensionUnknown() throws IOException {
        FormatConverter.ImageFormatSpec spec =
                FormatConverter.resolveImageFormat(".bin", "image/webp");
        assertEquals(".webp", spec.extension);
        assertEquals("image/webp", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.WEBP, spec.compressFormat);
    }

    @Test
    public void resolveImageFormat_webpFromExtension() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(".webp", null);
        assertEquals(".webp", spec.extension);
        assertEquals("image/webp", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.WEBP, spec.compressFormat);
    }

    @Test
    public void resolveImageFormat_defaultsToJpeg() throws IOException {
        FormatConverter.ImageFormatSpec spec = FormatConverter.resolveImageFormat(null, null);
        assertEquals(".jpg", spec.extension);
        assertEquals("image/jpeg", spec.mimeType);
        assertEquals(Bitmap.CompressFormat.JPEG, spec.compressFormat);
    }

    @Test
    public void resolveImageFormat_emptyInputsAndMimeAliases_coverFallbackBranches() throws IOException {
        FormatConverter.ImageFormatSpec emptySpec = FormatConverter.resolveImageFormat("", "");
        assertEquals(".jpg", emptySpec.extension);
        assertEquals("image/jpeg", emptySpec.mimeType);

        FormatConverter.ImageFormatSpec jpgAlias = FormatConverter.resolveImageFormat(".bin", "image/jpg");
        assertEquals(".jpg", jpgAlias.extension);
        assertEquals("image/jpeg", jpgAlias.mimeType);
    }

    @Test
    @Config(sdk = 33, manifest = Config.NONE)
    public void resolveImageFormat_heicRejectedBelowApi34() {
        try {
            FormatConverter.resolveImageFormat(".heif", "image/heif");
            fail("Expected IOException for HEIC below API 34");
        } catch (IOException e) {
            assertEquals(FormatConverter.HEIC_REQUIRES_API_34, e.getMessage());
        }
    }

    @Test
    @Config(sdk = 34, manifest = Config.NONE)
    public void resolveImageFormat_heicAllowedOnApi34() throws IOException {
        FormatConverter.ImageFormatSpec spec =
                FormatConverter.resolveImageFormat(".heic", "image/heic");
        assertEquals(".heic", spec.extension);
        assertEquals("image/heic", spec.mimeType);
        assertNotNull(spec.compressFormat);
        if (FormatConverter.isHeicOutputSupported()) {
            assertTrue(spec.bitmapFallbackSupported);
        }
    }

    @Test
    public void convertImageToPictures_rejectsVideoMimeTypes() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/source-video");
        when(resolver.getType(sourceUri)).thenReturn("video/mp4");

        try {
            FormatConverter.convertImageToPictures(
                    new ResolverContext(appContext, resolver),
                    sourceUri,
                    Bitmap.CompressFormat.JPEG,
                    "movie.mp4");
            fail("Expected video MIME to be rejected");
        } catch (IOException e) {
            assertEquals("video_not_supported", e.getMessage());
        }
    }

    @Test
    public void convertImageToPictures_throwsWhenSourceCannotOpen() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/source-missing");
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri)).thenReturn(null);

        try {
            FormatConverter.convertImageToPictures(
                    new ResolverContext(appContext, resolver),
                    sourceUri,
                    Bitmap.CompressFormat.JPEG,
                    "source.jpg");
            fail("Expected missing stream to throw");
        } catch (IOException e) {
            assertEquals("Cannot open source", e.getMessage());
        }
    }

    @Test
    public void convertImageToPictures_throwsWhenSecondDecodeStreamNull() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/source-second-null");
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}), null);

        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 5000;
                            options.outHeight = 5000;
                        }
                        return null;
                    });

            try {
                FormatConverter.convertImageToPictures(
                        new ResolverContext(appContext, resolver),
                        sourceUri,
                        Bitmap.CompressFormat.JPEG,
                        "source.jpg");
                fail("Expected null stream on second decode to throw");
            } catch (IOException e) {
                assertEquals("Cannot open source", e.getMessage());
            }
        }
    }

    @Test
    @Config(sdk = 34, manifest = Config.NONE)
    public void convertImageToPictures_usesHeicCompressBranchWhenSupported() throws Exception {
        if (!FormatConverter.isHeicOutputSupported()) {
            return;
        }

        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/source-heic");
        Uri outUri = Uri.parse("content://tests/out-heic");

        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        new ByteArrayInputStream(new byte[]{1, 2, 3}));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        File exifDestFile = new File(appContext.getCacheDir(), "fc_heic_exif_dest.jpg");
        when(resolver.openOutputStream(outUri)).thenReturn(new ByteArrayOutputStream());
        when(resolver.openFileDescriptor(outUri, "rw"))
                .thenAnswer(invocation -> ParcelFileDescriptor.open(
                        exifDestFile,
                        ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE));

        Bitmap.CompressFormat heic = Bitmap.CompressFormat.valueOf("HEIC");
        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.compress(eq(heic), anyInt(), any(OutputStream.class))).thenReturn(true);

        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 100;
                            options.outHeight = 100;
                            return null;
                        }
                        return bitmap;
                    });

            assertEquals(
                    outUri,
                    FormatConverter.convertImageToPictures(
                            new ResolverContext(appContext, resolver),
                            sourceUri,
                            heic,
                            "heic.jpg"));
        }

        verify(bitmap).compress(eq(heic), anyInt(), any(OutputStream.class));
    }

    @Test
    @Config(sdk = 31, manifest = Config.NONE)
    public void convertImageToPictures_throwsWhenDecodingFails() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/decode-fail");
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1, 2, 3}));

        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class);
             MockedStatic<ImageDecoder> mockedImageDecoder = mockStatic(ImageDecoder.class, CALLS_REAL_METHODS)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 0;
                            options.outHeight = 0;
                        }
                        return null;
                    });
            mockedImageDecoder
                    .when(() -> ImageDecoder.createSource(resolver, sourceUri))
                    .thenReturn(mock(ImageDecoder.Source.class));
            mockedImageDecoder
                    .when(() -> ImageDecoder.decodeBitmap(any(ImageDecoder.Source.class), any(ImageDecoder.OnHeaderDecodedListener.class)))
                    .thenReturn(null);

            try {
                FormatConverter.convertImageToPictures(
                        new ResolverContext(appContext, resolver),
                        sourceUri,
                        Bitmap.CompressFormat.JPEG,
                        "broken.jpg");
                fail("Expected decode failure");
            } catch (IOException e) {
                assertEquals("Decode failed", e.getMessage());
            }
        }
    }

    @Test
    public void convertImageToPictures_usesImageDecoderFallbackWhenBoundsDecodeFails() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/image-decoder");
        Uri outUri = Uri.parse("content://tests/output-decoder");
        File outputFile = new File(appContext.getCacheDir(), "fc_decoder_output.jpg");

        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{9, 8, 7}));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri))
                .thenAnswer(invocation -> new FileOutputStream(outputFile));
        when(resolver.openFileDescriptor(outUri, "rw")).thenReturn(null);

        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.compress(eq(Bitmap.CompressFormat.JPEG), any(Integer.class), any(OutputStream.class)))
                .thenReturn(true);
        ImageDecoder.Source source = mock(ImageDecoder.Source.class);
        ImageDecoder.ImageInfo info = mock(ImageDecoder.ImageInfo.class);
        ImageDecoder decoder = mock(ImageDecoder.class);
        when(info.getSize()).thenReturn(new Size(8000, 4000));

        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class);
             MockedStatic<ImageDecoder> mockedImageDecoder = mockStatic(ImageDecoder.class, CALLS_REAL_METHODS)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 0;
                            options.outHeight = 0;
                        }
                        return null;
                    });
            mockedImageDecoder.when(() -> ImageDecoder.createSource(resolver, sourceUri)).thenReturn(source);
            mockedImageDecoder
                    .when(() -> ImageDecoder.decodeBitmap(eq(source), any(ImageDecoder.OnHeaderDecodedListener.class)))
                    .thenAnswer(invocation -> {
                        ImageDecoder.OnHeaderDecodedListener listener = invocation.getArgument(1);
                        listener.onHeaderDecoded(decoder, info, source);
                        return bitmap;
                    });

            Uri result = FormatConverter.convertImageToPictures(
                    new ResolverContext(appContext, resolver),
                    sourceUri,
                    Bitmap.CompressFormat.JPEG,
                    "needs-decoder.jpg");

            assertEquals(outUri, result);
            verify(decoder).setTargetSize(4096, 2048);
            verify(bitmap).recycle();
        }
    }

    @Test
    public void convertImageToPictures_throwsWhenMediaStoreInsertFails() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/insert-fail");
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1}));

        Bitmap bitmap = mock(Bitmap.class);
        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 10;
                            options.outHeight = 10;
                            return null;
                        }
                        return bitmap;
                    });
            when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                    .thenReturn(null);

            try {
                FormatConverter.convertImageToPictures(
                        new ResolverContext(appContext, resolver),
                        sourceUri,
                        Bitmap.CompressFormat.JPEG,
                        "insert.jpg");
                fail("Expected insert failure");
            } catch (IOException e) {
                assertEquals("MediaStore insert failed", e.getMessage());
            }

            verify(bitmap).recycle();
        }
    }

    @Test
    public void convertImageToPictures_deletesInsertedUriWhenOutputStreamMissing() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/no-output");
        Uri outUri = Uri.parse("content://tests/no-output-result");
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1}));

        Bitmap bitmap = mock(Bitmap.class);
        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 10;
                            options.outHeight = 10;
                            return null;
                        }
                        return bitmap;
                    });
            when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                    .thenReturn(outUri);
            when(resolver.openOutputStream(outUri)).thenReturn(null);

            try {
                FormatConverter.convertImageToPictures(
                        new ResolverContext(appContext, resolver),
                        sourceUri,
                        Bitmap.CompressFormat.JPEG,
                        "missing-output.jpg");
                fail("Expected missing output stream");
            } catch (IOException e) {
                assertEquals("Cannot open output stream", e.getMessage());
            }

            verify(resolver).delete(outUri, null, null);
            verify(bitmap).recycle();
        }
    }

    @Test
    @Config(sdk = 31, manifest = Config.NONE)
    public void convertImageToPictures_usesWebpLossyOnAndroidRPlus() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/webp");
        Uri outUri = Uri.parse("content://tests/webp-result");
        when(resolver.getType(sourceUri)).thenReturn("image/webp");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1}));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri)).thenReturn(new ByteArrayOutputStream());
        when(resolver.openFileDescriptor(outUri, "rw")).thenReturn(null);

        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.compress(eq(Bitmap.CompressFormat.WEBP_LOSSY), any(Integer.class), any(OutputStream.class)))
                .thenReturn(true);

        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 10;
                            options.outHeight = 10;
                            return null;
                        }
                        return bitmap;
                    });

            Uri result = FormatConverter.convertImageToPictures(
                    new ResolverContext(appContext, resolver),
                    sourceUri,
                    Bitmap.CompressFormat.WEBP,
                    "webp-output.webp");

            assertEquals(outUri, result);
            verify(bitmap).compress(eq(Bitmap.CompressFormat.WEBP_LOSSY), any(Integer.class), any(OutputStream.class));
            verify(bitmap).recycle();
        }
    }

    @Test
    @Config(sdk = 34, manifest = Config.NONE)
    public void convertImageToPictures_usesHeicCompressOnApi34() throws Exception {
        if (!FormatConverter.isHeicOutputSupported()) {
            return;
        }

        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/heic");
        Uri outUri = Uri.parse("content://tests/heic-result");
        when(resolver.getType(sourceUri)).thenReturn("image/heic");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1}));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri)).thenReturn(new ByteArrayOutputStream());
        when(resolver.openFileDescriptor(outUri, "rw")).thenReturn(null);

        Bitmap bitmap = mock(Bitmap.class);
        Bitmap.CompressFormat heic = Bitmap.CompressFormat.valueOf("HEIC");
        when(bitmap.compress(eq(heic), any(Integer.class), any(OutputStream.class))).thenReturn(true);

        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 10;
                            options.outHeight = 10;
                            return null;
                        }
                        return bitmap;
                    });

            Uri result = FormatConverter.convertImageToPictures(
                    new ResolverContext(appContext, resolver),
                    sourceUri,
                    heic,
                    "camera-roll.heic");

            assertEquals(outUri, result);
            verify(bitmap).compress(eq(heic), any(Integer.class), any(OutputStream.class));
            verify(bitmap).recycle();
        }
    }

    @Test
    public void convertImageToPictures_deletesInsertedUriWhenCompressionFails() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/compress-fail");
        Uri outUri = Uri.parse("content://tests/compress-fail-result");
        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{1}));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri)).thenReturn(new ByteArrayOutputStream());

        Bitmap bitmap = mock(Bitmap.class);
        when(bitmap.compress(eq(Bitmap.CompressFormat.JPEG), any(Integer.class), any(OutputStream.class)))
                .thenReturn(false);

        try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
            mockedBitmapFactory
                    .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                    .thenAnswer(invocation -> {
                        BitmapFactory.Options options = invocation.getArgument(2);
                        if (options.inJustDecodeBounds) {
                            options.outWidth = 10;
                            options.outHeight = 10;
                            return null;
                        }
                        return bitmap;
                    });

            try {
                FormatConverter.convertImageToPictures(
                        new ResolverContext(appContext, resolver),
                        sourceUri,
                        Bitmap.CompressFormat.JPEG,
                        "compress-fail.jpg");
                fail("Expected compression failure");
            } catch (IOException e) {
                assertEquals("Compress failed", e.getMessage());
            }

            verify(resolver).delete(outUri, null, null);
            verify(bitmap).recycle();
        }
    }

    @Test
    @Config(sdk = 33, manifest = Config.NONE)
    public void convertImageToPictures_heicRequestedBelowApi34_fallsBackToJpegAndIgnoresExifErrors()
            throws Exception {
        Bitmap.CompressFormat heicFormat;
        try {
            heicFormat = Bitmap.CompressFormat.valueOf("HEIC");
        } catch (IllegalArgumentException e) {
            return;
        }

        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.fromFile(createJpegFixture("fc_source_heic_gate.jpg"));
        Uri outUri = Uri.parse("content://tests/heic-fallback");
        File outputFile = new File(appContext.getCacheDir(), "fc_heic_fallback.jpg");
        ArgumentCaptor<ContentValues> valuesCaptor = ArgumentCaptor.forClass(ContentValues.class);

        when(resolver.getType(sourceUri)).thenReturn("image/heic");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new FileInputStream(new File(sourceUri.getPath())));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), valuesCaptor.capture()))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri))
                .thenAnswer(invocation -> new FileOutputStream(outputFile));
        when(resolver.openFileDescriptor(outUri, "rw")).thenThrow(new RuntimeException("pfd boom"));

        Uri result = FormatConverter.convertImageToPictures(
                new ResolverContext(appContext, resolver),
                sourceUri,
                heicFormat,
                "bad name?.heic");

        assertEquals(outUri, result);
        assertEquals("bad_name_.jpg", valuesCaptor.getValue().getAsString(MediaStore.Images.Media.DISPLAY_NAME));
        assertEquals("image/jpeg", valuesCaptor.getValue().getAsString(MediaStore.Images.Media.MIME_TYPE));
        assertTrue(outputFile.exists());
        assertTrue(outputFile.length() > 0L);
    }

    @Test
    public void convertImageToPictures_successfullyWritesAndCopiesExif() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        File sourceFile = createJpegFixture("fc_source_success.jpg");
        Uri sourceUri = Uri.fromFile(sourceFile);
        Uri outUri = Uri.parse("content://tests/success");
        File outputFile = new File(appContext.getCacheDir(), "fc_output_success.jpg");
        ArgumentCaptor<ContentValues> valuesCaptor = ArgumentCaptor.forClass(ContentValues.class);

        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new FileInputStream(sourceFile));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), valuesCaptor.capture()))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri))
                .thenAnswer(invocation -> new FileOutputStream(outputFile));
        when(resolver.openFileDescriptor(outUri, "rw"))
                .thenAnswer(invocation -> ParcelFileDescriptor.open(
                        outputFile,
                        ParcelFileDescriptor.MODE_READ_WRITE
                                | ParcelFileDescriptor.MODE_CREATE));

        Uri result = FormatConverter.convertImageToPictures(
                new ResolverContext(appContext, resolver),
                sourceUri,
                Bitmap.CompressFormat.JPEG,
                "Vacation 2026.jpg");

        assertEquals(outUri, result);
        assertEquals(
                "Vacation_2026.jpg",
                valuesCaptor.getValue().getAsString(MediaStore.Images.Media.DISPLAY_NAME));
        assertEquals(
                "image/jpeg",
                valuesCaptor.getValue().getAsString(MediaStore.Images.Media.MIME_TYPE));

        assertTrue(outputFile.exists());
        assertTrue(outputFile.length() > 0L);
    }

    @Test
    public void convertImageToPictures_invokesCopyExifWithAccessibleDescriptors() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        File sourceFile = createJpegFixture("fc_convert_copy_source.jpg");
        File destFile = createBlankJpegFixture("fc_convert_copy_dest.jpg");
        Uri sourceUri = Uri.fromFile(sourceFile);
        Uri outUri = Uri.parse("content://tests/convert-copy-out");

        when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new FileInputStream(sourceFile));
        when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                .thenReturn(outUri);
        when(resolver.openOutputStream(outUri)).thenAnswer(invocation -> new FileOutputStream(destFile));
        when(resolver.openFileDescriptor(outUri, "rw"))
                .thenAnswer(invocation -> ParcelFileDescriptor.open(
                        destFile,
                        ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE));

        Uri result = FormatConverter.convertImageToPictures(
                new ResolverContext(appContext, resolver),
                sourceUri,
                Bitmap.CompressFormat.JPEG,
                "holiday.jpg");
        assertEquals(outUri, result);
        assertTrue(destFile.exists());
    }

    @Test
    public void copyExifData_copiesNonLocationExifWhenFilesAreAccessible() throws Exception {
        File sourceFile = createJpegFixture("fc_copy_exif_source.jpg");
        File destFile = createBlankJpegFixture("fc_copy_exif_dest.jpg");
        invokeCopyExifData(
                appContext, Uri.fromFile(sourceFile), Uri.fromFile(destFile));

        ExifInterface copied = new ExifInterface(destFile.getAbsolutePath());
        assertEquals("FixtureCamera", copied.getAttribute(ExifInterface.TAG_MAKE));
    }

    @Test
    public void openExifForWrite_usesParcelFileDescriptorWhenDestIsContentUri() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        File destFile = createBlankJpegFixture("fc_open_exif_pfd_dest.jpg");
        Uri destUri = Uri.parse("content://tests/open-exif-pfd");
        when(resolver.openFileDescriptor(destUri, "rw"))
                .thenAnswer(invocation -> ParcelFileDescriptor.open(
                        destFile,
                        ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE));

        Method method = FormatConverter.class.getDeclaredMethod(
                "openExifForWrite", Context.class, Uri.class);
        method.setAccessible(true);
        method.invoke(null, new ResolverContext(appContext, resolver), destUri);
    }

    @Test
    public void copyExifData_usesParcelFileDescriptorDestination() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        File sourceFile = createJpegFixture("fc_copy_exif_pfd_source.jpg");
        File destFile = createBlankJpegFixture("fc_copy_exif_pfd_dest.jpg");
        Uri sourceUri = Uri.fromFile(sourceFile);
        Uri destUri = Uri.parse("content://tests/copy-exif-pfd");

        when(resolver.openFileDescriptor(destUri, "rw"))
                .thenAnswer(invocation -> ParcelFileDescriptor.open(
                        destFile,
                        ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE));

        invokeCopyExifData(new ResolverContext(appContext, resolver), sourceUri, destUri);
    }

    @Test
    public void copyExifData_ignoresUnreadableDestinationPath() throws Exception {
        invokeCopyExifData(
                appContext,
                Uri.fromFile(createJpegFixture("fc_copy_exif_bad_source.jpg")),
                Uri.fromFile(new File("/this/path/does/not/exist/fc_copy_exif_bad_dest.jpg")));
    }

    @Test
    public void copyExifData_ignoresSaveFailuresOnReadOnlyDestination() throws Exception {
        File sourceFile = createJpegFixture("fc_copy_exif_readonly_source.jpg");
        File destFile = createBlankJpegFixture("fc_copy_exif_readonly_dest.jpg");
        assertTrue(destFile.setReadOnly());
        try {
            invokeCopyExifData(appContext, Uri.fromFile(sourceFile), Uri.fromFile(destFile));
        } finally {
            destFile.setWritable(true);
        }
    }

    @Test
    @Config(sdk = 34, manifest = Config.NONE)
    public void resolveImageFormat_heicMimeAndExtension_hitSpecBranches() throws Exception {
        FormatConverter.ImageFormatSpec byExt =
                FormatConverter.resolveImageFormat(".heic", null);
        assertEquals(".heic", byExt.extension);
        FormatConverter.ImageFormatSpec byMime =
                FormatConverter.resolveImageFormat(null, "image/heif");
        assertEquals("image/heic", byMime.mimeType);
    }

    @Test
    @Config(sdk = 33, manifest = Config.NONE)
    public void convertImageToPictures_heicStandInFallsBackBelowApi34() throws Exception {
        Bitmap.CompressFormat standIn = Bitmap.CompressFormat.JPEG;
        FormatConverter.testTreatFormatAsHeic = standIn;
        try {
            File source = createJpegFixture("fc_heic_fallback_source.jpg");
            ContentResolver resolver = mock(ContentResolver.class);
            Uri sourceUri = Uri.fromFile(source);
            Uri outUri = Uri.parse("content://tests/heic-fallback-out");
            when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
            when(resolver.openInputStream(sourceUri))
                    .thenReturn(new FileInputStream(source));
            when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                    .thenReturn(outUri);
            when(resolver.openOutputStream(outUri)).thenReturn(new ByteArrayOutputStream());
            when(resolver.openFileDescriptor(outUri, "rw")).thenReturn(null);

            FormatConverter.convertImageToPictures(
                    new ResolverContext(appContext, resolver),
                    sourceUri,
                    standIn,
                    "photo.heic");
        } finally {
            FormatConverter.testTreatFormatAsHeic = null;
        }
    }

    @Test
    @Config(sdk = 34, manifest = Config.NONE)
    public void convertImageToPictures_heicStandInUsesHeicCompressBranch() throws Exception {
        Bitmap.CompressFormat standIn = Bitmap.CompressFormat.JPEG;
        FormatConverter.testTreatFormatAsHeic = standIn;
        try {
            ContentResolver resolver = mock(ContentResolver.class);
            File source = createJpegFixture("fc_heic_compress_source.jpg");
            Uri sourceUri = Uri.fromFile(source);
            Uri outUri = Uri.parse("content://tests/heic-compress-out");
            File destFile = new File(appContext.getCacheDir(), "fc_heic_compress_dest.jpg");

            when(resolver.getType(sourceUri)).thenReturn("image/jpeg");
            when(resolver.openInputStream(sourceUri))
                    .thenReturn(new FileInputStream(source));
            when(resolver.insert(eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(ContentValues.class)))
                    .thenReturn(outUri);
            when(resolver.openOutputStream(outUri)).thenReturn(new FileOutputStream(destFile));
            when(resolver.openFileDescriptor(outUri, "rw")).thenReturn(null);

            Bitmap bitmap = mock(Bitmap.class);
            when(bitmap.compress(eq(standIn), anyInt(), any(OutputStream.class))).thenReturn(true);
            try (MockedStatic<BitmapFactory> mockedBitmapFactory = mockStatic(BitmapFactory.class)) {
                mockedBitmapFactory
                        .when(() -> BitmapFactory.decodeStream(any(InputStream.class), eq(null), any()))
                        .thenAnswer(invocation -> {
                            BitmapFactory.Options options = invocation.getArgument(2);
                            if (options.inJustDecodeBounds) {
                                options.outWidth = 8;
                                options.outHeight = 8;
                                return null;
                            }
                            return bitmap;
                        });
                FormatConverter.convertImageToPictures(
                        new ResolverContext(appContext, resolver),
                        sourceUri,
                        standIn,
                        "photo.heic");
            }
            verify(bitmap).compress(eq(standIn), anyInt(), any(OutputStream.class));
        } finally {
            FormatConverter.testTreatFormatAsHeic = null;
        }
    }

    @Test
    public void copyExifData_ignoresMissingStreamsAndExceptions() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        Uri sourceUri = Uri.parse("content://tests/copy-exif-source");
        Uri destUri = Uri.parse("content://tests/copy-exif-dest");
        Context context = new ResolverContext(appContext, resolver);

        when(resolver.openInputStream(sourceUri)).thenReturn(null);
        invokeCopyExifData(context, sourceUri, destUri);

        when(resolver.openInputStream(sourceUri)).thenThrow(new RuntimeException("boom"));
        invokeCopyExifData(context, sourceUri, destUri);
    }

    @Test
    public void copyExifData_ignoresNullDestinationDescriptor() throws Exception {
        ContentResolver resolver = mock(ContentResolver.class);
        File sourceFile = createJpegFixture("fc_copy_exif_null_pfd.jpg");
        Uri sourceUri = Uri.fromFile(sourceFile);
        Uri destUri = Uri.parse("content://tests/copy-exif-null-pfd");

        when(resolver.openInputStream(sourceUri))
                .thenAnswer(invocation -> new FileInputStream(sourceFile));
        when(resolver.openFileDescriptor(destUri, "rw")).thenReturn(null);

        invokeCopyExifData(new ResolverContext(appContext, resolver), sourceUri, destUri);
    }

    @Test
    public void convertVideoToMovies_delegatesAndWrapsInterruptedException() throws Exception {
        Uri sourceUri = Uri.parse("content://tests/source-video");
        Uri expectedUri = Uri.parse("content://tests/converted-video");

        try (MockedStatic<VideoMedia3Converter> mockedStatic =
                mockStatic(VideoMedia3Converter.class, CALLS_REAL_METHODS)) {
            mockedStatic.when(() -> VideoMedia3Converter.transcodeToGallery(
                            eq(appContext), eq(sourceUri), eq("clip.mp4"), eq(2), eq(null)))
                    .thenReturn(expectedUri);

            assertEquals(
                    expectedUri,
                    FormatConverter.convertVideoToMovies(appContext, sourceUri, "clip.mp4", 2));

            mockedStatic.when(() -> VideoMedia3Converter.transcodeToGallery(
                            eq(appContext), eq(sourceUri), eq("clip.mp4"), eq(2), eq(null)))
                    .thenThrow(new InterruptedException("stop"));

            try {
                FormatConverter.convertVideoToMovies(appContext, sourceUri, "clip.mp4", 2);
                fail("Expected interrupted conversion to be wrapped");
            } catch (IOException e) {
                assertEquals("Video conversion interrupted", e.getMessage());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    private File createJpegFixture(String name) throws Exception {
        File file = createBlankJpegFixture(name);
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(
                ExifInterface.TAG_ORIENTATION,
                String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
        exif.setAttribute(ExifInterface.TAG_MAKE, "FixtureCamera");
        exif.setLatLong(40.7128, -74.0060);
        exif.saveAttributes();
        return file;
    }

    private File createBlankJpegFixture(String name) throws Exception {
        File file = new File(appContext.getCacheDir(), name);
        Bitmap bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF336699);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos));
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private void invokeCopyExifData(Context context, Uri sourceUri, Uri destUri) throws Exception {
        Method method =
                FormatConverter.class.getDeclaredMethod(
                        "copyExifData", Context.class, Uri.class, Uri.class);
        method.setAccessible(true);
        method.invoke(null, context, sourceUri, destUri);
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
            if (name.startsWith("fc_")) {
                file.delete();
            }
        }
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
}
