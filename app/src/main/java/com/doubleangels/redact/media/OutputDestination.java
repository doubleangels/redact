package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates and cleans up output files for cleaned/converted media. Uses the folder the user picked
 * with the system file browser (a Storage Access Framework tree) for that media type when one is
 * set and still writable, otherwise the default {@code Pictures/Redact} / {@code Movies/Redact}
 * MediaStore location.
 */
public final class OutputDestination {

    public static final String DEFAULT_FOLDER_NAME = "Redact";

    /** The provider behind the system file browser's device and SD card storage. */
    static final String EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents";
    private static final String PRIMARY_VOLUME = "primary";
    /** Document IDs the Downloads provider gives to plain folders, e.g. {@code raw:/storage/...}. */
    private static final String RAW_PREFIX = "raw:";

    /** Chosen folders already reported as unusable, so a batch warns once rather than per file. */
    private static final Set<String> warnedFallbacks = ConcurrentHashMap.newKeySet();

    private OutputDestination() {
    }

    /** Default MediaStore location shown in Settings, e.g. {@code Pictures/Redact}. */
    @NonNull
    public static String defaultPath(boolean video) {
        return (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES)
                + "/" + DEFAULT_FOLDER_NAME;
    }

    /**
     * Creates an empty output file ready to be written to.
     *
     * @return a MediaStore entry (pending until {@link #publish}) or a document in the chosen folder
     */
    @NonNull
    public static Uri create(
            @NonNull Context context, boolean video, @NonNull String displayName, @NonNull String mime)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri tree = AppPreferences.getOutputTree(context, video);
        if (tree != null) {
            String failure;
            try {
                Uri doc = DocumentsContract.createDocument(resolver, rootDocument(tree), mime, displayName);
                if (doc != null) {
                    return doc;
                }
                failure = "the provider created nothing";
            } catch (Exception e) {
                failure = e.toString();
            }
            // Folder deleted, permission revoked or provider gone: save to the default instead of
            // failing the whole job, and tell the user once so they know where to look.
            SentryManager.log("The chosen output folder could not be used: " + failure + ".");
            warnFallback(context, tree, video);
        }

