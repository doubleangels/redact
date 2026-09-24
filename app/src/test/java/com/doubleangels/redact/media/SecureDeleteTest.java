package com.doubleangels.redact.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.doubleangels.redact.AppPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class SecureDeleteTest {

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void secureDelete_returnsFalseForNonExistentFile() {
        File missing = new File(context.getCacheDir(), "missing_" + System.nanoTime());
        assertFalse(SecureDelete.secureDelete(context, missing));
    }

    @Test
    public void secureDelete_returnsFalseForDirectory() throws IOException {
        File dir = new File(context.getCacheDir(), "dir_" + System.nanoTime());
        assertTrue(dir.mkdirs());
        try {
            assertFalse(SecureDelete.secureDelete(context, dir));
            assertTrue(dir.exists());
        } finally {
            dir.delete();
        }
    }

    @Test
    public void secureDelete_deletesPopulatedFileWithConfiguredPasses() throws IOException {
        File file = File.createTempFile("secure_", ".bin", context.getCacheDir());
        AppPreferences.setSecureDeletePasses(context, AppPreferences.getSecureDeletePasses(context));
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(new byte[8192]);
        }

        assertTrue(SecureDelete.secureDelete(context, file));
        assertFalse(file.exists());
    }

    @Test
    public void secureDelete_withSinglePassStillDeletes() throws IOException {
        AppPreferences.setSecureDeletePasses(context, 1);
        File file = File.createTempFile("secure_", ".bin", context.getCacheDir());
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(new byte[4096]);
        }

        assertTrue(SecureDelete.secureDelete(context, file));
        assertFalse(file.exists());
    }

    @Test
    public void secureDelete_deletesEmptyFile() throws IOException {
        File file = File.createTempFile("secure_", ".bin", context.getCacheDir());
        assertTrue(SecureDelete.secureDelete(context, file));
        assertFalse(file.exists());
    }
}