package com.doubleangels.redact;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import org.robolectric.Robolectric;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Minimal stand-in for the system file browser's storage provider
 * ({@code com.android.externalstorage.documents}) so folder-picker output can run under
 * Robolectric. Document IDs look like the real ones ({@code primary:Pictures/Private}) and map to
 * folders under the app's cache directory.
 */
public class FakeDocumentsProvider extends ContentProvider {

    public static final String AUTHORITY = "com.android.externalstorage.documents";

    private static volatile File root;
    /** When true, folders refuse new files, like a read-only provider. */
    public static volatile boolean readOnly;

    /** Registers the provider and starts from empty storage. */
    public static void install(Context context) {
        root = new File(context.getCacheDir(), "fake_documents");
        deleteRecursively(root);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        readOnly = false;
        Robolectric.setupContentProvider(FakeDocumentsProvider.class, AUTHORITY);
    }

    /** Creates the folder for {@code docId} and returns the tree URI the picker would hand back. */
    public static Uri folder(String docId) {
        //noinspection ResultOfMethodCallIgnored
        fileFor(docId).mkdirs();
        return tree(docId);
    }

    public static Uri tree(String docId) {
        return DocumentsContract.buildTreeDocumentUri(AUTHORITY, docId);
    }

    public static File fileFor(String docId) {
        return new File(root, docId.substring(docId.indexOf(':') + 1));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        String docId = DocumentsContract.getDocumentId(uri);
        File file = fileFor(docId);
        MatrixCursor cursor = new MatrixCursor(projection);
        if (!file.exists()) {
            return cursor;
        }
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            switch (projection[i]) {
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row[i] = file.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "image/jpeg";
                    break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    row[i] = file.isDirectory() && !readOnly
                            ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0;
                    break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:
                    row[i] = file.getName();
                    break;
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:
                    row[i] = docId;
                    break;
                default:
                    row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Uri target = extras.getParcelable("uri");
        String docId = DocumentsContract.getDocumentId(target);
        if ("android:createDocument".equals(method)) {
            File dir = fileFor(docId);
            if (readOnly || !dir.isDirectory()) {
                throw new IllegalStateException("Cannot create in " + docId);
            }
            String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            try {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, name).createNewFile();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            String childId = docId + "/" + name;
            Uri child = DocumentsContract.isTreeUri(target)
                    ? DocumentsContract.buildDocumentUriUsingTree(target, childId)
                    : DocumentsContract.buildDocumentUri(AUTHORITY, childId);
            Bundle out = new Bundle();
            out.putParcelable("uri", child);
            return out;
        }
        if ("android:deleteDocument".equals(method)) {
            //noinspection ResultOfMethodCallIgnored
            fileFor(docId).delete();
            return new Bundle();
        }
        throw new UnsupportedOperationException(method);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(DocumentsContract.getDocumentId(uri)),
                ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
