package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.net.Uri;
import android.provider.MediaStore;

import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MediaSelectorInstanceTest {

    private Activity activity;
    private MediaSelector selector;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        FakeMediaStoreProvider.install(activity);
        selector = new MediaSelector(activity);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        for (File f : files) {
            f.delete();
        }
    }

    private File file(String suffix, byte[] content) throws IOException {
        File f = File.createTempFile("sel_", suffix, activity.getFilesDir());
        files.add(f);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
        return f;
    }

    private static byte[] animatedWebp() {
        byte[] data = new byte[40];
        System.arraycopy("RIFF".getBytes(), 0, data, 0, 4);
        System.arraycopy("WEBP".getBytes(), 0, data, 8, 4);
        System.arraycopy("ANIM".getBytes(), 0, data, 20, 4);
        return data;
    }

    private static byte[] animatedPng() {
        byte[] data = new byte[40];
        data[0] = (byte) 0x89;
        data[1] = 'P';
        data[2] = 'N';
        data[3] = 'G';
        System.arraycopy("acTL".getBytes(), 0, data, 16, 4);
        return data;
    }

    // ---- processMediaUri ---------------------------------------------------------------------------

    @Test
    public void processMediaUri_fileUri_isNamedAfterTheFileAndTypedByExtension() throws Exception {
        File video = file(".mp4", new byte[8]);
        MediaItem item = selector.processMediaUri(Uri.fromFile(video));
        assertTrue(item.isVideo());
        assertTrue(item.fileName().endsWith(".mp4"));

        MediaItem image = selector.processMediaUri(Uri.fromFile(file(".jpg", new byte[8])));
        assertFalse(image.isVideo());
    }

    @Test
    public void processMediaUri_contentUri_usesTheProvidersMimeType() throws Exception {
        Uri video = FakeMediaStoreProvider.add(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, file(".bin", new byte[8]), "video/mp4");
        Uri image = FakeMediaStoreProvider.add(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, file(".bin", new byte[8]), "image/jpeg");

        assertTrue(selector.processMediaUri(video).isVideo());
        assertFalse(selector.processMediaUri(image).isVideo());
        assertTrue(selector.isVideoContent(video, null));
        assertFalse(selector.isVideoContent(image, "clip.mp4"));
    }

    @Test
    public void releasePersistableReadPermission_toleratesEveryUriShape() throws Exception {
        MediaSelector.releasePersistableReadPermission(activity, null);
        MediaSelector.releasePersistableReadPermission(activity, Uri.fromFile(file(".jpg", new byte[1])));
        MediaSelector.releasePersistableReadPermission(
                activity, Uri.parse("content://" + "media/external/images/media/3"));
    }

    // ---- animated images ------------------------------------------------------------------------------

    @Test
    public void isAnimatedImageUri_readsTheHeaderOfTheFile() throws Exception {
        assertTrue(MediaSelector.isAnimatedImageUri(activity, Uri.fromFile(file(".webp", animatedWebp())), 4096));
        assertTrue(MediaSelector.isAnimatedImageUri(activity, Uri.fromFile(file(".png", animatedPng())), 4096));
        assertFalse(MediaSelector.isAnimatedImageUri(activity, Uri.fromFile(file(".jpg", new byte[64])), 4096));
        assertFalse(MediaSelector.isAnimatedImageUri(activity, Uri.fromFile(new File("/nonexistent/x.png")), 4096));
    }

    @Test
    public void isAnimatedImage_combinesMimeNameAndPayload() throws Exception {
        assertFalse(selector.isAnimatedImage(new MediaItem(Uri.parse("file:///x.mp4"), true, "x.mp4")));

        Uri gifByMime = FakeMediaStoreProvider.add(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, file(".bin", new byte[8]), "image/gif");
        assertTrue(selector.isAnimatedImage(new MediaItem(gifByMime, false, "photo.bin")));

        assertTrue(selector.isAnimatedImage(
                new MediaItem(Uri.fromFile(file(".dat", new byte[8])), false, "loop.GIF")));

        assertTrue(selector.isAnimatedImage(
                new MediaItem(Uri.fromFile(file(".webp", animatedWebp())), false, "a.webp")));
        assertTrue(selector.isAnimatedImage(
                new MediaItem(Uri.fromFile(file(".png", animatedPng())), false, "a.png")));
        assertTrue(selector.isAnimatedImage(
                new MediaItem(Uri.fromFile(file(".apng", animatedPng())), false, "a.apng")));
        assertFalse(selector.isAnimatedImage(
                new MediaItem(Uri.fromFile(file(".png", new byte[64])), false, "still.png")));

        // No MIME type and an unrelated name: nothing to probe.
        assertFalse(selector.isAnimatedImage(
                new MediaItem(Uri.fromFile(file(".jpg", new byte[64])), false, "photo.jpg")));
        assertFalse(selector.isAnimatedImage(new MediaItem(Uri.parse("file:///nameless"), false, null)));
    }

    @Test
    public void getFileName_fallsBackToAGenericName() throws Exception {
        File f = file(".jpg", new byte[1]);
        assertTrue(selector.getFileName(Uri.fromFile(f)).endsWith(f.getName()));
        assertEquals("media", selector.getFileName(Uri.parse("content://nothing")));
    }

    @Test
    public void isAnimatedImageFile_checksMimeThenName() {
        assertTrue(MediaSelector.isAnimatedImageFile(null, "IMAGE/GIF"));
        assertTrue(MediaSelector.isAnimatedImageFile("a.GIF", null));
        assertFalse(MediaSelector.isAnimatedImageFile("a.jpg", "image/jpeg"));
        assertFalse(MediaSelector.isAnimatedImageFile(null, null));
    }

    @Test
    public void containsAnimatedImagePayload_rejectsShortBuffers() {
        assertFalse(MediaSelector.containsAnimatedImagePayload(new byte[4], 4));
    }
}
