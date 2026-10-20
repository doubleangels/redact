package com.doubleangels.redact;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Workarounds for running code that calls {@link FileProvider#getUriForFile} under Robolectric. */
public final class FileProviderTestSupport {

    private FileProviderTestSupport() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cache() {
        try {
            Field cache = FileProvider.class.getDeclaredField("sCache");
            cache.setAccessible(true);
            return (Map<String, Object>) cache.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not reach the FileProvider cache", e);
        }
    }

    /**
     * FileProvider caches its path roots statically, but Robolectric gives each test a new
     * data directory, so a cached strategy from an earlier test rejects this test's files.
     */
    public static void clearCache() {
        Map<String, Object> map = cache();
        synchronized (map) {
            map.clear();
        }
    }

    /**
     * FileProvider matches roots with a hard-coded '/' separator, so on Windows hosts it rejects
     * every file. There, this installs a strategy for the app's FileProvider that maps the roots in
     * {@code res/xml/file_paths.xml} the same way with either separator. Elsewhere the real
     * strategy is used.
     */
    public static void makeUsable(Context context) {
        clearCache();
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return;
        }
        String authority = context.getPackageName() + ".fileprovider";
        Map<String, File> roots = new LinkedHashMap<>();
        roots.put("processed", new File(context.getCacheDir(), "processed"));
        roots.put("temp", new File(context.getCacheDir(), "temp"));
        roots.put("converted", new File(context.getFilesDir(), "converted"));
        if (context.getExternalCacheDir() != null) {
            roots.put("external_cache", new File(context.getExternalCacheDir(), "redact"));
        }
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles != null) {
            roots.put("external_files", new File(externalFiles, "Redact"));
        }
        try {
            Class<?> strategyType = Class.forName("androidx.core.content.FileProvider$PathStrategy");
            Object strategy = Proxy.newProxyInstance(strategyType.getClassLoader(),
                    new Class<?>[] {strategyType}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUriForFile" -> uriFor(authority, roots, (File) args[0]);
                        case "getFileForUri" -> fileFor(roots, (Uri) args[0]);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "PortablePathStrategy";
                    });
            Map<String, Object> map = cache();
            synchronized (map) {
                map.put(authority, strategy);
            }
        } catch (ClassNotFoundException e) {
            throw new AssertionError("FileProvider.PathStrategy is missing", e);
        }
    }

    private static Uri uriFor(String authority, Map<String, File> roots, File file) throws IOException {
        String path = file.getCanonicalPath();
        for (Map.Entry<String, File> root : roots.entrySet()) {
            String rootPath = root.getValue().getCanonicalPath();
            if (path.startsWith(rootPath + File.separator)) {
                String relative = path.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
                return new Uri.Builder().scheme("content").authority(authority)
                        .appendPath(root.getKey()).appendEncodedPath(Uri.encode(relative, "/")).build();
            }
        }
        throw new IllegalArgumentException("Failed to find configured root that contains " + path);
    }

    private static File fileFor(Map<String, File> roots, Uri uri) {
        List<String> segments = uri.getPathSegments();
        File root = segments.isEmpty() ? null : roots.get(segments.get(0));
        if (root == null) {
            throw new IllegalArgumentException("Unable to find configured root for " + uri);
        }
        File file = root;
        for (String segment : segments.subList(1, segments.size())) {
            file = new File(file, segment);
        }
        return file;
    }
}
