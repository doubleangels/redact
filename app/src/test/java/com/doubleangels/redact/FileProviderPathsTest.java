package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.app.Application;
import android.net.Uri;

import androidx.core.content.FileProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;

/** The FileProvider serves the cleaned files in processed/ and nothing else. */
@RunWith(RobolectricTestRunner.class)
public class FileProviderPathsTest {

    private Application app;
    private String authority;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        authority = app.getPackageName() + ".fileprovider";
        FileProviderTestSupport.makeUsable(app);
    }

    @Test
    public void aCleanedFileInProcessed_canBeShared() {
        Uri uri = FileProvider.getUriForFile(app, authority, new File(app.getCacheDir(), "processed/a.jpg"));
        assertEquals("/processed/a.jpg", uri.getPath());
    }

    @Test
    public void everyOtherWorkingFolder_isOutOfReach() {
        File[] outside = {
                new File(app.getCacheDir(), "temp/a.jpg"),
                new File(app.getCacheDir(), "inbound_1.jpg"),
                new File(app.getFilesDir(), "converted/a.jpg"),
                new File(app.getExternalCacheDir(), "redact/a.mp4"),
                new File(app.getExternalFilesDir(null), "Redact/a.jpg"),
        };
        for (File file : outside) {
            assertThrows(file.getPath(), IllegalArgumentException.class,
                    () -> FileProvider.getUriForFile(app, authority, file));
        }
    }
}
