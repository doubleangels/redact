package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

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

    // ---- isOwnProvider -----------------------------------------------------------------------

    private static ProviderInfo provider(String packageName, String authority) {
        ProviderInfo info = new ProviderInfo();
        info.packageName = packageName;
        info.name = packageName + ".Provider";
        info.authority = authority;
        return info;
    }

    @Test
    public void isOwnProvider_dropsAUserIdPrefixAsAndroidDoes() {
        String fileProvider = context.getPackageName() + ".fileprovider";
        assertTrue(MediaUriResolver.isOwnProvider(context, Uri.parse("content://" + fileProvider + "/processed/a.jpg")));
        assertTrue(MediaUriResolver.isOwnProvider(context, Uri.parse("content://0@" + fileProvider + "/processed/a.jpg")));
        assertTrue(MediaUriResolver.isOwnProvider(context, Uri.parse("content://10@" + fileProvider + "/processed/a.jpg")));
    }

    @Test
    public void isOwnProvider_asksTheSystemWhoOwnsTheAuthority() {
        Shadows.shadowOf(context.getPackageManager())
                .addOrUpdateProvider(provider(context.getPackageName(), "media.cache.internal"));
        assertTrue(MediaUriResolver.isOwnProvider(context, Uri.parse("content://0@media.cache.internal/x")));

        PackageInfo other = new PackageInfo();
        other.packageName = "com.example.gallery";
        Shadows.shadowOf(context.getPackageManager()).installPackage(other);
        Shadows.shadowOf(context.getPackageManager())
                .addOrUpdateProvider(provider("com.example.gallery", "com.example.gallery.files"));
        assertFalse(MediaUriResolver.isOwnProvider(context, Uri.parse("content://com.example.gallery.files/1")));
        assertFalse(MediaUriResolver.isOwnProvider(context, Uri.parse("content://0@com.example.gallery.files/1")));
    }

    @Test
    public void isOwnProvider_acceptsAProviderTheSystemCannotName() {
        assertFalse(MediaUriResolver.isOwnProvider(context,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3Aa.jpg")));
    }

    @Test
    public void isOwnProvider_refusesAUriWithNoAuthority() {
        assertTrue(MediaUriResolver.isOwnProvider(context, Uri.parse("content:///x")));
        assertTrue(MediaUriResolver.isOwnProvider(context, Uri.parse("content://0@/x")));
        assertTrue(MediaUriResolver.isOwnProvider(context, new Uri.Builder().scheme("content").build()));
    }

    @Test
    public void isOwnProvider_refusesWhenTheLookupFails() {
        Context broken = new ContextWrapper(context) {
            @Override
            public PackageManager getPackageManager() {
                throw new IllegalStateException("package manager unavailable");
            }
        };
        assertTrue(MediaUriResolver.isOwnProvider(broken, Uri.parse("content://com.example.gallery.files/1")));
    }
}
