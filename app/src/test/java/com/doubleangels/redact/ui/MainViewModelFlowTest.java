package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.SavedStateHandle;

import com.doubleangels.redact.R;
import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.MediaItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MainViewModelFlowTest {

    @Rule
    public InstantTaskExecutorRule instantTaskExecutorRule = new InstantTaskExecutorRule();

    private Application app;
    private SavedStateHandle handle;
    private MainViewModel vm;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        app = RuntimeEnvironment.getApplication();
        handle = new SavedStateHandle();
        vm = new MainViewModel(app, handle);
    }

    @After
    public void tearDown() {
        AppProcessingScope.resetForTests();
    }

    private static MediaItem image(String name) {
        return new MediaItem(Uri.parse("file:///nonexistent/" + name), false, name);
    }

    private static MediaItem video(String name) {
        return new MediaItem(Uri.parse("file:///nonexistent/" + name), true, name);
    }

    /** Runs the main looper until the condition holds (background work posts back to it). */
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

    // ---- selection + persistence ------------------------------------------------------

    @Test
    public void setSelectedItems_storesCopyAndPersists() {
        List<MediaItem> items = new ArrayList<>(Arrays.asList(image("a.jpg"), video("b.mp4")));
        vm.setSelectedItems(items);
        items.clear();

        List<MediaItem> stored = vm.getSelectedItems().getValue();
        assertEquals(2, stored.size());
        assertEquals("a.jpg", stored.get(0).fileName());
        assertTrue(stored.get(1).isVideo());
    }

    @Test
    public void selection_survivesRecreationThroughSavedState() {
        vm.setSelectedItems(Arrays.asList(image("a.jpg"), video("b.mp4")));
        vm.setConvertSelectedItems(Arrays.asList(image("c.png")));

        MainViewModel restored = new MainViewModel(app, handle);

        List<MediaItem> clean = restored.getSelectedItems().getValue();
        assertEquals(2, clean.size());
        assertEquals(Uri.parse("file:///nonexistent/a.jpg"), clean.get(0).uri());
        assertFalse(clean.get(0).isVideo());
        assertTrue(clean.get(1).isVideo());
        assertEquals("b.mp4", clean.get(1).fileName());
        List<MediaItem> convert = restored.getConvertSelectedItems().getValue();
        assertEquals(1, convert.size());
        assertEquals("c.png", convert.get(0).fileName());
    }

    @Test
    public void nullFileName_isPersistedAsEmpty() {
        vm.setSelectedItems(Arrays.asList(new MediaItem(Uri.parse("file:///x/y.jpg"), false, null)));
        MainViewModel restored = new MainViewModel(app, handle);
        assertEquals("", restored.getSelectedItems().getValue().get(0).fileName());
    }

    @Test
    public void clearSelectedItems_emptiesAndRemovesPersistedState() {
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        vm.clearSelectedItems();

        assertTrue(vm.getSelectedItems().getValue().isEmpty());
        assertTrue(new MainViewModel(app, handle).getSelectedItems().getValue().isEmpty());
    }

    @Test
    public void setSelectedItems_null_meansEmpty() {
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        vm.setSelectedItems(null);
        assertTrue(vm.getSelectedItems().getValue().isEmpty());
        vm.setConvertSelectedItems(null);
        assertTrue(vm.getConvertSelectedItems().getValue().isEmpty());
    }

    @Test
    public void replacingSelection_releasesOnlyDroppedItems() {
        MediaItem keep = image("keep.jpg");
        MediaItem drop = image("drop.jpg");
        vm.setSelectedItems(Arrays.asList(keep, drop));
        vm.setSelectedItems(Arrays.asList(keep, image("new.jpg")));
        vm.setConvertSelectedItems(Arrays.asList(drop));
        vm.setConvertSelectedItems(new ArrayList<>());

        assertEquals(2, vm.getSelectedItems().getValue().size());
        assertTrue(vm.getConvertSelectedItems().getValue().isEmpty());
    }

    @Test
    public void mismatchedPersistedLists_restoreShortestLength() {
        handle.set("clean_selected_uris", new ArrayList<>(Arrays.asList("file:///a.jpg", "file:///b.jpg")));
        handle.set("clean_selected_names", new ArrayList<>(Arrays.asList("a.jpg")));
        handle.set("clean_selected_videos", new ArrayList<>(Arrays.asList(false, true)));

        MainViewModel restored = new MainViewModel(app, handle);
        assertEquals(1, restored.getSelectedItems().getValue().size());
    }

    // ---- processing state ----------------------------------------------------------------

    @Test
    public void restoredTerminalStates_arePreserved_butProcessingIsNot() {
        handle.set("clean_processing_state", MainViewModel.ProcessingState.COMPLETED);
        handle.set("convert_processing_state", MainViewModel.ProcessingState.PROCESSING);
        handle.set("clean_processed_count", 3);
        handle.set("clean_batch_total", 4);
        handle.set("convert_processed_count", 1);
        handle.set("convert_batch_total", 2);

        MainViewModel restored = new MainViewModel(app, handle);

        assertEquals(MainViewModel.ProcessingState.COMPLETED, restored.getCleanProcessingState().getValue());
        assertEquals(MainViewModel.ProcessingState.IDLE, restored.getConvertProcessingState().getValue());
        assertEquals(Integer.valueOf(3), restored.getCleanProcessedItemCount().getValue());
        assertEquals(Integer.valueOf(4), restored.getCleanBatchTotalCount().getValue());
        assertEquals(Integer.valueOf(1), restored.getConvertProcessedItemCount().getValue());
        assertEquals(Integer.valueOf(2), restored.getConvertBatchTotalCount().getValue());
    }

    @Test
    public void restoredState_reflectsBusyScope() {
        AppProcessingScope.get(app).convertInProgress().set(true);
        MainViewModel restored = new MainViewModel(app, new SavedStateHandle());
        assertEquals(MainViewModel.ProcessingState.PROCESSING, restored.getConvertProcessingState().getValue());
        assertTrue(restored.isProcessingActive());
    }

    @Test
    public void setProcessingState_persistsTerminalStatesAndDropsProcessing() {
        vm.setCleanProcessingState(MainViewModel.ProcessingState.COMPLETED);
        assertEquals(MainViewModel.ProcessingState.COMPLETED, handle.get("clean_processing_state"));
        vm.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);
        assertNull(handle.get("clean_processing_state"));

        vm.setConvertProcessingState(MainViewModel.ProcessingState.CANCELLED);
        assertEquals(MainViewModel.ProcessingState.CANCELLED, handle.get("convert_processing_state"));
        vm.setConvertProcessingState(MainViewModel.ProcessingState.PROCESSING);
        assertNull(handle.get("convert_processing_state"));
        assertTrue(vm.isProcessingActive());
    }

    @Test
    public void setProcessingState_fromBackgroundThread_postsAndPersistsOnMain() throws Exception {
        Thread t = new Thread(() -> {
            vm.setCleanProcessingState(MainViewModel.ProcessingState.COMPLETED);
            vm.setConvertProcessingState(MainViewModel.ProcessingState.COMPLETED);
        });
        t.start();
        t.join();
        awaitMain(() -> handle.get("convert_processing_state") != null);

        assertEquals(MainViewModel.ProcessingState.COMPLETED, vm.getCleanProcessingState().getValue());
        assertEquals(MainViewModel.ProcessingState.COMPLETED, handle.get("clean_processing_state"));
    }

    @Test
    public void progressUpdates_areClampedAndNullMessageBecomesEmpty() {
        vm.updateCleanProgressPercent(150, null);
        assertEquals(Integer.valueOf(100), vm.getCleanProgressPercent().getValue());
        assertEquals("", vm.getCleanProgressMessage().getValue());
        vm.updateCleanProgressPercent(-10, "x");
        assertEquals(Integer.valueOf(0), vm.getCleanProgressPercent().getValue());
        assertEquals("x", vm.getCleanProgressMessage().getValue());

        vm.updateConvertProgressPercentForce(250, null);
        assertEquals(Integer.valueOf(100), vm.getConvertProgressPercent().getValue());
        assertEquals("", vm.getConvertProgressMessage().getValue());
        vm.updateConvertProgressPercentForce(42, "half");
        assertEquals(Integer.valueOf(42), vm.getConvertProgressPercent().getValue());
        // A normal update right after a forced one is throttled away.
        vm.updateConvertProgressPercent(0, "throttled");
        assertEquals("half", vm.getConvertProgressMessage().getValue());
    }

    @Test
    public void isAnyProcessing_handlesNullAndIdleActivity() {
        assertFalse(MainViewModel.isAnyProcessing(null));
        FragmentActivity activity = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        assertFalse(MainViewModel.isAnyProcessing(activity));

        AppProcessingScope.get(app).convertInProgress().set(true);
        assertTrue(MainViewModel.isAnyProcessing(activity));
    }

    // ---- cancel ----------------------------------------------------------------------------

    @Test
    public void cancelConversion_marksCancelledAndClearsInProgress() {
        AppProcessingScope.get(app).convertInProgress().set(true);
        vm.cancelConversion();

        assertFalse(AppProcessingScope.get(app).convertInProgress().get());
        assertEquals(MainViewModel.ProcessingState.CANCELLED, vm.getConvertProcessingState().getValue());
    }

    @Test
    public void cancelCleaning_setsCancelledMessage() {
        vm.cancelCleaning();
        assertEquals(MainViewModel.ProcessingState.CANCELLED, vm.getCleanProcessingState().getValue());
        assertEquals(app.getString(R.string.status_processing_cancelled), vm.getCleanProgressMessage().getValue());
    }

    // ---- start guards -----------------------------------------------------------------------

    @Test
    public void startCleaning_ignoresNullAndEmpty() {
        vm.startCleaning(null);
        vm.startCleaning(new ArrayList<>());
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
        assertEquals(Integer.valueOf(0), vm.getCleanBatchTotalCount().getValue());
    }

    @Test
    public void startCleaning_refusedWhileConverting() {
        AppProcessingScope.get(app).convertInProgress().set(true);
        vm.startCleaning(Arrays.asList(image("a.jpg")));
        assertEquals(app.getString(R.string.status_already_processing), vm.getCleanProgressMessage().getValue());
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void startConversion_ignoresNullAndEmpty() {
        vm.startConversion(null, 0, 0, Bitmap.CompressFormat.JPEG);
        vm.startConversion(new ArrayList<>(), 0, 0, Bitmap.CompressFormat.JPEG);
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getConvertProcessingState().getValue());
    }

    @Test
    public void startConversion_refusedWhileCleaning() {
        vm.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);
        vm.startConversion(Arrays.asList(image("a.jpg")), 0, 0, Bitmap.CompressFormat.JPEG);
        assertEquals(app.getString(R.string.status_already_processing), vm.getConvertProgressMessage().getValue());
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getConvertProcessingState().getValue());
    }

    @Test
    public void startConversion_refusedWhenAlreadyConverting() {
        AppProcessingScope.get(app).convertInProgress().set(true);
        vm.startConversion(Arrays.asList(image("a.jpg")), 0, 0, Bitmap.CompressFormat.JPEG);
        assertEquals(app.getString(R.string.status_already_processing), vm.getConvertProgressMessage().getValue());
    }

    // ---- full flows (inputs do not exist, so every item fails) -----------------------------

    @Test
    public void startCleaning_runsBatchToCompletion() throws Exception {
        vm.startCleaning(Arrays.asList(image("a.jpg"), image("b.jpg")));

        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(0), vm.getCleanProcessedItemCount().getValue());
        assertEquals(Integer.valueOf(2), vm.getCleanBatchTotalCount().getValue());
        assertEquals(Integer.valueOf(2), handle.get("clean_batch_total"));
        assertTrue(vm.getCleanSucceededSources().isEmpty());
        assertFalse(vm.isProcessingActive());
    }

    @Test
    public void startConversion_runsBatchToCompletion() throws Exception {
        vm.startConversion(Arrays.asList(image("a.jpg"), image("b.png")), 0, 0, Bitmap.CompressFormat.JPEG);
        assertEquals(MainViewModel.ProcessingState.PROCESSING, vm.getConvertProcessingState().getValue());
        assertEquals(Integer.valueOf(2), vm.getConvertBatchTotalCount().getValue());

        awaitMain(() -> vm.getConvertProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(0), vm.getConvertProcessedItemCount().getValue());
        assertEquals(app.getString(R.string.convert_done_failed), vm.getConvertProgressMessage().getValue());
        assertEquals(Integer.valueOf(100), vm.getConvertProgressPercent().getValue());
        assertFalse(AppProcessingScope.get(app).convertInProgress().get());
    }

    @Test
    public void startConversion_withVideo_finishesEvenWhenTheVideoCannotBeRead() throws Exception {
        vm.startConversion(Arrays.asList(video("v.mp4")), 0, 0, Bitmap.CompressFormat.JPEG);

        awaitMain(() -> vm.getConvertProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(0), vm.getConvertProcessedItemCount().getValue());
    }
}
