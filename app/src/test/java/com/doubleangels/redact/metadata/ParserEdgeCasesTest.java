package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** What the readers do with files they can't open, can't follow, or that end too soon. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ParserEdgeCasesTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final Application app = RuntimeEnvironment.getApplication();
    private final File missing = new File("/no/such/file.bin");

    private File file(byte[] bytes) throws Exception {
        File f = temp.newFile();
        Files.write(f.toPath(), bytes);
        return f;
    }

    @Test
    public void scanners_neverCallAFileTheyCannotOpenClean() {
        assertFalse(ImageContainerMetadata.scan(missing).recognized);
        assertFalse(ImageContainerMetadata.scan(app, Uri.fromFile(missing)).recognized);
        assertFalse(VideoContainerMetadata.scan(app, Uri.fromFile(missing)).recognized);
        assertNull(MotionPhoto.find(missing));
        assertFalse(GifContainerMetadata.isGif(missing));
        assertFalse(HeifContainerMetadata.isHeif(missing));
    }

    @Test
    public void findingsDescribeThemselves() {
        assertEquals("XMP=a", new ImageContainerMetadata.Finding("XMP", "a", 1, ImageContainerMetadata.Kind.OTHER).toString());
        assertEquals("DATA_AFTER_IMAGE=7 bytes",
                new ImageContainerMetadata.Finding("DATA_AFTER_IMAGE", null, 7, ImageContainerMetadata.Kind.OTHER).toString());
    }

    @Test
    public void videoScan_ofARegionPastTheEnd_isNotRecognized() throws Exception {
        File f = file(TestVideos.cleanMp4());
        try (FileInputStream in = new FileInputStream(f)) {
            assertFalse(VideoContainerMetadata.scan(in.getChannel(), 0, f.length() + 100).recognized);
        }
    }

    @Test
    public void heif_isRecognisedByACompatibleBrand_notOnlyItsMajorOne() throws Exception {
        byte[] ftyp = new byte[24];
        java.nio.ByteBuffer.wrap(ftyp).putInt(24).put("ftypiso8".getBytes(StandardCharsets.ISO_8859_1))
                .putInt(0).put("isomheic".getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(HeifContainerMetadata.isHeif(ftyp, ftyp.length));
        java.nio.ByteBuffer.wrap(ftyp, 16, 8).put("isomiso2".getBytes(StandardCharsets.ISO_8859_1));
        assertFalse(HeifContainerMetadata.isHeif(ftyp, ftyp.length));
    }

    @Test
    public void motionPhoto_needsAJpegOrHeifOfSomeLength() throws Exception {
        assertNull(MotionPhoto.find(file(new byte[] {(byte) 0xFF, (byte) 0xD8})));
        assertNull(MotionPhoto.find(file("not a picture at all, just text".getBytes(StandardCharsets.ISO_8859_1))));
    }

    @Test
    public void cacheCleanup_ignoresWhatIsNotAFolder() throws Exception {
        File plain = temp.newFile("plain.txt");
        Class<?> cleanup = com.doubleangels.redact.CacheCleanup.class;
        Method size = cleanup.getDeclaredMethod("directorySizeBytes", File.class);
        Method tempSize = cleanup.getDeclaredMethod("tempPrefixSizeBytes", File.class);
        Method shared = cleanup.getDeclaredMethod("deleteStaleSharedOutputs", File.class, long.class);
        Method stale = cleanup.getDeclaredMethod("deleteStaleTempPrefixFiles", android.content.Context.class, File.class, long.class);
        for (Method m : new Method[] {size, tempSize, shared, stale}) {
            m.setAccessible(true);
        }
        assertEquals(0L, size.invoke(null, plain));
        assertEquals(0L, size.invoke(null, (Object) null));
        assertEquals(0L, tempSize.invoke(null, plain));
        assertEquals(0, shared.invoke(null, plain, 0L));
        assertEquals(0, shared.invoke(null, null, 0L));
        assertEquals(0, stale.invoke(null, app, plain, 0L));
        assertTrue(plain.exists());
    }

    private byte[] resource(String path) throws Exception {
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            return in.readAllBytes();
        }
    }

    @Test
    public void truncatedFiles_areNeverCalledClean() throws Exception {
        for (String name : new String[] {"image/ultra_hdr_tagged.jpg", "image/rgba16.png", "image/heic_tagged.heic",
                "image/motion_photo_tagged.jpg", "image/animated.avif"}) {
            byte[] whole = resource(name);
            for (int cut : new int[] {whole.length / 3, whole.length / 2, whole.length - 3}) {
                File f = file(java.util.Arrays.copyOf(whole, cut));
                ImageContainerMetadata.Report r = ImageContainerMetadata.scan(f);
                assertFalse(name + " cut at " + cut, r.isClean());
            }
        }
        byte[] video = TestVideos.cleanMp4();
        File cutVideo = file(java.util.Arrays.copyOf(video, video.length / 2));
        assertFalse("half a video is not called clean", VideoContainerMetadata.scan(cutVideo).isClean());
    }

    @Test
    public void heif_whoseDataLivesInAnotherFile_isNotRebuilt() throws Exception {
        byte[] heic = resource("image/heic_tagged.heic");
        // A data reference without the self-contained flag points outside the file.
        byte[] dref = buildDinfWithExternalUrl();
        int meta = new String(heic, StandardCharsets.ISO_8859_1).indexOf("meta") - 4;
        int metaSize = java.nio.ByteBuffer.wrap(heic, meta, 4).getInt();
        byte[] patched = new byte[heic.length + dref.length];
        System.arraycopy(heic, 0, patched, 0, meta + metaSize);
        System.arraycopy(dref, 0, patched, meta + metaSize, dref.length);
        System.arraycopy(heic, meta + metaSize, patched, meta + metaSize + dref.length, heic.length - meta - metaSize);
        java.nio.ByteBuffer.wrap(patched, meta, 4).putInt(metaSize + dref.length);
        // The pictures moved by the size of the new box, so a rebuild would read the wrong bytes
        // anyway; what matters is that it refuses before reading them.
        try {
            HeifContainerMetadata.sanitize(file(patched), temp.newFile("out.heic"));
            org.junit.Assert.fail("external data must not be followed");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("External"));
        }
    }

    private static byte[] buildDinfWithExternalUrl() {
        byte[] location = "http://example.com/x\0".getBytes(StandardCharsets.ISO_8859_1);
        java.nio.ByteBuffer url = java.nio.ByteBuffer.allocate(12 + location.length);
        url.putInt(12 + location.length).put("url ".getBytes(StandardCharsets.ISO_8859_1)).putInt(0).put(location);
        java.nio.ByteBuffer dref = java.nio.ByteBuffer.allocate(16 + url.capacity());
        dref.putInt(16 + url.capacity()).put("dref".getBytes(StandardCharsets.ISO_8859_1)).putInt(0).putInt(1).put(url.array());
        java.nio.ByteBuffer dinf = java.nio.ByteBuffer.allocate(8 + dref.capacity());
        dinf.putInt(8 + dref.capacity()).put("dinf".getBytes(StandardCharsets.ISO_8859_1)).put(dref.array());
        return dinf.array();
    }
}
