package com.doubleangels.redact.media;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.sentry.SentryManager;

import java.io.IOException;

/**
 * Creates and cleans up output files for cleaned/converted media. Uses the folder the user picked
 * with the system file browser (a Storage Access Framework tree) for that media type when one is
 * set and still writable, otherwise the default {@code Pictures/Redact} / {@code Movies/Redact}
 * MediaStore location.
 */
public final class OutputDestination {

    public static final String DEFAULT_FOLDER_NAME = "Redact";

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
            try {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                Uri doc = DocumentsContract.createDocument(resolver, parent, mime, displayName);
                if (doc != null) {
                    return doc;
                }
            } catch (Exception e) {
                // Folder deleted, permission revoked or provider gone: fall back to the default
                // rather than failing the whole job.
                SentryManager.log("The chosen output folder could not be used: " + e + ".");
            }
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

    /** True when {@code uri} is a file inside a folder the user chose as an output folder. */
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
            if (tree == null || !tree.getAuthority().equals(uri.getAuthority())) {
                continue;
            }
            String treeId = DocumentsContract.getTreeDocumentId(tree);
            if (docId.equals(treeId) || docId.startsWith(treeId + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDocumentAuthority(@NonNull Uri uri) {
        return "content".equals(uri.getScheme())
                && uri.getPathSegments().contains("document");
    }

    /** Human-readable folder label such as {@code Pictures/Private}, for Settings. */
    @NonNull
    public static String describe(@NonNull Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return path.isEmpty() ? "/" : path;
        } catch (Exception e) {
            return tree.toString();
        }
    }
}