        ContentValues values = new ContentValues();
        Uri collection;
        if (video) {
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            values.put(MediaStore.Video.Media.RELATIVE_PATH, defaultPath(true));
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        } else {
            values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, defaultPath(false));
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        }
        MediaStoreWrites.markPending(values);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("MediaStore insert failed");
        }
        return uri;
    }

    private static void warnFallback(@NonNull Context context, @NonNull Uri tree, boolean video) {
        if (!warnedFallbacks.add(tree.toString())) {
            return;
        }
        Context app = context.getApplicationContext();
        String message = app.getString(
                R.string.output_folder_fallback, describe(app, tree), defaultPath(video));
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
    }

    /** Lets a newly chosen folder warn again if it, too, stops working. */
    public static void clearFallbackWarnings() {
        warnedFallbacks.clear();
    }

    /** Makes a finished output visible. Documents in a chosen folder need no publish step. */
    public static void publish(@NonNull ContentResolver resolver, @Nullable Uri uri) {
        MediaStoreWrites.markPublished(resolver, uri);
    }

    /** Removes a partially written output created by {@link #create}. */
    public static void discard(@NonNull ContentResolver resolver, @Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            if (isDocumentAuthority(uri)) {
                DocumentsContract.deleteDocument(resolver, uri);
            } else {
                resolver.delete(uri, null, null);
            }
        } catch (Exception e) {
            SentryManager.log("The partial output failed to clean up: " + e.getMessage() + ".");
        }
    }

    /** The document URI for the folder a tree URI grants, which is where new files are created. */
    @NonNull
    static Uri rootDocument(@NonNull Uri tree) {
        return DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    /**
     * Where the folder picker should open: the chosen folder while it is still usable, otherwise
     * {@code Pictures} or {@code Movies}, where the default {@code Redact} folder lives. Without
     * this the picker opens wherever it was last used (often a folder of the wrong media type) or
     * at the top of storage, a folder it will not let the user choose.
     */
    @NonNull
    public static Uri pickerStartLocation(@NonNull Context context, boolean video) {
        Uri tree = AppPreferences.getOutputTree(context, video);
        if (tree != null && isUsable(context, tree)) {
            return rootDocument(tree);
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, PRIMARY_VOLUME + ":"
                + (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES));
    }

    /** True when {@code tree} is the default {@code Pictures/Redact} or {@code Movies/Redact} folder. */
    public static boolean isDefaultFolder(@NonNull Uri tree, boolean video) {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(tree.getAuthority())) {
            return false;
        }
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            return id.equalsIgnoreCase(PRIMARY_VOLUME + ":" + defaultPath(video));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when Redact still holds write access to {@code tree} and the folder still accepts new
     * files. False once the folder is deleted, the grant is revoked or its storage is removed.
     */
    public static boolean isUsable(@NonNull Context context, @NonNull Uri tree) {
        boolean granted = false;
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.getUri().equals(tree) && permission.isWritePermission()) {
                granted = true;
                break;
            }
        }
        return granted && acceptsNewFiles(context, tree);
    }

    /** True when the folder {@code tree} grants exists and allows files to be created in it. */
    public static boolean acceptsNewFiles(@NonNull Context context, @NonNull Uri tree) {
        String[] columns = {
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS};
        try (Cursor cursor = context.getContentResolver().query(rootDocument(tree), columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return false;
            }
            return DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))
                    && (cursor.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when {@code uri} is a file inside a folder the user chose as an output folder, reached
     * through the file browser's storage view (its document ID is a path inside the chosen folder).
     * Files reached through MediaStore are matched by {@link #chosenRelativePaths} instead.
     */
    public static boolean isInChosenFolder(@NonNull Context context, @NonNull Uri uri) {
        if (!isDocumentAuthority(uri)) {
            return false;
        }
        String docId;
        try {
            docId = DocumentsContract.getDocumentId(uri);
        } catch (Exception e) {
            return false;
        }
        for (boolean video : new boolean[] {false, true}) {
            Uri tree = AppPreferences.getOutputTree(context, video);
            if (tree == null || !uri.getAuthority().equals(tree.getAuthority())) {
                continue;
            }
            String treeId;
            try {
                treeId = DocumentsContract.getTreeDocumentId(tree);
            } catch (Exception e) {
                continue;
            }
            String prefix = treeId.endsWith(":") || treeId.endsWith("/") ? treeId : treeId + "/";
            if (docId.equals(treeId) || docId.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The MediaStore relative paths of the chosen output folders on device or SD card storage,
     * such as {@code Pictures/Private}, without a trailing slash. Folders from other providers have
     * no such path and are left out.
     */
    @NonNull
    public static List<String> chosenRelativePaths(@NonNull Context context) {
        List<String> paths = new ArrayList<>();
        for (boolean video : new boolean[] {false, true}) {
            Uri tree = AppPreferences.getOutputTree(context, video);
            if (tree == null || !EXTERNAL_STORAGE_AUTHORITY.equals(tree.getAuthority())) {
                continue;
            }
            try {
                String id = DocumentsContract.getTreeDocumentId(tree);
                String path = trimSlashes(id.substring(id.indexOf(':') + 1));
                if (!path.isEmpty()) {
                    paths.add(path);
                }
            } catch (Exception ignored) {
                // Not a tree URI; nothing to match.
            }
        }
        return paths;
    }

    private static boolean isDocumentAuthority(@NonNull Uri uri) {
        return "content".equals(uri.getScheme())
                && uri.getPathSegments().contains("document");
    }

    /**
     * The chosen folder as Settings shows it: {@code Pictures/Private} on device storage,
     * {@code DCIM/Private (SD card)} on another volume (or just the volume's name for its top
     * level), {@code Download/Private} from the Downloads view, and the folder's name for any other
     * provider.
     */
    @NonNull
    public static String describe(@NonNull Context context, @NonNull Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception e) {
            return tree.toString();
        }
        if (EXTERNAL_STORAGE_AUTHORITY.equals(tree.getAuthority())) {
            int colon = id.indexOf(':');
            if (colon > 0) {
                String volume = id.substring(0, colon);
                String path = trimSlashes(id.substring(colon + 1));
                if (PRIMARY_VOLUME.equalsIgnoreCase(volume)) {
                    return path.isEmpty() ? volumeName(context, null) : path;
                }
                String volumeName = volumeName(context, volume);
                return path.isEmpty() ? volumeName : path + " (" + volumeName + ")";
            }
        }
        if (id.startsWith(RAW_PREFIX)) {
            String path = id.substring(RAW_PREFIX.length());
            String primaryRoot = trimSlashes(Environment.getExternalStorageDirectory().getPath());
            String relative = trimSlashes(path);
            if (relative.startsWith(primaryRoot + "/")) {
                return relative.substring(primaryRoot.length() + 1);
            }
            return path;
        }
        String name = displayName(context, tree);
        return name != null ? name : id;
    }

    /** The user-facing name of a storage volume, e.g. "SD card"; the primary volume when null. */
    @NonNull
    private static String volumeName(@NonNull Context context, @Nullable String uuid) {
        StorageManager storage = context.getSystemService(StorageManager.class);
        if (storage != null) {
            for (StorageVolume volume : storage.getStorageVolumes()) {
                boolean match = uuid == null ? volume.isPrimary() : uuid.equalsIgnoreCase(volume.getUuid());
                if (match) {
                    String description = volume.getDescription(context);
                    if (description != null && !description.isEmpty()) {
                        return description;
                    }
                }
            }
        }
        return uuid != null ? uuid : "/";
    }

    @Nullable
    private static String displayName(@NonNull Context context, @NonNull Uri tree) {
        try (Cursor cursor = context.getContentResolver().query(rootDocument(tree),
                new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                return name == null || name.isEmpty() ? null : name;
            }
        } catch (Exception ignored) {
            // Provider gone or permission revoked; fall back to the ID.
        }
        return null;
    }

    @VisibleForTesting
    @NonNull
    static String trimSlashes(@NonNull String path) {
        int start = 0;
        int end = path.length();
        while (start < end && path.charAt(start) == '/') {
            start++;
        }
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }
}
