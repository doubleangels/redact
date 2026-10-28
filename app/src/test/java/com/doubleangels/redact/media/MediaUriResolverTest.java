package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class MediaUriResolverTest {

    private static final String PICKER_PREFIX =
            "content://media/picker/0/com.android.providers.media.photopicker/media/";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void isPhotoPickerUri_detectsPickerPathSegment() {
        assertTrue(MediaUriResolver.isPhotoPickerUri(Uri.parse(PICKER_PREFIX + "42")));
    }

    @Test
    public void isPhotoPickerUri_detectsPickerAuthority() {
        assertTrue(MediaUriResolver.isPhotoPickerUri(
                Uri.parse("content://com.android.providers.media.photopicker/media/42")));
    }

    @Test
    public void isPhotoPickerUri_rejectsNonPickerAndNonContentUris() {
        assertFalse(MediaUriResolver.isPhotoPickerUri(null));
        assertFalse(MediaUriResolver.isPhotoPickerUri(
                Uri.parse("content://media/external/images/media/42")));
        assertFalse(MediaUriResolver.isPhotoPickerUri(Uri.parse("file:///sdcard/picker/a.jpg")));
    }

    @Test
    public void resolveToMediaStoreUri_returnsNonContentUriUnchanged() {
        Uri file = Uri.parse("file:///data/local/tmp/photo.jpg");
        assertEquals(file, MediaUriResolver.resolveToMediaStoreUri(context, file));
    }

    @Test
    public void resolveToMediaStoreUri_keepsDocumentUriWhenMediaStoreLookupFails() {
        Uri document = Uri.parse("content://com.example.docs/document/abc");
        assertEquals(document, MediaUriResolver.resolveToMediaStoreUri(context, document));
    }

    @Test
    public void resolveToMediaStoreUri_keepsPickerUriWhenNoMediaStoreRowMatches() {
        Uri picker = Uri.parse(PICKER_PREFIX + "42");
        assertEquals(picker, MediaUriResolver.resolveToMediaStoreUri(context, picker));
    }

    @Test
    public void resolveToMediaStoreUri_keepsPickerUriWithNonNumericId() {
        Uri picker = Uri.parse(PICKER_PREFIX + "abc");
        assertEquals(picker, MediaUriResolver.resolveToMediaStoreUri(context, picker));
    }

    @Test
    public void resolveForDisplayNameQuery_fallsBackToOriginalUri() {
        Uri document = Uri.parse("content://com.example.docs/document/abc");
        assertEquals(document, MediaUriResolver.resolveForDisplayNameQuery(context, document));
    }

    @Test
    public void readDisplayName_usesLastPathSegmentForFileUri() {
        assertEquals("photo.jpg",
                MediaUriResolver.readDisplayName(context, Uri.parse("file:///data/local/tmp/photo.jpg")));
    }

    @Test
    public void readDisplayName_fallsBackToLastSegmentWhenProviderMissing() {
        assertEquals("clip.mp4", MediaUriResolver.readDisplayName(
                context, Uri.parse("content://com.example.missing/files/clip.mp4")));
    }

    @Test
    public void readDisplayName_fallsBackToLastSegmentWhenQueryIsDenied() {
        Robolectric.setupContentProvider(DenyingProvider.class, "com.example.denied");
        assertEquals("shared.jpg", MediaUriResolver.readDisplayName(
                context, Uri.parse("content://com.example.denied/files/shared.jpg")));
    }

    public static class DenyingProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(@NonNull Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            throw new SecurityException("Permission Denial");
        }

        @Override
        public String getType(@NonNull Uri uri) {
            return null;
        }

        @Override
        public Uri insert(@NonNull Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
            return 0;
        }

        @Override
        public int update(@NonNull Uri uri, ContentValues values, String selection,
                String[] selectionArgs) {
            return 0;
        }
    }
}
