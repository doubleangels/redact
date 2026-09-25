package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.app.Application;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShareHandlerActivityTest {

    private static final long PROCESSING_TIMEOUT_MS = 20_000L;

    private Application app;
    private ActivityController<ShareHandlerActivity> controller;
    private final List<File> sourceFiles = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setShareConfirmBeforeStrip(app, false);
        deleteInboundSnapshots();
        clearFileProviderCache();
    }

    /**
     * FileProvider caches its path roots statically, but Robolectric gives each test a new
     * data directory, so a cached strategy from an earlier test rejects this test's files.
     */
    private static void clearFileProviderCache() {
        try {
            java.lang.reflect.Field cache =
                    androidx.core.content.FileProvider.class.getDeclaredField("sCache");
            cache.setAccessible(true);
            Object map = cache.get(null);
            synchronized (map) {
                ((java.util.Map<?, ?>) map).clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not reset FileProvider cache", e);
        }
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        for (File file : sourceFiles) {
            file.delete();
        }
        deleteInboundSnapshots();
    }

    private ShareHandlerActivity launch(Intent intent) {
        controller = Robolectric.buildActivity(ShareHandlerActivity.class, intent).setup();
        return controller.get();
    }

    private static Intent sendIntent(String type, Uri stream) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(type);
        if (stream != null) {
            intent.putExtra(Intent.EXTRA_STREAM, stream);
        }
        return intent;
    }

    private static Intent sendMultipleIntent(String type, ArrayList<Uri> streams) {
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType(type);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streams);
        return intent;
    }

    private Uri createJpeg() throws IOException {
        File jpeg = File.createTempFile("share_src_", ".jpg", app.getFilesDir());
        sourceFiles.add(jpeg);
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.BLUE);
        try (FileOutputStream fos = new FileOutputStream(jpeg)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos));
        }
        return Uri.fromFile(jpeg);
    }

    private File[] inboundSnapshots() {
        File[] files = app.getCacheDir().listFiles((dir, name) -> name.startsWith("inbound_"));
        return files != null ? files : new File[0];
    }

    private void deleteInboundSnapshots() {
        for (File file : inboundSnapshots()) {
            file.delete();
        }
    }

    /**
     * FileProvider matches roots with a hard-coded '/' separator, so producing the cleaned
     * content URI only works under Robolectric on POSIX hosts (CI runs on Linux).
     */
    private static void assumeFileProviderUsable() {
        assumeFalse("FileProvider path matching fails on Windows hosts",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"));
    }

    /** Error-level logcat captured during the test, to make CI failures diagnosable. */
    private static String loggedErrors() {
        StringBuilder sb = new StringBuilder();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.type >= android.util.Log.WARN) {
                sb.append(System.lineSeparator()).append(item.tag).append(": ").append(item.msg);
                if (item.throwable != null) {
                    sb.append(" -> ").append(item.throwable);
                }
            }
        }
        return sb.toString();
    }

    private void assertFinishedWithError(ShareHandlerActivity activity, int messageRes) {
        assertEquals(app.getString(messageRes), ShadowToast.getTextOfLatestToast());
        assertTrue(activity.isFinishing());
    }

    private Intent awaitStartedActivity(ShareHandlerActivity activity) throws InterruptedException {
        long deadline = System.currentTimeMillis() + PROCESSING_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Intent started = Shadows.shadowOf(activity).peekNextStartedActivityForResult() != null
                    ? Shadows.shadowOf(activity).getNextStartedActivityForResult().intent
                    : null;
            if (started != null || activity.isFinishing()) {
                return started;
            }
            Thread.sleep(25);
        }
        return null;
    }

    @Test
    public void unsupportedAction_finishesWithError() {
        ShareHandlerActivity activity = launch(new Intent(Intent.ACTION_VIEW));
        assertFinishedWithError(activity, R.string.share_error_unsupported_action);
    }

    @Test
    public void send_unsupportedMimeType_finishesWithError() {
        ShareHandlerActivity activity = launch(sendIntent("text/plain", Uri.parse("content://x/y")));
        assertFinishedWithError(activity, R.string.share_error_unsupported_media);
    }

    @Test
    public void sendMultiple_unsupportedMimeType_finishesWithError() {
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://x/y"));
        ShareHandlerActivity activity = launch(sendMultipleIntent("application/pdf", uris));
        assertFinishedWithError(activity, R.string.share_error_unsupported_media);
    }

    @Test
    public void send_withoutStream_finishesWithReceiveError() {
        ShareHandlerActivity activity = launch(sendIntent("image/jpeg", null));
        assertFinishedWithError(activity, R.string.share_error_failed_receive_media);
    }

    @Test
    public void send_withUnsupportedScheme_finishesWithReceiveError() {
        ShareHandlerActivity activity =
                launch(sendIntent("image/jpeg", Uri.parse("https://example.com/a.jpg")));
        assertFinishedWithError(activity, R.string.share_error_failed_receive_media);
    }

    @Test
    public void sendMultiple_emptyList_finishesWithReceiveError() {
        ShareHandlerActivity activity = launch(sendMultipleIntent("image/*", new ArrayList<>()));
        assertFinishedWithError(activity, R.string.share_error_failed_receive_media);
    }

    @Test
    public void sendMultiple_tooManyItems_finishesWithLimitError() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            uris.add(Uri.parse("content://com.example/media/" + i));
        }
        ShareHandlerActivity activity = launch(sendMultipleIntent("image/*", uris));
        assertEquals(app.getString(R.string.share_error_too_many_items, 20),
                ShadowToast.getTextOfLatestToast());
        assertTrue(activity.isFinishing());
        assertEquals(0, inboundSnapshots().length);
    }

    @Test
    public void send_withConfirmEnabled_snapshotsInputAndShowsConfirmDialog() throws IOException {
        AppPreferences.setShareConfirmBeforeStrip(app, true);

        ShareHandlerActivity activity = launch(sendIntent("image/jpeg", createJpeg()));

        assertFalse(activity.isFinishing());
        assertEquals(1, inboundSnapshots().length);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        assertFalse(ShareHandlerActivity.isShareProcessingActive());
    }

    @Test
    public void send_deduplicatesStreamAndClipDataUris() throws IOException {
        AppPreferences.setShareConfirmBeforeStrip(app, true);
        Uri jpeg = createJpeg();
        Intent intent = sendIntent("image/jpeg", jpeg);
        intent.setClipData(ClipData.newRawUri("photo", jpeg));

        launch(intent);

        assertEquals(1, inboundSnapshots().length);
    }

    @Test
    public void sendMultiple_snapshotsEachDistinctItem() throws IOException {
        AppPreferences.setShareConfirmBeforeStrip(app, true);
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(createJpeg());
        uris.add(createJpeg());
        uris.add(Uri.parse("https://example.com/ignored.jpg"));

        launch(sendMultipleIntent("image/*", uris));

        assertEquals(2, inboundSnapshots().length);
    }

    @Test
    public void confirmDialogCancel_finishesAndDeletesSnapshots() throws IOException {
        AppPreferences.setShareConfirmBeforeStrip(app, true);
        ShareHandlerActivity activity = launch(sendIntent("image/jpeg", createJpeg()));
        assertEquals(1, inboundSnapshots().length);

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(activity.isFinishing());
        controller.pause().stop().destroy();
        controller = null;
        assertEquals(0, inboundSnapshots().length);
    }

    @Test
    public void send_image_stripsAndLaunchesShareChooser() throws Exception {
        assumeFileProviderUsable();
        ShareHandlerActivity activity = launch(sendIntent("image/jpeg", createJpeg()));

        Intent chooser = awaitStartedActivity(activity);

        assertNotNull("Expected share chooser; toast was: " + ShadowToast.getTextOfLatestToast()
                        + loggedErrors(),
                chooser);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent share = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        assertNotNull(share);
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals("image/*", share.getType());
        assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        Uri cleaned = share.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        assertNotNull(cleaned);
        assertEquals("content", cleaned.getScheme());
        assertFalse(ShareHandlerActivity.isShareProcessingActive());
    }

    @Test
    public void sendMultiple_images_launchesMultipleShareChooser() throws Exception {
        assumeFileProviderUsable();
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(createJpeg());
        uris.add(createJpeg());
        ShareHandlerActivity activity = launch(sendMultipleIntent("image/*", uris));

        Intent chooser = awaitStartedActivity(activity);

        assertNotNull("Expected share chooser; toast was: " + ShadowToast.getTextOfLatestToast()
                        + loggedErrors(),
                chooser);
        Intent share = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        assertNotNull(share);
        assertEquals(Intent.ACTION_SEND_MULTIPLE, share.getAction());
        assertEquals("image/*", share.getType());
        ArrayList<Uri> cleaned = share.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        assertNotNull(cleaned);
        assertEquals(2, cleaned.size());
        assertEquals(2, share.getClipData().getItemCount());
    }

    @Test
    public void send_undecodableImage_finishesWithProcessingError() throws Exception {
        File garbage = File.createTempFile("share_bad_", ".jpg", app.getFilesDir());
        sourceFiles.add(garbage);
        try (FileOutputStream fos = new FileOutputStream(garbage)) {
            fos.write("definitely not a jpeg".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        ShareHandlerActivity activity = launch(sendIntent("image/jpeg", Uri.fromFile(garbage)));

        Intent started = awaitStartedActivity(activity);

        assertNull("Unexpected share chooser" + loggedErrors(), started);
        assertFinishedWithError(activity, R.string.share_error_processing_failed);
        assertFalse(ShareHandlerActivity.isShareProcessingActive());
    }

    @Test
    public void restoredWhileProcessing_finishesWithInterruptedError() {
        Bundle saved = new Bundle();
        saved.putBoolean("processing_active", true);
        controller = Robolectric.buildActivity(ShareHandlerActivity.class,
                sendIntent("image/jpeg", Uri.parse("content://x/y")));
        controller.create(saved);

        assertFinishedWithError(controller.get(), R.string.share_error_interrupted);
    }

    @Test
    public void restoredAfterShareLaunched_finishesSilently() {
        ShadowToast.reset();
        Bundle saved = new Bundle();
        saved.putBoolean("sharing_initiated", true);
        controller = Robolectric.buildActivity(ShareHandlerActivity.class,
                sendIntent("image/jpeg", Uri.parse("content://x/y")));
        controller.create(saved);

        assertTrue(controller.get().isFinishing());
        assertNull(ShadowToast.getTextOfLatestToast());
    }
}
