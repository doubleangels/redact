package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.doubleangels.redact.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowStatFs;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProcessingResourceWarningsAssessTest {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * MB;
    private static final String AUTHORITY = "com.doubleangels.redact.test.sizes";

    /** Reports whatever size and type a URI's query parameters give it. */
    public static class SizedProvider extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME});
            cursor.addRow(new Object[] {Long.parseLong(uri.getQueryParameter("size")), uri.getLastPathSegment()});
            return cursor;
        }

        @Override
        public String getType(Uri uri) {
            return uri.getQueryParameter("type");
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
    }

    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Robolectric.setupContentProvider(SizedProvider.class, AUTHORITY);
        setFreeSpace(64 * GB);
        setMemory(4 * GB, false);
    }

    private void setFreeSpace(long bytes) {
        ShadowStatFs.reset();
        long blocks = bytes / 4096;
        ShadowStatFs.registerStats(app.getCacheDir(), (int) Math.min(Integer.MAX_VALUE, blocks * 2),
                (int) Math.min(Integer.MAX_VALUE, blocks), (int) Math.min(Integer.MAX_VALUE, blocks));
        if (app.getExternalCacheDir() != null) {
            ShadowStatFs.registerStats(app.getExternalCacheDir(), (int) Math.min(Integer.MAX_VALUE, blocks * 2),
                    (int) Math.min(Integer.MAX_VALUE, blocks), (int) Math.min(Integer.MAX_VALUE, blocks));
        }
    }

    private void setMemory(long available, boolean low) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        info.availMem = available;
        info.lowMemory = low;
        shadowOf((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).setMemoryInfo(info);
    }

    private static AppCompatActivity themedActivity() {
        org.robolectric.android.controller.ActivityController<AppCompatActivity> controller =
                Robolectric.buildActivity(AppCompatActivity.class);
        controller.get().setTheme(R.style.Theme_Redact);
        return controller.setup().get();
    }

    private static Uri sized(String name, long size, String type) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(name)
                .appendQueryParameter("size", Long.toString(size))
                .appendQueryParameter("type", type).build();
    }

    private static MediaItem item(String name, long size, boolean video) {
        return new MediaItem(sized(name, size, video ? "video/mp4" : "image/jpeg"), video, name);
    }

    private static List<Integer> messages(List<ProcessingResourceWarnings.Warning> warnings) {
        List<Integer> ids = new ArrayList<>();
        for (ProcessingResourceWarnings.Warning warning : warnings) {
            ids.add(warning.messageResId);
        }
        return ids;
    }

    @Test
    public void assess_nothingSelected_hasNoWarnings() {
        assertTrue(ProcessingResourceWarnings.assess(app, List.of()).isEmpty());
        assertTrue(ProcessingResourceWarnings.assessShareUris(app, app.getContentResolver(), List.of(),
                new MediaSelector(themedActivity())).isEmpty());
    }

    @Test
    public void assess_smallFilesWithRoomToSpare_hasNoWarnings() {
        assertTrue(ProcessingResourceWarnings.assess(app, List.of(item("a.jpg", 5 * MB, false))).isEmpty());
    }

    @Test
    public void assess_lowMemory_warns() {
        setMemory(100 * MB, false);
        assertEquals(List.of(R.string.processing_warning_low_memory),
                messages(ProcessingResourceWarnings.assess(app, List.of(item("a.jpg", MB, false)))));

        setMemory(4 * GB, true);
        assertEquals(List.of(R.string.processing_warning_low_memory),
                messages(ProcessingResourceWarnings.assess(app, List.of(item("a.jpg", MB, false)))));
    }

    @Test
    public void assess_hugeVideo_warnsAboutItsSize() {
        List<ProcessingResourceWarnings.Warning> warnings =
                ProcessingResourceWarnings.assess(app, List.of(item("big.mp4", 3 * GB, true)));
        assertTrue(messages(warnings).contains(R.string.processing_warning_large_video));
        assertEquals(ProcessingResourceWarnings.Severity.ADVISORY, warnings.get(0).severity);
    }

    @Test
    public void assess_littleFreeSpace_isAdvisory_andTooLittleIsCritical() {
        setFreeSpace(800 * MB);
        List<ProcessingResourceWarnings.Warning> low =
                ProcessingResourceWarnings.assess(app, List.of(item("a.jpg", MB, false)));
        assertEquals(List.of(R.string.processing_warning_low_storage), messages(low));
        assertEquals(ProcessingResourceWarnings.Severity.ADVISORY, low.get(0).severity);
        assertNotNull(low.get(0).formatArgs);

        setFreeSpace(100 * MB);
        List<ProcessingResourceWarnings.Warning> critical =
                ProcessingResourceWarnings.assess(app, List.of(item("a.jpg", MB, false)));
        assertEquals(List.of(R.string.processing_warning_critical_storage_message), messages(critical));
        assertEquals(ProcessingResourceWarnings.Severity.CRITICAL, critical.get(0).severity);
        assertEquals(2, critical.get(0).formatArgs.length);
    }

    @Test
    public void assess_moreTempSpaceThanIsFree_isCritical() {
        setFreeSpace(4 * GB);
        assertEquals(List.of(R.string.processing_warning_critical_storage_message),
                messages(ProcessingResourceWarnings.assess(app, List.of(item("a.mp4", 1800 * MB, true)))));
    }

    @Test
    public void assessShareUris_skipsNulls_andTellsVideosFromTheirType() {
        List<Uri> uris = Arrays.asList(null, sized("clip.mp4", 3 * GB, "video/mp4"), sized("a.jpg", MB, "image/jpeg"));
        List<ProcessingResourceWarnings.Warning> warnings = ProcessingResourceWarnings.assessShareUris(
                app, app.getContentResolver(), uris, new MediaSelector(themedActivity()));
        assertTrue(messages(warnings).contains(R.string.processing_warning_large_video));
    }

    @Test
    public void runWithWarnings_advisoriesToastThenProceed() {
        AppCompatActivity activity = themedActivity();
        AtomicInteger proceeded = new AtomicInteger();
        ProcessingResourceWarnings.runWithWarnings(activity, List.of(
                new ProcessingResourceWarnings.Warning(ProcessingResourceWarnings.Severity.ADVISORY,
                        R.string.processing_warning_low_storage, "1 MB")), proceeded::incrementAndGet);
        assertEquals(1, proceeded.get());
        assertEquals(activity.getString(R.string.processing_warning_low_storage, "1 MB"),
                ShadowToast.getTextOfLatestToast());

        ProcessingResourceWarnings.runWithWarnings(activity, List.of(), proceeded::incrementAndGet);
        assertEquals(2, proceeded.get());
    }

    @Test
    public void runWithWarnings_criticalStorageAsksFirst() {
        AppCompatActivity activity = themedActivity();
        AtomicInteger proceeded = new AtomicInteger();
        ProcessingResourceWarnings.runWithWarnings(activity, List.of(
                new ProcessingResourceWarnings.Warning(ProcessingResourceWarnings.Severity.ADVISORY,
                        R.string.processing_warning_low_memory),
                new ProcessingResourceWarnings.Warning(ProcessingResourceWarnings.Severity.CRITICAL,
                        R.string.processing_warning_critical_storage_message, "2 GB", "1 GB")),
                proceeded::incrementAndGet);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, proceeded.get());

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, proceeded.get());
        assertFalse(dialog.isShowing());
    }
}
