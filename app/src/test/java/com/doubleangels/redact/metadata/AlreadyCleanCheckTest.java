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

    @Test
    public void fileInRedactOutputFolder_isAlreadyClean_evenWithMetadata() throws IOException {
        File f = jpegWith(ExifInterface.TAG_MAKE, "Acme");
        MediaItem item = item(f, "image/jpeg");
        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Redact/");
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item));
    }

    @Test
    public void fileInChosenOutputFolder_isAlreadyClean() {
        android.net.Uri tree = android.net.Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3APictures%2FPrivate");
        com.doubleangels.redact.AppPreferences.setOutputTree(app, false, tree);
        android.net.Uri inside = android.net.Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3APictures%2FPrivate"
                        + "/document/primary%3APictures%2FPrivate%2Fabc.jpg");
        android.net.Uri outside = android.net.Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3APictures%2FPrivateOther"
                        + "/document/primary%3APictures%2FPrivateOther%2Fabc.jpg");
        assertEquals(true, com.doubleangels.redact.media.OutputDestination.isInChosenFolder(app, inside));
        assertEquals(false, com.doubleangels.redact.media.OutputDestination.isInChosenFolder(app, outside));
    }

    @Test
    public void fileInChosenOutputFolder_pickedThroughMediaStore_isAlreadyClean() throws IOException {
        com.doubleangels.redact.AppPreferences.setOutputTree(app, false, android.net.Uri.parse(
                "content://com.android.externalstorage.documents/tree/primary%3APictures%2FPrivate"));
        File f = jpegWith(ExifInterface.TAG_MAKE, "Acme");
        MediaItem item = item(f, "image/jpeg");
        // Shared storage ignores case, so a differently cased path is the same folder.
        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "pictures/private/");
        assertEquals(Status.ALREADY_CLEAN, AlreadyCleanCheck.assessItem(app, item));

        FakeMediaStoreProvider.only().values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PrivateOther/");
        assertEquals(Status.NEEDS_CLEANING, AlreadyCleanCheck.assessItem(app, item));
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

    @Test
    public void assess_afterTheBudgetRunsOut_reportsUnknown() throws IOException {
        List<MediaItem> items = Arrays.asList(item(jpeg(), "image/jpeg"), item(jpeg(), "image/jpeg"));

        assertEquals(
                Arrays.asList(Status.UNKNOWN, Status.UNKNOWN),
                AlreadyCleanCheck.assess(app, items, -1L));
    }
}
