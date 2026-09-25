package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
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
}
