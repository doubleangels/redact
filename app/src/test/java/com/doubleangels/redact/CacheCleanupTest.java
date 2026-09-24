package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class CacheCleanupTest {

    private Context context;
    private Locale originalLocale;

    @Before
    public void setUp() throws IOException {
        context = RuntimeEnvironment.getApplication();
        originalLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        clearCacheTree();
    }

    @After
    public void tearDown() {
        Locale.setDefault(originalLocale);
    }

    private void clearCacheTree() {
        File cacheDir = context.getCacheDir();
        if (cacheDir != null) {
            deleteRecursively(cacheDir);
        }
        File processedDir = new File(context.getCacheDir(), "processed");
        processedDir.mkdirs();
    }

    private static void deleteRecursively(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                } else {
                    child.delete();
                }
            }
        }
    }

    @Test
    public void formatSize_handlesBytesKilobytesAndMegabytes() {
        assertEquals("0 B", CacheCleanup.formatSize(0));
        assertEquals("500 B", CacheCleanup.formatSize(500));
        assertEquals("1.0 KB", CacheCleanup.formatSize(1024));
        assertEquals("1.5 KB", CacheCleanup.formatSize(1536));
        assertEquals("1.0 MB", CacheCleanup.formatSize(1024 * 1024));
        assertEquals("2.5 MB", CacheCleanup.formatSize((long) (2.5 * 1024 * 1024)));
    }

    @Test
    public void getTempCacheSizeBytes_countsProcessedDirAndKnownTempPrefixes() throws IOException {
        File cacheDir = context.getCacheDir();
        writeFile(new File(cacheDir, "temp_photo.jpg"), 1024);
        writeFile(new File(cacheDir, "inbound_share"), 512);
        writeFile(new File(cacheDir, "verify_pending.dat"), 2048);
        writeFile(new File(cacheDir, "normal_file_not_counted.bin"), 4096);
        File processedDir = new File(cacheDir, "processed");
        writeFile(new File(processedDir, "output.jpg"), 700);

        long expected = 1024 + 512 + 2048 + 700;
        assertEquals(expected, CacheCleanup.getTempCacheSizeBytes(context));
    }

    @Test
    public void getTempCacheSizeBytes_emptyCacheIsZero() {
        assertEquals(0, CacheCleanup.getTempCacheSizeBytes(context));
    }

    @Test
    public void clearStaleTempFiles_removesOnlyOldPrefixedAndProcessedFiles() throws IOException {
        File cacheDir = context.getCacheDir();
        long now = System.currentTimeMillis();
        long oldCutoff = now - CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS;

        File oldTemp = writeFile(new File(cacheDir, "temp_old.bin"), 100);
        oldTemp.setLastModified(oldCutoff);
        File oldInbound = writeFile(new File(cacheDir, "inbound_old.bin"), 100);
        oldInbound.setLastModified(oldCutoff);
        File freshTemp = writeFile(new File(cacheDir, "temp_fresh.bin"), 100);
        freshTemp.setLastModified(now);
        File untracked = writeFile(new File(cacheDir, "stays_forever.bin"), 100);
        untracked.setLastModified(oldCutoff);
        File processedDir = new File(cacheDir, "processed");
        File oldProcessed = writeFile(new File(processedDir, "old_output.jpg"), 100);
        oldProcessed.setLastModified(oldCutoff);
        File freshProcessed = writeFile(new File(processedDir, "new_output.jpg"), 100);
        freshProcessed.setLastModified(now);

        int deleted = CacheCleanup.clearStaleTempFiles(
                context, CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS);

        assertEquals(3, deleted);
        assertFalse(oldTemp.exists());
        assertFalse(oldInbound.exists());
        assertFalse(oldProcessed.exists());
        assertTrue(freshTemp.exists());
        assertTrue(freshProcessed.exists());
        assertTrue(untracked.exists());
    }

    @Test
    public void clearAllTempFiles_usesShortGracePeriodAndCleansOld() throws IOException {
        File cacheDir = context.getCacheDir();
        long now = System.currentTimeMillis();
        File oldTemp = writeFile(new File(cacheDir, "inbound_stale.bin"), 100);
        oldTemp.setLastModified(now - CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS);
        File justCreated = writeFile(new File(cacheDir, "temp_just_created.bin"), 100);
        justCreated.setLastModified(now);

        int deleted = CacheCleanup.clearAllTempFiles(context);

        assertEquals(1, deleted);
        assertFalse(oldTemp.exists());
        assertTrue(justCreated.exists());
    }

    private static File writeFile(File file, int size) throws IOException {
        file.getParentFile().mkdirs();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(new byte[size]);
        }
        return file;
    }
}