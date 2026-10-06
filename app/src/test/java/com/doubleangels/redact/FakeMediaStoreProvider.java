package com.doubleangels.redact;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.robolectric.Robolectric;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal in-memory stand-in for the {@code media} content provider so code that inserts into
 * {@link MediaStore} can run under Robolectric. Every row is backed by a file, readable and
 * writable through {@code openFile}.
 */
public class FakeMediaStoreProvider extends ContentProvider {

    public static final class Entry {
        public final long id;
        public final File file;
        public final ContentValues values;

        Entry(long id, File file, ContentValues values) {
            this.id = id;
            this.file = file;
            this.values = values;
        }

        public String displayName() {
            return values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME);
        }

        public String mimeType() {
            return values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
        }

        public boolean isPending() {
            Integer pending = values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
            return pending != null && pending == 1;
        }
    }

    private static final Map<Long, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static volatile File directory;
    /** When true, {@code insert} returns null, like a MediaStore that refuses the row. */
    public static volatile boolean failInsert;
    /** When true, opening a row's file fails, like a MediaStore row whose backing file is gone. */
    public static volatile boolean failOpen;

    /** Registers the provider for the {@code media} authority and clears earlier rows. */
    public static void install(Context context) {
        ENTRIES.clear();
        failInsert = false;
        failOpen = false;
        directory = new File(context.getCacheDir(), "fake_media_store");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        Robolectric.setupContentProvider(FakeMediaStoreProvider.class, "media");
    }

    public static void reset() {
        ENTRIES.clear();
        failInsert = false;
        failOpen = false;
    }

    public static Map<Long, Entry> entries() {
        return ENTRIES;
    }

    public static Entry only() {
        if (ENTRIES.size() != 1) {
            throw new AssertionError("Expected exactly one MediaStore row, found " + ENTRIES.size());
        }
        return ENTRIES.values().iterator().next();
    }

    /** Adds a row backed by {@code file} and returns its content URI under {@code collection}. */
    public static Uri add(Uri collection, File file, String mimeType) {
        long id = NEXT_ID.getAndIncrement();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, file.getName());
        ENTRIES.put(id, new Entry(id, file, values));
        return ContentUris.withAppendedId(collection, id);
    }

    private static Entry entryFor(Uri uri) {
        try {
            return ENTRIES.get(ContentUris.parseId(uri));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (failInsert) {
            return null;
        }
        long id = NEXT_ID.getAndIncrement();
        File file = new File(directory, "row_" + id);
        try {
            //noinspection ResultOfMethodCallIgnored
            file.createNewFile();
        } catch (IOException e) {
            return null;
        }
        ENTRIES.put(id, new Entry(id, file, new ContentValues(values)));
        return ContentUris.withAppendedId(uri, id);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Entry entry = entryFor(uri);
        if (entry == null || failOpen) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        Entry entry = entryFor(uri);
        if (entry == null) {
            return 0;
        }
        entry.values.putAll(values);
        return 1;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        Entry removed;
        try {
            removed = ENTRIES.remove(ContentUris.parseId(uri));
        } catch (RuntimeException e) {
            return 0;
        }
        if (removed == null) {
            return 0;
        }
        //noinspection ResultOfMethodCallIgnored
        removed.file.delete();
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        Entry entry = entryFor(uri);
        return entry == null ? null : entry.mimeType();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Entry entry = entryFor(uri);
        String[] columns = projection != null ? projection
                : new String[] {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        if (entry != null) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case MediaStore.MediaColumns._ID -> row[i] = entry.id;
                    case MediaStore.MediaColumns.SIZE -> row[i] = entry.file.length();
                    default -> row[i] = entry.values.get(columns[i]);
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }
}
