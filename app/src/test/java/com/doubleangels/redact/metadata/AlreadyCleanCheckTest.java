package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeMediaStoreProvider;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.metadata.AlreadyCleanCheck.Status;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class AlreadyCleanCheckTest {

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
    }

    private File jpeg() throws IOException {
        File f = File.createTempFile("check_", ".jpg", app.getCacheDir());
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        return f;
    }

    private File jpegWith(String tag, String value) throws IOException {
        File f = jpeg();
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        exif.setAttribute(tag, value);
        exif.saveAttributes();
        return f;
    }

    private MediaItem item(File file, String mime) {
        return new MediaItem(FakeMediaStoreProvider.add(IMAGES, file, mime), false, file.getName());
    }

    @Test
    public void imageWithoutMetadata_isAlreadyClean() throws IOException {
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item(jpeg(), "image/jpeg")));
    }

    @Test
    public void imageWithCameraDetails_needsCleaning() throws IOException {
        File f = jpegWith(ExifInterface.TAG_MAKE, "Acme");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item(f, "image/jpeg")));
    }

    @Test
    public void imageWithLocation_needsCleaning() throws IOException {
        File f = jpegWith(ExifInterface.TAG_GPS_LATITUDE, "40/1,26/1,46/1");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item(f, "image/jpeg")));
    }

    @Test
    public void orientationAlone_doesNotCountAsMetadata() throws IOException {
        File f = jpegWith(ExifInterface.TAG_ORIENTATION, "6");
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item(f, "image/jpeg")));
    }

    @Test
    public void tagsTheSettingsKeep_doNotCountAsMetadata() throws IOException {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveCameraSettings(app, true);
        File f = jpegWith(ExifInterface.TAG_MAKE, "Acme");
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item(f, "image/jpeg")));
    }

    @Test
    public void strictClean_countsCameraDetailsEvenIfKeepIsOn() throws IOException {
        AppPreferences.setPreserveCameraSettings(app, true);
        AppPreferences.setStrictClean(app, true);
        File f = jpegWith(ExifInterface.TAG_MAKE, "Acme");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item(f, "image/jpeg")));
    }

    @Test
    public void imageWithXmp_needsCleaning() throws IOException {
        File f = jpeg();
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            out.write("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"></x:xmpmeta>".getBytes());
        }
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item(f, "image/jpeg")));
    }

    /**
     * A photo moved into Pictures/Redact by hand still has its location: the folder says nothing
     * about the file, so it is read like any other.
     */
    @Test
    public void fileInRedactOutputFolder_withLocation_needsCleaning() throws IOException {
        File f = jpegWith(ExifInterface.TAG_GPS_LATITUDE, "40/1,41/1,0/1");
        MediaItem item = item(f, "image/jpeg");
        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Redact/");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item));
    }

    @Test
    public void cleanFileInRedactOutputFolder_isAlreadyClean() throws IOException {
        MediaItem item = item(jpeg(), "image/jpeg");
        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Redact/");
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item));
    }

    /**
     * The output folder can be one that also holds originals (DCIM/Camera, Pictures, Download):
     * an uncleaned original there must not be reported as clean.
     */
    @Test
    public void originalInAChosenOutputFolder_withLocation_needsCleaning() throws IOException {
        com.doubleangels.redact.AppPreferences.setOutputTree(app, false, android.net.Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"));
        File f = jpegWith(ExifInterface.TAG_GPS_LATITUDE, "40/1,41/1,0/1");
        MediaItem item = item(f, "image/jpeg");
        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera/");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item));
    }

    @Test
    public void cleanFileInAChosenOutputFolder_isAlreadyClean() throws IOException {
        com.doubleangels.redact.AppPreferences.setOutputTree(app, false, android.net.Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"));
        MediaItem clean = item(jpeg(), "image/jpeg");
        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera/");
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, clean));
    }

    @Test
    public void similarlyNamedFolder_isNotARedactOutput() throws IOException {
        File f = jpegWith(ExifInterface.TAG_MAKE, "Acme");
        MediaItem item = item(f, "image/jpeg");
        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/RedactBackup/");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item));
    }

    @Test
    public void unsupportedType_isUnknown() throws IOException {
        assertEquals(Status.UNKNOWN, AlreadyCleanCheck.assessItem(app, item(jpeg(), "image/gif")));
    }

    @Test
    public void unreadableItem_isUnknown() {
        MediaItem missing = new MediaItem(Uri.parse("file:///nonexistent/x.jpg"), false, "x.jpg");
        assertEquals(Status.UNKNOWN, AlreadyCleanCheck.assessItem(app, missing));
    }

    @Test
    public void assess_returnsOneStatusPerItemInOrder() throws IOException {
        List<MediaItem> items = Arrays.asList(
                item(jpeg(), "image/jpeg"),
                item(jpegWith(ExifInterface.TAG_MAKE, "Acme"), "image/jpeg"),
                new MediaItem(Uri.parse("file:///nonexistent/x.jpg"), false, "x.jpg"));

        assertEquals(
                Arrays.asList(Status.ALREADY_CLEAN, Status.NEEDS_CLEANING, Status.UNKNOWN),
                AlreadyCleanCheck.assess(app, items, 60_000L));
    }

    private MediaItem video(int key, String value) throws IOException {
        return video(TestVideos.cleanMp4(), key, value);
    }

    private MediaItem video(byte[] content, int key, String value) throws IOException {
        File f = TestVideos.write(app.getCacheDir(), "check.mp4", content);
        Uri uri = FakeMediaStoreProvider.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, f, "video/mp4");
        if (value != null) {
            org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(
                    org.robolectric.shadows.util.DataSource.toDataSource(app, uri), key, value);
        }
        return new MediaItem(uri, true, f.getName());
    }

    @Test
    public void video_withLocationDateOrAuthor_needsCleaning() throws IOException {
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app,
                video(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION, "+40.5-073.9/")));
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app,
                video(android.media.MediaMetadataRetriever.METADATA_KEY_DATE, "20240102T030405.000Z")));
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app,
                video(android.media.MediaMetadataRetriever.METADATA_KEY_AUTHOR, "Someone")));
    }

    @Test
    public void video_withNothingIdentifying_isAlreadyClean() throws IOException {
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app,
                video(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")));
    }

    @Test
    public void video_withALocationOnlyTheContainerShows_needsCleaning() throws IOException {
        // The platform retriever reports nothing for this file, as on a real device.
        byte[] hidden = TestVideos.mp4(0, TestVideos.box("udta",
                TestVideos.mdtaMeta("location", TestVideos.LOCATION), TestVideos.box("loci", new byte[20])));

        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app,
                video(hidden, android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")));
    }

    @Test
    public void video_withOnlyARecordingDateInItsHeaders_needsCleaning() throws IOException {
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app,
                video(TestVideos.mp4(TestVideos.RECORDED_1904),
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")));
    }

    @Test
    public void video_withOnlyARecordingDate_isAlreadyCleanWhenTheDateIsKept() throws IOException {
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setPreserveDateTaken(app, true);
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app,
                video(TestVideos.mp4(TestVideos.RECORDED_1904),
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")));

        // Strict Clean keeps nothing, so the date still has to go.
        AppPreferences.setStrictClean(app, true);
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app,
                video(TestVideos.mp4(TestVideos.RECORDED_1904),
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")));
    }

    @Test
    public void aRealVideoWithQuickTimeLocationKeys_isNeverCalledClean() throws IOException {
        File f = TestVideos.resource(app.getCacheDir(), "video/ffmpeg_location_keys.mp4");

        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app,
                video(java.nio.file.Files.readAllBytes(f.toPath()),
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")));
    }

    @Test
    public void aVideoWhoseContainerCannotBeRead_isNotCalledClean() throws IOException {
        assertEquals(Status.UNKNOWN, AlreadyCleanCheck.assessItem(app,
                video(new byte[64], android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "1000")));
    }

    @Test
    public void video_theRetrieverCannotRead_isUnknown() throws IOException {
        MediaItem item = video(0, null);
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addException(
                org.robolectric.shadows.util.DataSource.toDataSource(app, item.uri()),
                new IllegalArgumentException("not a video"));
        assertEquals(Status.UNKNOWN, AlreadyCleanCheck.assessItem(app, item));
    }

    @Test
    public void unreadableImage_isUnknown() throws IOException {
        MediaItem item = item(jpegWith(ExifInterface.TAG_MAKE, "Acme"), "image/jpeg");
        FakeMediaStoreProvider.failOpen = true;
        assertEquals(Status.UNKNOWN, AlreadyCleanCheck.assessItem(app, item));
    }

    @Test
    public void heic_isCalledCleanOnlyWhenItHoldsNothingButPictures() throws IOException {
        File tagged = TestVideos.resource(app.getCacheDir(), "image/heic_tagged.heic");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item(tagged, "image/heic")));

        File clean = File.createTempFile("check_", ".heic", app.getCacheDir());
        clean.deleteOnExit();
        HeifContainerMetadata.sanitize(tagged, clean);
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item(clean, "image/heic")));

        File withXmp = File.createTempFile("check_", ".heic", app.getCacheDir());
        withXmp.deleteOnExit();
        java.nio.file.Files.copy(clean.toPath(), withXmp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try (FileOutputStream out = new FileOutputStream(withXmp, true)) {
            out.write("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"></x:xmpmeta>".getBytes());
        }
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item(withXmp, "image/heif")));

        // Labelled HEIC but a plain JPEG: read as what it is, which a clean would leave as it is.
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item(jpeg(), "image/heic")));
    }

    @Test
    public void anImageWhoseStructureCannotBeFollowed_isUnknown() throws IOException {
        File f = jpeg();
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        java.nio.file.Files.write(f.toPath(), Arrays.copyOf(bytes, bytes.length - 2)); // no end of image

        assertEquals(Status.UNKNOWN, AlreadyCleanCheck.assessItem(app, item(f, "image/jpeg")));
    }

    @Test
    public void aUriNoProviderAnswersFor_isUnknown() {
        Uri orphan = Uri.parse("content://com.example.nobody/images/1");

        assertEquals(Status.UNKNOWN, AlreadyCleanCheck.assessItem(app, new MediaItem(orphan, false, "1.jpg")));
    }

    @Test
    public void assess_afterTheBudgetRunsOut_reportsUnknown() throws IOException {
        List<MediaItem> items = Arrays.asList(item(jpeg(), "image/jpeg"), item(jpeg(), "image/jpeg"));

        assertEquals(
                Arrays.asList(Status.UNKNOWN, Status.UNKNOWN),
                AlreadyCleanCheck.assess(app, items, -1L));
    }

    @Test
    public void ultraHdr_cleanedByRedact_isAlreadyClean_despiteItsKeptDirectory() throws IOException {
        File tagged = TestVideos.resource(app.getCacheDir(), "image/ultra_hdr_tagged.jpg");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item(tagged, "image/jpeg")));

        File clean = File.createTempFile("check_", ".jpg", app.getCacheDir());
        clean.deleteOnExit();
        ImageContainerMetadata.sanitize(tagged, clean);
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item(clean, "image/jpeg")));
    }
}
