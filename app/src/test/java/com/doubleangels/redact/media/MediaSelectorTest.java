package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResultLauncher;

import com.doubleangels.redact.sentry.SentryManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MediaSelectorTest {

    private Activity activity;
    private ContentResolver resolver;
    private ActivityResultLauncher<Intent> launcher;
    private MediaSelector mediaSelector;

    @Before
    public void setUp() {
        activity = mock(Activity.class);
        resolver = mock(ContentResolver.class);
        //noinspection unchecked
        launcher = mock(ActivityResultLauncher.class);

        when(activity.getContentResolver()).thenReturn(resolver);
        mediaSelector = new MediaSelector(activity, launcher);
    }

    @Test
    public void selectMedia_launchesConfiguredOpenDocumentIntent() {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);

        mediaSelector.selectMedia();

        verify(launcher).launch(captor.capture());
        Intent intent = captor.getValue();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertEquals("*/*", intent.getType());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertArrayEquals(
                new String[]{"image/*", "video/*"},
                intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertTrue((intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
    }

    @Test
    public void selectMedia_withoutLauncher_returnsEarly() {
        MediaSelector selector = new MediaSelector(activity, null);
        selector.selectMedia();
        verify(launcher, never()).launch(any());
    }

    @Test
    public void processMediaUri_takesPersistablePermissionAndUsesDisplayName() {
        Uri uri = Uri.parse("content://media/external/video/12");
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{"picked-video.mp4"});
        when(resolver.query(eq(uri), eq(null), eq(null), eq(null), eq(null))).thenReturn(cursor);
        when(resolver.getType(uri)).thenReturn("video/mp4");

        MediaItem item = mediaSelector.processMediaUri(uri);

        assertTrue(item.isVideo());
        assertEquals("picked-video.mp4", item.fileName());
        assertEquals(uri, item.uri());
        verify(resolver)
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    @Test
    public void processMediaUri_recordsPermissionFailureAndFallsBackToLastPathSegment() {
        Uri uri = Uri.parse("content://media/external/images/22");
        RuntimeException permissionFailure = new RuntimeException("permission boom");
        RuntimeException queryFailure = new RuntimeException("query boom");
        when(resolver.getType(uri)).thenReturn(null);
        when(resolver.query(eq(uri), eq(null), eq(null), eq(null), eq(null)))
                .thenThrow(queryFailure);
        doThrow(permissionFailure)
                .when(resolver)
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try (MockedStatic<SentryManager> sentry = mockStatic(SentryManager.class)) {
            MediaItem item = mediaSelector.processMediaUri(uri);

            assertFalse(item.isVideo());
            assertEquals("22", item.fileName());
            sentry.verify(() -> SentryManager.recordException(permissionFailure));
            sentry.verify(() -> SentryManager.recordException(queryFailure));
        }
    }

    @Test
    public void getFileName_usesCursorValueForContentUris() {
        Uri uri = Uri.parse("content://media/external/images/5");
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        cursor.addRow(new Object[]{"holiday.jpg"});
        when(resolver.query(eq(uri), eq(null), eq(null), eq(null), eq(null))).thenReturn(cursor);

        assertEquals("holiday.jpg", mediaSelector.getFileName(uri));
    }

    @Test
    public void getFileName_fallsBackToLastPathSegmentOrMedia() {
        Uri contentUri = Uri.parse("content://media/external/images/path-segment");
        Uri fileUri = Uri.parse("file:///storage/emulated/0/DCIM/direct-file.jpg");
        MatrixCursor missingColumnCursor = new MatrixCursor(new String[]{"other"});
        missingColumnCursor.addRow(new Object[]{"ignored"});
        when(resolver.query(eq(contentUri), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(missingColumnCursor);

        assertEquals("path-segment", mediaSelector.getFileName(contentUri));
        assertEquals("direct-file.jpg", mediaSelector.getFileName(fileUri));
        assertEquals("media", mediaSelector.getFileName(Uri.parse("file:///")));
    }

    @Test
    public void processMediaResult_handlesSingleMultipleAndEmptySelections() {
        Uri singleUri = Uri.parse("content://media/external/video/single");
        Uri imageUri = Uri.parse("content://media/external/images/first");
        Uri videoUri = Uri.parse("content://media/external/video/second");

        MatrixCursor singleCursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        singleCursor.addRow(new Object[]{"single.mp4"});
        MatrixCursor imageCursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        imageCursor.addRow(new Object[]{"image.jpg"});
        MatrixCursor videoCursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME});
        videoCursor.addRow(new Object[]{"video.webm"});

        when(resolver.query(eq(singleUri), eq(null), eq(null), eq(null), eq(null))).thenReturn(singleCursor);
        when(resolver.query(eq(imageUri), eq(null), eq(null), eq(null), eq(null))).thenReturn(imageCursor);
        when(resolver.query(eq(videoUri), eq(null), eq(null), eq(null), eq(null))).thenReturn(videoCursor);
        when(resolver.getType(singleUri)).thenReturn("video/mp4");
        when(resolver.getType(imageUri)).thenReturn("image/jpeg");
        when(resolver.getType(videoUri)).thenReturn("video/webm");

        Intent singleIntent = new Intent();
        singleIntent.setData(singleUri);
        List<MediaItem> singleItems = mediaSelector.processMediaResult(singleIntent);
        assertEquals(1, singleItems.size());
        assertTrue(singleItems.get(0).isVideo());
        assertEquals("single.mp4", singleItems.get(0).fileName());

        Intent multipleIntent = new Intent();
        ClipData multiple =
                new ClipData(
                        new android.content.ClipDescription("picked", new String[]{"image/*"}),
                        new ClipData.Item(imageUri));
        multiple.addItem(new ClipData.Item(videoUri));
        multipleIntent.setClipData(multiple);
        List<MediaItem> multipleItems = mediaSelector.processMediaResult(multipleIntent);
        assertEquals(2, multipleItems.size());
        assertFalse(multipleItems.get(0).isVideo());
        assertTrue(multipleItems.get(1).isVideo());

        assertTrue(mediaSelector.processMediaResult(new Intent()).isEmpty());
    }
}
