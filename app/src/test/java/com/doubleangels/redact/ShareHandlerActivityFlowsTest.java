package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.media.AppProcessingScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Share-sheet flows that do not need a FileProvider URI, so they also run on Windows hosts: new
 * intents, state restore, cancel, chooser results, delayed cleanup and content-URI snapshots.
 */
@SuppressWarnings("deprecation")
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ShareHandlerActivityFlowsTest {

    /** Serves one JPEG (or video-typed file) over content:// for the inbound snapshot path. */
    public static class TestMediaProvider extends ContentProvider {
        static File file;
        static String type = "image/jpeg";

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            return null;
        }

        @Override
        public String getType(Uri uri) {
            return type;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    private static final String AUTHORITY = "com.doubleangels.redact.test.media";

    private Application app;
    private ActivityController<ShareHandlerActivity> controller;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setShareConfirmBeforeStrip(app, true);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            try {
                controller.pause().stop().destroy();
            } catch (RuntimeException ignored) {
                // already destroyed by the test
            }
        }
        for (File f : files) {
            f.delete();
        }
        File[] inbound = app.getCacheDir().listFiles((d, n) -> n.startsWith("inbound_"));
        if (inbound != null) {
            for (File f : inbound) {
                f.delete();
            }
        }
        AppProcessingScope.resetForTests();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ShareHandlerActivity launch(Intent intent) {
        controller = Robolectric.buildActivity(ShareHandlerActivity.class, intent).setup();
        return controller.get();
    }

    private static Intent send(String type, Uri stream) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_STREAM, stream);
        return intent;
    }

    private File jpeg() throws IOException {
        File f = File.createTempFile("flow_", ".jpg", app.getFilesDir());
        files.add(f);
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        return f;
    }

    private File[] inboundSnapshots() {
        File[] found = app.getCacheDir().listFiles((d, n) -> n.startsWith("inbound_"));
        return found != null ? found : new File[0];
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            idle();
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean());
    }

    // ---- onNewIntent --------------------------------------------------------------------------

    @Test
    public void newIntent_replacesTheShareInFlight() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        assertEquals(1, inboundSnapshots().length);

        controller.newIntent(send("image/jpeg", Uri.fromFile(jpeg())));
        idle();

        assertFalse(activity.isFinishing());
        assertNotNull(ShadowDialog.getLatestDialog());
        awaitTrue(() -> inboundSnapshots().length == 1);
        assertFalse(ShareHandlerActivity.isShareProcessingActive());
    }

    @Test
    public void newIntent_withUnsupportedAction_finishesWithAnError() {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(new File("/nope/x.jpg"))));

        controller.newIntent(new Intent(Intent.ACTION_VIEW));
        idle();

        assertTrue(activity.isFinishing());
        assertEquals(app.getString(R.string.share_error_unsupported_action), ShadowToast.getTextOfLatestToast());
    }

    // ---- state restore -------------------------------------------------------------------------

    @Test
    public void restoringWhileProcessingWasActive_finishesWithTheInterruptedError() {
        Bundle state = new Bundle();
        state.putBoolean("processing_active", true);
        controller = Robolectric.buildActivity(ShareHandlerActivity.class, send("image/jpeg", Uri.parse("content://x/y")));
        controller.create(state);

        assertEquals(app.getString(R.string.share_error_interrupted), ShadowToast.getTextOfLatestToast());
        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void restoringAfterTheChooserWasShown_justFinishes() {
        Bundle state = new Bundle();
        state.putBoolean("sharing_initiated", true);
        controller = Robolectric.buildActivity(ShareHandlerActivity.class, send("image/jpeg", Uri.parse("content://x/y")));
        controller.create(state);

        assertTrue(controller.get().isFinishing());
    }

    @Test
    public void saveInstanceState_recordsTheSharingFlags() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        setField(activity, "sharingInitiated", true);
        setField(activity, "processingActive", true);

        Bundle out = new Bundle();
        controller.saveInstanceState(out);

        assertTrue(out.getBoolean("sharing_initiated"));
        assertTrue(out.getBoolean("processing_active"));
    }

    // ---- confirm / cancel ---------------------------------------------------------------------

    @Test
    public void confirming_startsProcessing_andCancellingTheProgressDialogStopsIt() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        AlertDialog progress = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(progress);
        progress.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();

        assertTrue(activity.isFinishing());
        assertEquals(app.getString(R.string.status_processing_cancelled), ShadowToast.getTextOfLatestToast());
        awaitTrue(() -> !ShareHandlerActivity.isShareProcessingActive());
    }

    @Test
    public void cancelShareProcessing_cleansUpAndFinishes() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));

        call(activity, "cancelShareProcessing", new Class<?>[0]);
        idle();

        assertTrue(activity.isFinishing());
        awaitTrue(() -> inboundSnapshots().length == 0);
    }

    // ---- building the share intents ---------------------------------------------------------------

    @Test
    public void shareCleanFile_buildsAChooserForASingleImageOrVideo() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        Uri cleaned = Uri.fromFile(new File("/cache/processed/abc123.jpg"));

        call(activity, "shareCleanFile", new Class<?>[] {boolean.class, Uri.class}, false, cleaned);
        ShadowActivity.IntentForResult launched = shadowOf(activity).peekNextStartedActivityForResult();
        assertNotNull(launched);
        assertEquals(Intent.ACTION_CHOOSER, launched.intent.getAction());
        Intent inner = launched.intent.<Intent>getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, inner.getAction());
        assertEquals("image/*", inner.getType());
        assertEquals(cleaned, inner.<Uri>getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue((inner.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);

        shadowOf(activity).getNextStartedActivityForResult();
        call(activity, "shareCleanFile", new Class<?>[] {boolean.class, Uri.class}, true, cleaned);
        Intent video = shadowOf(activity).peekNextStartedActivityForResult().intent
                .<Intent>getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals("video/*", video.getType());
    }

    @Test
    public void shareCleanFile_withoutAFile_finishesWithAnError() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));

        call(activity, "shareCleanFile", new Class<?>[] {boolean.class, Uri.class}, false, null);

        assertEquals(app.getString(R.string.share_error_failed_get_cleaned_file),
                ShadowToast.getTextOfLatestToast());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void shareCleanFiles_picksTheMimeTypeFromTheContent() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.fromFile(new File("/cache/processed/a.jpg")));
        uris.add(Uri.fromFile(new File("/cache/processed/b.jpg")));
        Class<?>[] types = {boolean.class, boolean.class, ArrayList.class};

        boolean[][] cases = {{false, true}, {true, false}, {true, true}};
        String[] expected = {"image/*", "video/*", "*/*"};
        for (int i = 0; i < cases.length; i++) {
            call(activity, "shareCleanFiles", types, cases[i][0], cases[i][1], uris);
            ShadowActivity.IntentForResult launched = shadowOf(activity).getNextStartedActivityForResult();
            assertNotNull(launched);
            Intent inner = launched.intent.<Intent>getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals(Intent.ACTION_SEND_MULTIPLE, inner.getAction());
            assertEquals(expected[i], inner.getType());
            assertEquals(2, inner.<Uri>getParcelableArrayListExtra(Intent.EXTRA_STREAM).size());
            assertEquals(2, inner.getClipData().getItemCount());
        }
    }

    @Test
    public void shareCleanFiles_withNothing_finishesWithAnError() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));

        call(activity, "shareCleanFiles", new Class<?>[] {boolean.class, boolean.class, ArrayList.class},
                false, true, new ArrayList<Uri>());

        assertEquals(app.getString(R.string.share_error_failed_get_cleaned_files),
                ShadowToast.getTextOfLatestToast());
        assertTrue(activity.isFinishing());
    }

    // ---- chooser results + delayed cleanup -----------------------------------------------------------

    private File addProcessedFile(ShareHandlerActivity activity) throws Exception {
        File dir = new File(app.getCacheDir(), "processed");
        dir.mkdirs();
        File processed = new File(dir, "shared_" + System.nanoTime() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(processed)) {
            out.write(new byte[64]);
        }
        files.add(processed);
        List<File> list = field(activity, "processedFiles");
        list.add(processed);
        List<String> names = field(activity, "processedDisplayNames");
        names.add(processed.getName());
        return processed;
    }

    @Test
    public void chooserCancelled_deletesProcessedFilesAtOnce() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        File processed = addProcessedFile(activity);
        Uri cleaned = Uri.fromFile(processed);
        call(activity, "shareCleanFile", new Class<?>[] {boolean.class, Uri.class}, false, cleaned);
        ShadowActivity shadow = shadowOf(activity);
        ShadowActivity.IntentForResult launched = shadow.peekNextStartedActivityForResult();

        shadow.receiveResult(launched.intent, Activity.RESULT_CANCELED, null);
        idle();

        assertTrue(activity.isFinishing());
        awaitTrue(() -> !processed.exists());
    }

    @Test
    public void chooserCompleted_deletesProcessedFilesAfterTheGracePeriod() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        File processed = addProcessedFile(activity);
        call(activity, "shareCleanFile", new Class<?>[] {boolean.class, Uri.class}, false, Uri.fromFile(processed));
        ShadowActivity shadow = shadowOf(activity);
        ShadowActivity.IntentForResult launched = shadow.peekNextStartedActivityForResult();

        shadow.receiveResult(launched.intent, Activity.RESULT_OK, new Intent());
        idle();
        assertTrue("Files stay until the target app has read them", processed.exists());
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.MINUTES);

        awaitTrue(() -> !processed.exists());
        assertTrue(activity.isFinishing());
    }

    @Test
    public void destroyingAfterSharing_schedulesTheDelayedCleanup() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        File processed = addProcessedFile(activity);
        setField(activity, "sharingInitiated", true);
        activity.finish();

        controller.pause().stop().destroy();
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.MINUTES);

        awaitTrue(() -> !processed.exists());
    }

    @Test
    public void destroyingWithoutSharing_deletesProcessedFilesImmediately() throws Exception {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(jpeg())));
        File processed = addProcessedFile(activity);
        activity.finish();

        controller.pause().stop().destroy();

        awaitTrue(() -> !processed.exists());
    }

    // ---- inbound URIs ----------------------------------------------------------------------------------

    @Test
    public void contentUri_isCopiedIntoTheCache() throws Exception {
        Robolectric.setupContentProvider(TestMediaProvider.class, AUTHORITY);
        TestMediaProvider.file = jpeg();
        TestMediaProvider.type = "image/jpeg";

        launch(send("image/jpeg", Uri.parse("content://" + AUTHORITY + "/photo/1")));

        File[] snapshots = inboundSnapshots();
        assertEquals(1, snapshots.length);
        assertTrue(snapshots[0].getName().endsWith(".jpg"));
        assertTrue(snapshots[0].length() > 0);
    }

    @Test
    public void videoContentUri_getsAnMp4Suffix() throws Exception {
        Robolectric.setupContentProvider(TestMediaProvider.class, AUTHORITY);
        TestMediaProvider.file = jpeg();
        TestMediaProvider.type = "video/mp4";

        launch(send("video/mp4", Uri.parse("content://" + AUTHORITY + "/clip/1")));

        File[] snapshots = inboundSnapshots();
        assertEquals(1, snapshots.length);
        assertTrue(snapshots[0].getName().endsWith(".mp4"));
    }

    @Test
    public void unknownContentType_getsABinSuffix() throws Exception {
        Robolectric.setupContentProvider(TestMediaProvider.class, AUTHORITY);
        TestMediaProvider.file = jpeg();
        TestMediaProvider.type = "application/octet-stream";

        launch(send("*/*", Uri.parse("content://" + AUTHORITY + "/blob/1")));

        File[] snapshots = inboundSnapshots();
        assertEquals(1, snapshots.length);
        assertTrue(snapshots[0].getName().endsWith(".bin"));
    }

    @Test
    public void fileUris_mapTheirExtensionToASnapshotSuffix() throws Exception {
        File mp4 = File.createTempFile("clip_", ".MOV", app.getFilesDir());
        files.add(mp4);
        File bin = File.createTempFile("blob_", ".dat", app.getFilesDir());
        files.add(bin);
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.fromFile(mp4));
        uris.add(Uri.fromFile(bin));
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("*/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);

        launch(intent);

        List<String> suffixes = new ArrayList<>();
        for (File f : inboundSnapshots()) {
            suffixes.add(f.getName().substring(f.getName().lastIndexOf('.')));
        }
        assertTrue(suffixes.contains(".mp4"));
        assertTrue(suffixes.contains(".bin"));
    }

    @Test
    public void unreadableInboundFile_isDroppedAndTheShareFails() {
        ShareHandlerActivity activity = launch(send("image/jpeg", Uri.fromFile(new File("/nonexistent/photo.jpg"))));

        assertTrue(activity.isFinishing());
        assertEquals(app.getString(R.string.share_error_failed_receive_media), ShadowToast.getTextOfLatestToast());
        assertEquals(0, inboundSnapshots().length);
    }

    @Test
    public void dataUriFallback_isUsedWhenThereIsNoStreamExtra() throws Exception {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/jpeg");
        intent.setData(Uri.fromFile(jpeg()));

        ShareHandlerActivity activity = launch(intent);

        assertFalse(activity.isFinishing());
        assertEquals(1, inboundSnapshots().length);
    }

    @Test
    public void nullStreamIntent_isRejected() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/jpeg");
        ShareHandlerActivity activity = launch(intent);
        assertTrue(activity.isFinishing());
        assertEquals(0, inboundSnapshots().length);
    }
}
