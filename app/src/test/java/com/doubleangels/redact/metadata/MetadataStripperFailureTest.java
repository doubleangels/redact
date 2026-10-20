package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.FakeMediaStoreProvider;
import com.doubleangels.redact.FileProviderTestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** What the stripper returns when a step of an image clean cannot be done. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MetadataStripperFailureTest {

    @Rule
    public Timeout timeout = Timeout.seconds(60);

    private Application app;
    private MetadataStripper stripper;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        FileProviderTestSupport.makeUsable(app);
        stripper = new MetadataStripper(app);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private File jpegWithGps() throws IOException {
        File f = File.createTempFile("fail_", ".jpg", app.getFilesDir());
        files.add(f);
        try (FileOutputStream out = new FileOutputStream(f)) {
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_MAKE, "Acme");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
        exif.saveAttributes();
        return f;
    }

    private File processedDir() {
        return new File(app.getCacheDir(), "processed");
    }

    private static void deleteTree(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    @Test
    public void shareImage_whenTheOutputFolderCannotBeMade_givesNothing() throws Exception {
        deleteTree(processedDir());
        stripper.setTestForceOutputDirMkdirsFailure(true);

        assertNull(stripper.stripMetadataForSharing(Uri.fromFile(jpegWithGps()), "photo.jpg", false));
    }

}
