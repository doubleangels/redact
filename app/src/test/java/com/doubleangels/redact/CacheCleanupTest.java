package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
public class CacheCleanupTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        CacheCleanup.clearAllTempFiles(context);
    }

    @Test
    public void getTempCacheSizeBytes_countsProcessedAndTempFiles() throws Exception {
        File processedDir = new File(context.getCacheDir(), "processed");
        assertTrue(processedDir.mkdirs() || processedDir.isDirectory());
        File processedFile = new File(processedDir, "share_test.bin");
        try (FileOutputStream fos = new FileOutputStream(processedFile)) {
            fos.write(new byte[1024]);
        }

        File externalCache = context.getExternalCacheDir();
        assert externalCache != null;
        File tempFile = new File(externalCache, "temp_test.bin");
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(new byte[512]);
        }

        long size = CacheCleanup.getTempCacheSizeBytes(context);
        assertTrue(size >= 1024 + 512);

        int deleted = CacheCleanup.clearAllTempFiles(context);
        assertTrue(deleted >= 2);
        assertEquals(0, CacheCleanup.getTempCacheSizeBytes(context));

        processedFile.delete();
        tempFile.delete();
    }

    @Test
    public void formatSize_units() {
        assertEquals("512 B", CacheCleanup.formatSize(512));
        assertEquals("1.0 KB", CacheCleanup.formatSize(1024));
        assertEquals("1.0 MB", CacheCleanup.formatSize(1024L * 1024L));
    }

    @Test
    public void getTempCacheSizeBytes_ignoresNonTempExternalFiles() throws Exception {
        File externalCache = context.getExternalCacheDir();
        assert externalCache != null;
        File other = new File(externalCache, "not_temp.bin");
        try (FileOutputStream fos = new FileOutputStream(other)) {
            fos.write(new byte[2048]);
        }
        long before = CacheCleanup.getTempCacheSizeBytes(context);
        assertEquals(0, before);

        File subdir = new File(externalCache, "temp_nested");
        subdir.mkdirs();
        assertEquals(0, CacheCleanup.getTempCacheSizeBytes(context));

        other.delete();
        subdir.delete();
    }

    @Test
    public void clearAllTempFiles_missingDirsReturnsZero() {
        File processedDir = new File(context.getCacheDir(), "processed");
        if (processedDir.exists()) {
            File[] files = processedDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            processedDir.delete();
        }
        assertTrue(CacheCleanup.clearAllTempFiles(context) >= 0);
        assertEquals(0, CacheCleanup.getTempCacheSizeBytes(context));
    }

    @Test
    public void privateHelpers_handleNullListFilesAndDeleteFailures() throws Exception {
        File listFilesNullDir = mock(File.class);
        when(listFilesNullDir.isDirectory()).thenReturn(true);
        when(listFilesNullDir.listFiles()).thenReturn(null);

        assertEquals(0L, invokeLongHelper("directorySizeBytes", listFilesNullDir));
        assertEquals(0L, invokeLongHelper("tempPrefixSizeBytes", listFilesNullDir));
        assertEquals(0, invokeDeleteHelper("deleteAllFilesInDirectory", listFilesNullDir));
        assertEquals(0, invokeDeleteHelper("deleteTempPrefixFiles", listFilesNullDir));

        File failingFile = mock(File.class);
        when(failingFile.isFile()).thenReturn(true);
        when(failingFile.getName()).thenReturn("temp_fail.bin");
        when(failingFile.delete()).thenReturn(false);
        when(failingFile.length()).thenReturn(123L);

        File directory = mock(File.class);
        when(directory.isDirectory()).thenReturn(true);
        when(directory.listFiles()).thenReturn(new File[]{failingFile});

        assertEquals(123L, invokeLongHelper("directorySizeBytes", directory));
        assertEquals(123L, invokeLongHelper("tempPrefixSizeBytes", directory));
        assertEquals(0, invokeDeleteHelper("deleteAllFilesInDirectory", directory));
        assertEquals(0, invokeDeleteHelper("deleteTempPrefixFiles", directory));
    }

    @Test
    public void clearAllTempFiles_doesNotDeleteNonTempExternalFiles() throws Exception {
        File externalCache = context.getExternalCacheDir();
        assert externalCache != null;
        File keepFile = new File(externalCache, "keep_me.bin");
        try (FileOutputStream fos = new FileOutputStream(keepFile)) {
            fos.write(new byte[8]);
        }

        CacheCleanup.clearAllTempFiles(context);

        assertTrue(keepFile.exists());
        assertFalse(keepFile.getName().startsWith("temp_"));
        keepFile.delete();
    }

    private long invokeLongHelper(String methodName, File directory) throws Exception {
        Method method = CacheCleanup.class.getDeclaredMethod(methodName, File.class);
        method.setAccessible(true);
        return (long) method.invoke(null, directory);
    }

    private int invokeDeleteHelper(String methodName, File directory) throws Exception {
        Method method = CacheCleanup.class.getDeclaredMethod(methodName, File.class);
        method.setAccessible(true);
        return (int) method.invoke(null, directory);
    }
}
