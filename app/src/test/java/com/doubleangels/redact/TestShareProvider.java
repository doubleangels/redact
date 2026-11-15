package com.doubleangels.redact;

import static org.robolectric.Shadows.shadowOf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.robolectric.Robolectric;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

/**
 * Another app's content provider, as a share sends it: files served over content://, named
 * through {@link OpenableColumns}, and known to the package manager as another package's, as a
 * sender's provider is. Redact refuses file:// shares, so tests hand their fixture files over
 * through this. Like a file:// URI before, it reports no MIME type of its own unless one is set.
 */
public class TestShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.test.share";
    public static final String PACKAGE = "com.example.test";

    private static final List<File> FILES = new ArrayList<>();
    @Nullable
    static String type;

    /** Registers the provider for this test; call from {@code @Before}. */
    public static void install(@NonNull Context context) {
        FILES.clear();
        type = null;
        Robolectric.setupContentProvider(TestShareProvider.class, AUTHORITY);
        register(context, AUTHORITY);
    }

    /**
     * Tells the package manager {@code authority} belongs to another app, as it does for a real
     * sender's provider; a provider it can't name is refused.
     */
    public static void register(@NonNull Context context, @NonNull String authority) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            // Installed once: installing it again would drop the providers already added.
            PackageInfo sender = new PackageInfo();
            sender.packageName = PACKAGE;
            shadowOf(context.getPackageManager()).installPackage(sender);
        }
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;
        info.packageName = PACKAGE;
        info.name = PACKAGE + ".Provider" + Math.abs(authority.hashCode());
        info.exported = true;
        shadowOf(context.getPackageManager()).addOrUpdateProvider(info);
    }

    /** A content:// URI that serves {@code file}, which need not exist. */
    @NonNull
    public static Uri share(@NonNull File file) {
        FILES.add(file);
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(String.valueOf(FILES.size() - 1)).appendPath(file.getName()).build();
    }

    /** The MIME type every item reports; null (the default) for none. */
    public static void setType(@Nullable String mimeType) {
        type = mimeType;
    }

    @Nullable
    private static File fileFor(@NonNull Uri uri) {
        List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) {
            return null;
        }
        try {
            int index = Integer.parseInt(segments.get(0));
            return index >= 0 && index < FILES.size() ? FILES.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file = fileFor(uri);
        if (file == null) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.exists() ? file.length() : null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(@NonNull Uri uri) {
        return type;
    }

    @Override
    public Uri insert(@NonNull Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        if (file == null) {
            throw new FileNotFoundException("No such item");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
