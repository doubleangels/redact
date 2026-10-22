package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.media3.common.ParserException;
import androidx.media3.common.PlaybackException;
import androidx.media3.transformer.ExportException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Inputs no decoder can read are classified as unsupported rather than reported as crashes. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // legacy graphics fakes a bitmap for any input
public class UnsupportedMediaTest {

    private File file(String suffix, byte[] content) throws IOException {
        Application app = RuntimeEnvironment.getApplication();
        File f = File.createTempFile("unsupported_", suffix, app.getCacheDir());
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
        return f;
    }

    private File svg() throws IOException {
        return file(".svg", "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"4\" height=\"4\"/>"
                .getBytes(StandardCharsets.UTF_8));
    }

    // ---- cause chain ----------------------------------------------------------------------------

    @Test
    public void isCauseOf_findsItAnywhereInTheChain() {
        UnsupportedMediaException unsupported = new UnsupportedMediaException("x", null);
        assertTrue(UnsupportedMediaException.isCauseOf(unsupported));
        assertTrue(UnsupportedMediaException.isCauseOf(
                new IOException("Failed to process video safely", new RuntimeException(unsupported))));
        assertFalse(UnsupportedMediaException.isCauseOf(new IOException("disk full")));
        assertFalse(UnsupportedMediaException.isCauseOf(null));
    }

    @Test
    public void isCauseOf_terminatesOnACyclicChain() {
        IOException a = new IOException("a");
        IOException b = new IOException("b", a);
        a.initCause(b);
        assertFalse(UnsupportedMediaException.isCauseOf(a));
    }

    // ---- video: Media3 export errors ------------------------------------------------------------

    @Test
    public void unrecognizedContainer_isUnsupportedInput() {
        // Media3 maps parser errors to ERROR_CODE_UNSPECIFIED; the player's code is in the chain.
        PlaybackException player = new PlaybackException(
                "Unrecognized input", ParserException.createForMalformedContainer("x", null),
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED);
        assertTrue(VideoMedia3Converter.isUnsupportedInput(ExportException.createForAssetLoader(
                player, ExportException.ERROR_CODE_UNSPECIFIED)));
    }

    @Test
    public void noDecoderForTheCodec_isUnsupportedInput() {
        assertTrue(VideoMedia3Converter.isUnsupportedInput(ExportException.createForAssetLoader(
                new IllegalStateException(), ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)));
    }

    @Test
    public void processingFaults_areNotUnsupportedInput() {
        // Decoder start failures (REDACT-21), encoder limits and I/O errors stay reportable.
        int[] codes = {
                ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                ExportException.ERROR_CODE_IO_UNSPECIFIED,
                ExportException.ERROR_CODE_MUXING_FAILED,
        };
        for (int code : codes) {
            assertFalse(String.valueOf(code), VideoMedia3Converter.isUnsupportedInput(
                    ExportException.createForAssetLoader(new IllegalStateException(), code)));
        }
    }

    @Test
    public void encoderRejectingTheFrameSize_isResolutionFailure() {
        // REDACT-24: Media3's encoder size search lets VideoCapabilities throw "unsupported width".
        IllegalArgumentException capabilities = new IllegalArgumentException("unsupported width");
        ExportException export = ExportException.createForAssetLoader(
                new RuntimeException(new IllegalStateException(capabilities)),
                ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED);
        assertTrue(VideoMedia3Converter.isEncoderResolutionFailure(export));
        assertFalse(VideoMedia3Converter.isUnsupportedInput(export));
        assertTrue(VideoMedia3Converter.isEncoderResolutionFailure(ExportException.createForAssetLoader(
                new IllegalArgumentException("unsupported height"),
                ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)));
    }

    @Test
    public void otherFaults_areNotResolutionFailures() {
        assertFalse(VideoMedia3Converter.isEncoderResolutionFailure(ExportException.createForAssetLoader(
                new IllegalArgumentException("bad argument"),
                ExportException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)));
        assertFalse(VideoMedia3Converter.isEncoderResolutionFailure(ExportException.createForAssetLoader(
                new IllegalStateException(), ExportException.ERROR_CODE_ENCODER_INIT_FAILED)));
    }

    @Test
    public void muxerWatchdogTimeout_isAStall() {
        // REDACT-25: no sample reached the muxer for 10 seconds, so Media3 aborted the export.
        ExportException stall = ExportException.createForAssetLoader(
                new IllegalStateException("Abort: no output sample written in the last 10000 milliseconds."),
                ExportException.ERROR_CODE_MUXING_TIMEOUT);
        assertTrue(VideoMedia3Converter.isMuxerStall(stall));
        assertFalse(VideoMedia3Converter.isUnsupportedInput(stall));
        assertFalse(VideoMedia3Converter.isMuxerStall(ExportException.createForAssetLoader(
                new IllegalStateException(), ExportException.ERROR_CODE_MUXING_FAILED)));
    }

    @Test
    public void stallTimeout_isLongerOnPhonesWithoutAPerformanceClass() {
        long rated = VideoMedia3Converter.maxSampleGapMs(android.os.Build.VERSION_CODES.TIRAMISU);
        assertEquals(androidx.media3.transformer.Transformer.DEFAULT_MAX_DELAY_BETWEEN_MUXER_SAMPLES_MS, rated);
        assertEquals(Math.max(rated, 20_000L), VideoMedia3Converter.maxSampleGapMs(0));
    }

    @Test
    public void fallbackShortSides_neverUpscale() {
        assertArrayEquals(new int[] {1080, 720, 480}, VideoMedia3Converter.fallbackShortSides(2160));
        assertArrayEquals(new int[] {720, 480}, VideoMedia3Converter.fallbackShortSides(1080));
        assertArrayEquals(new int[] {}, VideoMedia3Converter.fallbackShortSides(480));
        assertArrayEquals(new int[] {1080, 720, 480}, VideoMedia3Converter.fallbackShortSides(0));
    }

    // ---- images -----------------------------------------------------------------------------------

    @Test
    public void decodeBitmapFromUri_svg_throwsUnsupported() throws IOException {
        try {
            FormatConverter.decodeBitmapFromUri(RuntimeEnvironment.getApplication(), Uri.fromFile(svg()));
            fail("expected UnsupportedMediaException");
        } catch (UnsupportedMediaException expected) {
            // ok
        }
    }

    @Test
    public void decodeBitmapFromUri_unreadableSource_isAnIoErrorNotUnsupported() {
        try {
            FormatConverter.decodeBitmapFromUri(
                    RuntimeEnvironment.getApplication(), Uri.fromFile(new File("/nonexistent/x.jpg")));
            fail("expected IOException");
        } catch (UnsupportedMediaException e) {
            fail("a missing file is not an unsupported format");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void convertImage_nonImageFile_throwsUnsupported() throws IOException {
        File notAnImage = file(".jpg", "%PDF-1.7 not really an image".getBytes(StandardCharsets.US_ASCII));
        try {
            FormatConverter.convertImageToPictures(RuntimeEnvironment.getApplication(),
                    Uri.fromFile(notAnImage), Bitmap.CompressFormat.JPEG, "x");
            fail("expected UnsupportedMediaException");
        } catch (UnsupportedMediaException expected) {
            assertEquals(UnsupportedMediaException.class, expected.getClass());
        }
    }
}
