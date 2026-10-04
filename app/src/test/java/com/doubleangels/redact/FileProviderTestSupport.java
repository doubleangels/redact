package com.doubleangels.redact;

import static org.junit.Assume.assumeFalse;

import androidx.core.content.FileProvider;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;

/** Workarounds for running code that calls {@link FileProvider#getUriForFile} under Robolectric. */
public final class FileProviderTestSupport {

    private FileProviderTestSupport() {
    }

    /**
     * FileProvider caches its path roots statically, but Robolectric gives each test a new
     * data directory, so a cached strategy from an earlier test rejects this test's files.
     */
    public static void clearCache() {
        try {
            Field cache = FileProvider.class.getDeclaredField("sCache");
            cache.setAccessible(true);
            Object map = cache.get(null);
            synchronized (map) {
                ((Map<?, ?>) map).clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not reset FileProvider cache", e);
        }
    }

    /**
     * FileProvider matches roots with a hard-coded '/' separator, so producing a content URI
     * only works under Robolectric on POSIX hosts (CI runs on Linux).
     */
    public static void assumeUsable() {
        assumeFalse("FileProvider path matching fails on Windows hosts",
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }
}
