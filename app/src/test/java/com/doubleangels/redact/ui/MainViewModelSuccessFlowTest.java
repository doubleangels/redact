package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeMediaStoreProvider;
import com.doubleangels.redact.R;
import com.doubleangels.redact.ShareHandlerActivity;
import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Batches that really succeed (images are written through the fake MediaStore). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainViewModelSuccessFlowTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private Application app;
    private MainViewModel vm;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        AppPreferences.setStrictClean(app, true);
        vm = new MainViewModel(app, new SavedStateHandle());
    }

    @After
    public void tearDown() {
        setShareSessions(0);
        FakeMediaStoreProvider.reset();
        AppProcessingScope.resetForTests();
        for (File f : files) {
            f.delete();
        }
    }

    private static void awaitMain(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for background work");
            }
            Thread.sleep(25);
        }
    }

    private MediaItem jpeg(String name) throws IOException {
        File f = File.createTempFile("vm_", ".jpg", app.getFilesDir());
        files.add(f);
        Bitmap bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(f)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        return new MediaItem(Uri.fromFile(f), false, name);
    }

    private static MediaItem missing(String name) {
        return new MediaItem(Uri.fromFile(new File("/nonexistent/" + name)), false, name);
    }

    private static void setShareSessions(int count) {
        try {
            Field f = ShareHandlerActivity.class.getDeclaredField("activeShareSessions");
            f.setAccessible(true);
            ((AtomicInteger) f.get(null)).set(count);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // ---- clean ------------------------------------------------------------------------------------

    @Test
    public void cleaning_realImages_succeedsAndRemembersTheSources() throws Exception {
        MediaItem a = jpeg("a.jpg");
        MediaItem b = jpeg("b.jpg");

        vm.startCleaning(Arrays.asList(a, b));
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(2), vm.getCleanProcessedItemCount().getValue());
        assertEquals(Integer.valueOf(2), vm.getCleanBatchTotalCount().getValue());
        assertEquals(Arrays.asList(a.uri(), b.uri()), vm.getCleanSucceededSources());
        assertEquals(2, FakeMediaStoreProvider.entries().size());
        assertTrue(vm.getCleanProgressMessage().getValue() != null);
    }

    @Test
    public void cleaning_aMixedBatch_reportsPartialSuccess() throws Exception {
        vm.startCleaning(Arrays.asList(jpeg("a.jpg"), missing("b.jpg")));
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(1), vm.getCleanProcessedItemCount().getValue());
        assertEquals(Integer.valueOf(2), vm.getCleanBatchTotalCount().getValue());
    }

    @Test
    public void cleaning_cancelledByTheProcessor_endsAsCancelled() throws Exception {
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            items.add(jpeg("p" + i + ".jpg"));
        }

        vm.startCleaning(items);
        AppProcessingScope.get(app).mediaProcessor().cancel();
        awaitMain(() -> {
            MainViewModel.ProcessingState state = vm.getCleanProcessingState().getValue();
            return state == MainViewModel.ProcessingState.CANCELLED
                    || state == MainViewModel.ProcessingState.COMPLETED;
        });

        assertFalse(vm.isProcessingActive() && vm.getCleanProcessingState().getValue()
                == MainViewModel.ProcessingState.PROCESSING);
    }

    @Test
    public void cleaning_isRefusedWhileASharIsBeingProcessed() {
        setShareSessions(1);
        vm.startCleaning(Arrays.asList(missing("a.jpg")));
        assertEquals(app.getString(R.string.status_already_processing), vm.getCleanProgressMessage().getValue());
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void cleaning_aSecondStartWhileOneRuns_reportsAlreadyProcessing() throws Exception {
        vm.startCleaning(Arrays.asList(jpeg("a.jpg"), jpeg("b.jpg")));
        vm.startCleaning(Arrays.asList(jpeg("c.jpg")));

        // The refusal message is posted, then overwritten by the first batch's own progress.
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);
        assertEquals(Integer.valueOf(2), vm.getCleanProcessedItemCount().getValue());
    }

    // ---- convert ----------------------------------------------------------------------------------

    @Test
    public void converting_realImages_reportsEverythingConverted() throws Exception {
        vm.startConversion(Arrays.asList(jpeg("a.jpg"), jpeg("b.jpg")), 1, 1, Bitmap.CompressFormat.PNG);

        awaitMain(() -> vm.getConvertProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(2), vm.getConvertProcessedItemCount().getValue());
        assertEquals(app.getString(R.string.convert_done_all, 2), vm.getConvertProgressMessage().getValue());
        assertEquals(2, FakeMediaStoreProvider.entries().size());
        for (FakeMediaStoreProvider.Entry e : FakeMediaStoreProvider.entries().values()) {
            assertEquals("image/png", e.mimeType());
        }
    }

    @Test
    public void converting_aMixedBatch_reportsPartialSuccess() throws Exception {
        vm.startConversion(Arrays.asList(jpeg("a.jpg"), missing("b.jpg")), 2, 2, Bitmap.CompressFormat.WEBP);

        awaitMain(() -> vm.getConvertProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(1), vm.getConvertProcessedItemCount().getValue());
        assertEquals(app.getString(R.string.convert_done_partial, 1, 1), vm.getConvertProgressMessage().getValue());
    }

    @Test
    public void converting_isRefusedWhileASharIsBeingProcessed() {
        setShareSessions(1);
        vm.startConversion(Arrays.asList(missing("a.jpg")), 0, 0, Bitmap.CompressFormat.JPEG);
        assertEquals(app.getString(R.string.status_already_processing), vm.getConvertProgressMessage().getValue());
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getConvertProcessingState().getValue());
    }

    @Test
    public void converting_cancelledPartWay_endsCancelled() throws Exception {
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add(jpeg("c" + i + ".jpg"));
        }

        vm.startConversion(items, 1, 1, Bitmap.CompressFormat.PNG);
        vm.cancelConversion();
        awaitMain(() -> !AppProcessingScope.get(app).convertInProgress().get());
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(MainViewModel.ProcessingState.CANCELLED, vm.getConvertProcessingState().getValue());
    }
}
