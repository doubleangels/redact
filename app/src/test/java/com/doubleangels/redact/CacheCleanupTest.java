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
        CacheCleanup.resetAutoCleanupStateForTests();
        clearCacheTree();
        AppPreferences.setAutoClearTempFiles(context, true);
    }

    @After
    public void tearDown() {
        Locale.setDefault(originalLocale);
        CacheCleanup.resetAutoCleanupStateForTests();
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
        long oldTimestamp = now - CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS * 2;

        File oldTemp = writeFile(new File(cacheDir, "temp_old.bin"), 100);
        oldTemp.setLastModified(oldTimestamp);
        File oldInbound = writeFile(new File(cacheDir, "inbound_old.bin"), 100);
        oldInbound.setLastModified(oldTimestamp);
        File freshTemp = writeFile(new File(cacheDir, "temp_fresh.bin"), 100);
        freshTemp.setLastModified(now);
        File untracked = writeFile(new File(cacheDir, "stays_forever.bin"), 100);
        untracked.setLastModified(oldTimestamp);
        File processedDir = new File(cacheDir, "processed");
        File oldProcessed = writeFile(new File(processedDir, "old_output.jpg"), 100);
        oldProcessed.setLastModified(oldTimestamp);
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
        oldTemp.setLastModified(now - CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS * 2);
        File justCreated = writeFile(new File(cacheDir, "temp_just_created.bin"), 100);
        justCreated.setLastModified(now);

        int deleted = CacheCleanup.clearAllTempFiles(context);

        assertEquals(1, deleted);
        assertFalse(oldTemp.exists());
        assertTrue(justCreated.exists());
    }

    @Test
    public void shouldRunAutoCleanup_trueOnFirstCallThenFalseForRestOfProcess() {
        assertTrue(CacheCleanup.shouldRunAutoCleanup(context));
        assertFalse(CacheCleanup.shouldRunAutoCleanup(context));
        assertFalse(CacheCleanup.shouldRunAutoCleanup(context));
    }

    @Test
    public void shouldRunAutoCleanup_falseWhenPreferenceDisabled() {
        AppPreferences.setAutoClearTempFiles(context, false);
        assertFalse(CacheCleanup.shouldRunAutoCleanup(context));
    }

    @Test
    public void performAutoCleanup_removesStaleFilesLikeClearStaleTempFiles() throws IOException {
        File cacheDir = context.getCacheDir();
        long oldTimestamp =
                System.currentTimeMillis() - CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS * 2;
        File oldTemp = writeFile(new File(cacheDir, "temp_old.bin"), 100);
        oldTemp.setLastModified(oldTimestamp);

        CacheCleanup.performAutoCleanup(context);

        assertFalse(oldTemp.exists());
    }

    @Test
    public void scheduleAutoCleanupIfEnabled_doesNotThrowWhenDisabled() {
        // Kept off for this test so it never hands work to the real background executor --
        // the actual sweep behavior is covered deterministically by performAutoCleanup and
        // shouldRunAutoCleanup above, without a background thread that could outlive the test.
        AppPreferences.setAutoClearTempFiles(context, false);
        CacheCleanup.scheduleAutoCleanupIfEnabled(context);
        CacheCleanup.scheduleAutoCleanupIfEnabled(context);
    }

    private static File writeFile(File file, int size) throws IOException {
        file.getParentFile().mkdirs();
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
            fos.write(new byte[size]);
        }
        return file;
    }

    // ---- orphaned working files from a previous process ----------------------------------------

    private File cacheFile(String name) throws IOException {
        File f = new File(context.getCacheDir(), name);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            out.write(new byte[512]);
        }
        return f;
    }

    @Test
    public void orphanSweep_securelyDeletesFreshWorkFilesRegardlessOfAge() throws IOException {
        // Brand new files (no age threshold applies): a previous process left these behind.
        File inbound = cacheFile("inbound_12345.jpg");
        File source = cacheFile("temp_999.jpg");
        File verify = cacheFile("verify_999.jpg");
        File transmux = cacheFile("vid_transmux_999.mp4");
        File transform = cacheFile("vid_transform_999.mp4");
        File unrelated = cacheFile("keep_me.txt");
        File processed = new File(new File(context.getCacheDir(), "processed"), "shared_out.jpg");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(processed)) {
            out.write(new byte[64]);
        }

        CacheCleanup.scheduleOrphanedWorkFileSweep(context);
        CacheCleanup.awaitOrphanSweepForTests();

        for (File f : new File[] {inbound, source, verify, transmux, transform}) {
            assertFalse(f.getName() + " should be gone", f.exists());
        }
        assertTrue("unrelated files stay", unrelated.exists());
        assertTrue("a cleaned file that may still be shared stays", processed.exists());
    }

    @Test
    public void orphanSweep_neverTouchesFilesCreatedAfterItWasScheduled() throws IOException {
        File orphan = cacheFile("inbound_old.jpg");

        CacheCleanup.scheduleOrphanedWorkFileSweep(context);
        // A share-in that started this very process creates its snapshot right after startup.
        File current = cacheFile("inbound_current.jpg");
        CacheCleanup.awaitOrphanSweepForTests();

        assertFalse(orphan.exists());
        assertTrue("this process's own snapshot must survive", current.exists());
    }

    @Test
    public void orphanSweep_isANoOpWhenThereIsNothingToDelete() {
        CacheCleanup.scheduleOrphanedWorkFileSweep(context);
        CacheCleanup.awaitOrphanSweepForTests();
    }

    @Test
    public void orphanSweep_removesSharedFilesOnlyOnceTheirAppHasHadTimeToReadThem() throws IOException {
        File dir = new File(context.getCacheDir(), "processed");
        dir.mkdirs();
        File old = new File(dir, "shared_old.jpg");
        File fresh = new File(dir, "shared_fresh.jpg");
        for (File f : new File[] {old, fresh}) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(new byte[64]);
            }
        }
        assertTrue(old.setLastModified(
                System.currentTimeMillis() - CacheCleanup.SHARED_OUTPUT_MAX_AGE_MS - 60_000L));

        CacheCleanup.scheduleOrphanedWorkFileSweep(context);
        CacheCleanup.awaitOrphanSweepForTests();

        assertFalse("a share older than the grace period is gone", old.exists());
        assertTrue("a recent share stays for the app reading it", fresh.exists());
    }

    @Test
    public void orphanSweep_removesALeftoverCensoredCopy() throws IOException {
        File censored = cacheFile("censored_123.jpg");

        CacheCleanup.scheduleOrphanedWorkFileSweep(context);
        CacheCleanup.awaitOrphanSweepForTests();

        assertFalse(censored.exists());
    }

    @Test
    public void orphanSweep_removesThumbnailsAnEarlierVersionCachedOnDisk() throws IOException {
        File dir = new File(context.getCacheDir(), "image_manager_disk_cache");
        dir.mkdirs();
        File thumbnail = new File(dir, "0a1b2c.0");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(thumbnail)) {
            out.write(new byte[64]);
        }

        CacheCleanup.scheduleOrphanedWorkFileSweep(context);
        CacheCleanup.awaitOrphanSweepForTests();

        assertFalse(thumbnail.exists());
    }

    @Test
    public void staleSweep_securelyRemovesAnUltraHdrXmpCopy() throws IOException {
        File cacheDir = context.getCacheDir();
        File orphan = writeFile(new File(cacheDir, "xmp_1234.jpg"), 100);
        orphan.setLastModified(System.currentTimeMillis() - CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS * 2);

        assertEquals(1, CacheCleanup.clearStaleTempFiles(context, CacheCleanup.DEFAULT_STALE_TEMP_MAX_AGE_MS));
        assertFalse(orphan.exists());
    }

    private static final java.util.regex.Pattern TEMP_FILE_NAME = java.util.regex.Pattern.compile(
            "createTempFile\\(\\s*(\"[^\"]*\"|[A-Z_]+)"
                    + "|new File\\([^,;()]*(?:\\([^()]*\\))?[^,;()]*,\\s*(\"[^\"]*\")\\s*\\+");

    /**
     * Every working file named in the code must start with a prefix the sweeps know, or a copy of a
     * photo left by a process that died mid-clean is never removed.
     */
    @Test
    public void everyWorkingFilePrefix_isCoveredByTheSweeps() throws Exception {
        java.lang.reflect.Field field = CacheCleanup.class.getDeclaredField("TEMP_PREFIXES");
        field.setAccessible(true);
        String[] prefixes = (String[]) field.get(null);

        java.util.List<String> uncovered = new java.util.ArrayList<>();
        int found = 0;
        try (java.util.stream.Stream<java.nio.file.Path> files =
                     java.nio.file.Files.walk(new File("src/main/java").toPath())) {
            for (java.nio.file.Path file : (Iterable<java.nio.file.Path>)
                    files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(java.nio.file.Files.readAllBytes(file),
                        java.nio.charset.StandardCharsets.UTF_8);
                java.util.regex.Matcher m = TEMP_FILE_NAME.matcher(source);
                while (m.find()) {
                    String name = m.group(1) != null ? m.group(1) : m.group(2);
                    if (!name.startsWith("\"")) {
                        java.util.regex.Matcher constant = java.util.regex.Pattern.compile(
                                "String\\s+" + name + "\\s*=\\s*(\"[^\"]*\")").matcher(source);
                        if (!constant.find()) {
                            uncovered.add(file.getFileName() + ": unresolved " + name);
                            continue;
                        }
                        name = constant.group(1);
                    }
                    name = name.substring(1, name.length() - 1);
                    found++;
                    boolean covered = false;
                    for (String prefix : prefixes) {
                        covered |= name.startsWith(prefix);
                    }
                    if (!covered) {
                        uncovered.add(file.getFileName() + ": " + name);
                    }
                }
            }
        }
        assertTrue("The scan found no working files; is the pattern still right?", found > 10);
        assertEquals("Add these to CacheCleanup.TEMP_PREFIXES: " + uncovered, 0, uncovered.size());
    }
}
