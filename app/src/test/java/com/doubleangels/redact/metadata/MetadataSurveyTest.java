package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.media.MediaItem;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MetadataSurveyTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private final Application app = RuntimeEnvironment.getApplication();

    @After
    public void tearDown() {
        AppPreferences.resetAll(app);
    }

    private File jpeg(boolean tagged) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        File file = temp.newFile(tagged ? "tagged.jpg" : "plain.jpg");
        Files.write(file.toPath(), out.toByteArray());
        if (tagged) {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setLatLong(52.3702, 4.8952);
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:17 09:30:12");
            exif.setAttribute(ExifInterface.TAG_MAKE, "Acme");
            exif.setAttribute(ExifInterface.TAG_ARTIST, "Jane Doe");
            exif.saveAttributes();
        }
        return file;
    }

    @Test
    public void aTaggedPhoto_reportsEachKindOfDetail() throws Exception {
        CleanStats stats = MetadataSurvey.survey(app, new MediaItem(Uri.fromFile(jpeg(true)), false, "tagged.jpg"));
        assertNotNull(stats);
        assertTrue(stats.hadLocation);
        assertTrue(stats.hadDate);
        assertTrue(stats.hadDevice);
        assertTrue(stats.hadName);
        assertTrue(stats.removedFields > 0);
    }

    @Test
    public void aPlainPhoto_carriesNothing() throws Exception {
        CleanStats stats = MetadataSurvey.survey(app, new MediaItem(Uri.fromFile(jpeg(false)), false, "plain.jpg"));
        assertNotNull(stats);
        assertFalse(stats.hadLocation);
        assertFalse(stats.hadName);
    }

    @Test
    public void aFileThatCannotBeRead_isNull() throws Exception {
        File junk = temp.newFile("junk.jpg");
        Files.write(junk.toPath(), new byte[] {1, 2, 3});
        assertNull(MetadataSurvey.survey(app, new MediaItem(Uri.fromFile(junk), false, "junk.jpg")));
        assertNull(MetadataSurvey.survey(app, new MediaItem(Uri.fromFile(new File(temp.getRoot(), "gone.mp4")), true, "gone.mp4")));
    }

    @Test
    public void aTaggedVideo_reportsItsLocationAndNames() throws Exception {
        File video = TestVideos.write(temp.getRoot(), "tagged.webm", TestVideos.webm(true, false));
        CleanStats stats = MetadataSurvey.survey(app, new MediaItem(Uri.fromFile(video), true, "tagged.webm"));
        assertNotNull(stats);
        assertTrue(stats.hadLocation);
        assertTrue(stats.hadDate);
        assertTrue(stats.removedFields > 0);
    }

    @Test
    public void aVideoThatCannotBeOpened_countsAsHavingSound_soRemoveAudioFailsClosed() {
        assertTrue(CleanVerifier.hasAudioTrack(new File(temp.getRoot(), "missing.mp4")));
    }
}
