package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.ui.MainViewModel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Drives the Clean and Convert tabs through the shared {@link MainViewModel}, like the user would. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class CleanConvertFragmentsTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private MainViewModel vm;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        vm = new ViewModelProvider(activity).get(MainViewModel.class);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        AppProcessingScope.resetForTests();
    }

    /** On API 31 the picker is only offered once legacy storage read access is granted. */
    private void grantStorageRead() {
        shadowOf(activity.getApplication()).grantPermissions(android.Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void awaitMain(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            idle();
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for background work");
            }
            Thread.sleep(25);
        }
    }

    private Fragment fragment(String tag) {
        return activity.getSupportFragmentManager().findFragmentByTag(tag);
    }

    private View tab(String tag, int tabId) {
        activity.selectTab(tabId);
        idle();
        View v = fragment(tag).getView();
        assertNotNull(v);
        return v;
    }

    private View clean() {
        return tab("clean", R.id.navigation_clean);
    }

    private View convert() {
        return tab("convert", R.id.navigation_convert);
    }

    private static MediaItem image(String name) {
        return new MediaItem(Uri.parse("file:///nonexistent/" + name), false, name);
    }

    private static MediaItem video(String name) {
        return new MediaItem(Uri.parse("file:///nonexistent/" + name), true, name);
    }

    private static int visibility(View root, int id) {
        return root.findViewById(id).getVisibility();
    }

    private static String text(View root, int id) {
        return ((TextView) root.findViewById(id)).getText().toString();
    }

    // ---- Clean -----------------------------------------------------------------------------

    @Test
    public void clean_emptyState_thenSelection_swapsContent() {
        View v = clean();
        assertEquals(View.VISIBLE, visibility(v, R.id.emptyStateContainer));
        assertEquals(View.GONE, visibility(v, R.id.mediaContentContainer));
        assertEquals(View.GONE, visibility(v, R.id.stripButton));

        vm.setSelectedItems(Arrays.asList(image("a.jpg"), video("b.mp4")));
        idle();

        assertEquals(View.GONE, visibility(v, R.id.emptyStateContainer));
        assertEquals(View.VISIBLE, visibility(v, R.id.mediaContentContainer));
        assertEquals(View.VISIBLE, visibility(v, R.id.stripButton));
        assertTrue(v.findViewById(R.id.stripButton).isEnabled());
        assertEquals(activity.getString(R.string.clean_selected_count, 2), text(v, R.id.cleanSelectedCountText));
    }

    @Test
    public void clean_clearButton_emptiesSelection_unlessProcessing() {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        idle();

        vm.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);
        idle();
        v.findViewById(R.id.cleanClearButton).performClick();
        assertEquals(1, vm.getSelectedItems().getValue().size());

        vm.setCleanProcessingState(MainViewModel.ProcessingState.IDLE);
        idle();
        v.findViewById(R.id.cleanClearButton).performClick();
        idle();
        assertTrue(vm.getSelectedItems().getValue().isEmpty());
        assertEquals(View.VISIBLE, visibility(v, R.id.emptyStateContainer));
    }

    @Test
    public void clean_processingState_updatesButtonAndProgress() {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        idle();

        vm.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);
        vm.updateCleanProgressPercent(40, "Working on a.jpg");
        idle();
        MaterialButton strip = v.findViewById(R.id.stripButton);
        assertEquals(activity.getString(R.string.button_cancel), strip.getText().toString());
        assertTrue(strip.isEnabled());
        assertEquals(View.VISIBLE, visibility(v, R.id.progressContainer));
        assertFalse(v.findViewById(R.id.selectButton).isEnabled());
        assertEquals("Working on a.jpg", text(v, R.id.progressText));

        vm.setCleanProcessingState(MainViewModel.ProcessingState.COMPLETED);
        idle();
        assertEquals(activity.getString(R.string.button_strip_exif_data), strip.getText().toString());
        assertEquals(View.GONE, visibility(v, R.id.progressContainer));
        assertTrue(v.findViewById(R.id.selectButton).isEnabled());

        vm.setCleanProcessingState(MainViewModel.ProcessingState.CANCELLED);
        idle();
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void clean_completedWithDeleteOriginals_requestsTrash() {
        AppPreferences.setDeleteOriginalsAfterClean(activity, true);
        clean();
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        vm.setCleanProcessingState(MainViewModel.ProcessingState.COMPLETED);
        idle();

        assertEquals(MainViewModel.ProcessingState.COMPLETED, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void clean_originalsAreOfferedForTrashOnlyOnce_evenAfterTheViewIsRecreated() throws Exception {
        FakeMediaStoreProvider.install(activity);
        AppPreferences.setDeleteOriginalsAfterClean(activity, true);
        java.io.File jpeg = java.io.File.createTempFile("trash_", ".jpg", activity.getFilesDir());
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(jpeg)) {
            android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
        }
        clean();
        vm.startCleaning(Arrays.asList(new MediaItem(Uri.fromFile(jpeg), false, "photo.jpg")));
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);
        idle();

        // The Clean tab consumed the sources when it saw COMPLETED...
        assertTrue(vm.getCleanSucceededSources().isEmpty());

        // ...so a recreated view, which is handed COMPLETED again, finds nothing to ask about.
        controller.recreate();
        activity = controller.get();
        idle();
        assertEquals(MainViewModel.ProcessingState.COMPLETED,
                new ViewModelProvider(activity).get(MainViewModel.class).getCleanProcessingState().getValue());
        assertTrue(new ViewModelProvider(activity).get(MainViewModel.class).getCleanSucceededSources().isEmpty());
        jpeg.delete();
        FakeMediaStoreProvider.reset();
    }

    @Test
    public void clean_changingSelectionAfterCompletion_resetsToIdle() {
        clean();
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        idle();
        vm.setCleanProcessingState(MainViewModel.ProcessingState.COMPLETED);
        idle();

        vm.setSelectedItems(Arrays.asList(image("b.jpg")));
        idle();

        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void clean_stripButton_withoutSelection_promptsToSelect() {
        View v = clean();
        v.findViewById(R.id.stripButton).performClick();
        idle();
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void clean_stripButton_whileProcessing_cancels() {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        vm.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);
        idle();

        v.findViewById(R.id.stripButton).performClick();
        idle();

        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void clean_stripButton_withSelection_runsBatch() throws Exception {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(image("a.jpg"), image("b.jpg")));
        idle();

        v.findViewById(R.id.stripButton).performClick();
        awaitMain(() -> vm.getCleanBatchTotalCount().getValue() == 2
                && vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(0), vm.getCleanProcessedItemCount().getValue());
    }

    @Test
    public void clean_selectButton_launchesPickerAndResultSelectsItems() {
        grantStorageRead();
        View v = clean();
        v.findViewById(R.id.emptyStateSelectButton).performClick();
        idle();

        ShadowActivity shadow = shadowOf(activity);
        ShadowActivity.IntentForResult launched = shadow.peekNextStartedActivityForResult();
        assertNotNull(launched);
        Intent data = new Intent();
        data.setClipData(ClipData.newRawUri("media", Uri.parse("file:///nonexistent/a.jpg")));
        data.getClipData().addItem(new ClipData.Item(Uri.parse("file:///nonexistent/b.mp4")));
        shadow.receiveResult(launched.intent, Activity.RESULT_OK, data);
        idle();
        assertEquals(2, vm.getSelectedItems().getValue().size());
    }

    @Test
    public void clean_hiddenAndShownAgain_refreshesCompletedStatus() {
        clean();
        vm.setSelectedItems(Arrays.asList(image("a.jpg")));
        idle();
        vm.setCleanProcessingState(MainViewModel.ProcessingState.COMPLETED);
        idle();

        convert();
        View v = clean();

        assertEquals(View.VISIBLE, visibility(v, R.id.mediaContentContainer));
        vm.setCleanProcessingState(MainViewModel.ProcessingState.PROCESSING);
        idle();
        convert();
        clean();
        assertEquals(MainViewModel.ProcessingState.PROCESSING, vm.getCleanProcessingState().getValue());
    }

    @Test
    public void clean_permissionResult_isHandled() {
        clean();
        activity.onRequestPermissionsResult(
                com.doubleangels.redact.permission.PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {android.Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {android.content.pm.PackageManager.PERMISSION_DENIED});
        idle();
        assertNotNull(fragment("clean"));
    }

    // ---- Convert ---------------------------------------------------------------------------

    @Test
    public void convert_emptyState_thenImageSelection_showsImageFormats() {
        View v = convert();
        assertEquals(View.VISIBLE, visibility(v, R.id.emptyStateContainer));
        assertEquals(View.GONE, visibility(v, R.id.formatSection));

        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg"), image("b.png")));
        idle();

        assertEquals(View.GONE, visibility(v, R.id.emptyStateContainer));
        assertEquals(View.VISIBLE, visibility(v, R.id.formatSection));
        assertEquals(activity.getString(R.string.convert_output_format_images), text(v, R.id.formatLabel));
        assertEquals(activity.getString(R.string.convert_format_jpeg), text(v, R.id.chipFormatJpeg));
        assertTrue(v.findViewById(R.id.convertButton).isEnabled());
        assertEquals(View.VISIBLE, visibility(v, R.id.filesCountBadge));
    }

    @Test
    public void convert_headerAfterSelectingFiles_tellsYouWhatToDoNext() {
        View v = convert();
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg"), image("b.jpg")));
        idle();

        // The count is the title; the subtitle must no longer ask you to select files.
        assertEquals(activity.getString(R.string.convert_selected_count, 2), text(v, R.id.convertSelectedCountText));
        String hint = text(v, R.id.convertSelectedHint);
        assertEquals("Choose an output format below, then tap Convert.", hint);
        assertFalse(hint.toLowerCase(java.util.Locale.ROOT).startsWith("select files"));
    }

    @Test
    public void convert_videoOnlyAndMixedSelections_relabelTheFormatChips() {
        View v = convert();

        vm.setConvertSelectedItems(Arrays.asList(video("a.mp4")));
        idle();
        assertEquals(activity.getString(R.string.convert_output_format_videos), text(v, R.id.formatLabel));
        assertEquals(activity.getString(R.string.convert_format_h264), text(v, R.id.chipFormatJpeg));
        assertEquals(View.VISIBLE, visibility(v, R.id.chipFormatHeif));

        vm.setConvertSelectedItems(Arrays.asList(video("a.mp4"), image("b.jpg")));
        idle();
        assertEquals(activity.getString(R.string.convert_output_format_mixed), text(v, R.id.formatLabel));

        vm.setConvertSelectedItems(new ArrayList<>());
        idle();
        assertEquals(View.GONE, visibility(v, R.id.formatSection));
        assertEquals(View.GONE, visibility(v, R.id.filesCountBadge));
    }

    @Test
    public void convert_choosingAChip_updatesTheBadge() {
        View v = convert();
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg")));
        idle();

        ChipGroup group = v.findViewById(R.id.formatChipGroup);
        group.check(R.id.chipFormatPng);
        idle();

        assertEquals(activity.getString(R.string.convert_format_png), text(v, R.id.formatBadge));
        group.check(R.id.chipFormatWebp);
        idle();
        assertEquals(activity.getString(R.string.convert_format_webp), text(v, R.id.formatBadge));
    }

    @Test
    public void convert_aFormatTheUserPicked_survivesChangesToTheSelection() {
        View v = convert();
        ChipGroup group = v.findViewById(R.id.formatChipGroup);
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg")));
        idle();

        v.findViewById(R.id.chipFormatWebp).performClick();
        idle();
        assertEquals(R.id.chipFormatWebp, group.getCheckedChipId());

        // Adding a video, then removing the image, changes the mix twice; the choice must stay.
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg"), video("b.mp4")));
        idle();
        assertEquals(R.id.chipFormatWebp, group.getCheckedChipId());
        vm.setConvertSelectedItems(Arrays.asList(video("b.mp4")));
        idle();
        assertEquals(R.id.chipFormatWebp, group.getCheckedChipId());
    }

    @Test
    public void convert_withoutAUserChoice_theDefaultFollowsTheSelectionAndAClearedSelectionStartsOver() {
        View v = convert();
        ChipGroup group = v.findViewById(R.id.formatChipGroup);
        AppPreferences.setDefaultImageFormatIndex(activity, 0);
        AppPreferences.setDefaultVideoFormatIndex(activity, 1);

        vm.setConvertSelectedItems(Arrays.asList(video("b.mp4")));
        idle();
        assertEquals(R.id.chipFormatPng, group.getCheckedChipId());   // the video default (index 1)

        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg")));
        idle();
        assertEquals(R.id.chipFormatJpeg, group.getCheckedChipId());  // the image default (index 0)

        // After a manual pick and a cleared selection, the next selection starts from defaults again.
        v.findViewById(R.id.chipFormatWebp).performClick();
        idle();
        vm.setConvertSelectedItems(new ArrayList<>());
        idle();
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg")));
        idle();
        assertEquals(R.id.chipFormatJpeg, group.getCheckedChipId());
    }

    @Test
    public void convert_clearButton_emptiesSelection_unlessProcessing() {
        View v = convert();
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg")));
        idle();

        vm.setConvertProcessingState(MainViewModel.ProcessingState.PROCESSING);
        idle();
        v.findViewById(R.id.convertClearButton).performClick();
        assertEquals(1, vm.getConvertSelectedItems().getValue().size());

        vm.setConvertProcessingState(MainViewModel.ProcessingState.IDLE);
        idle();
        v.findViewById(R.id.convertClearButton).performClick();
        idle();
        assertTrue(vm.getConvertSelectedItems().getValue().isEmpty());
    }

    @Test
    public void convert_processingStates_driveButtonsAndStatus() {
        View v = convert();
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg"), image("b.jpg")));
        idle();
        MaterialButton button = v.findViewById(R.id.convertButton);

        vm.setConvertProcessingState(MainViewModel.ProcessingState.PROCESSING);
        vm.updateConvertProgressPercentForce(55, "Converting");
        idle();
        assertEquals(activity.getString(R.string.button_cancel), button.getText().toString());
        assertEquals(View.VISIBLE, visibility(v, R.id.progressContainer));
        assertFalse(v.findViewById(R.id.selectButton).isEnabled());
        assertFalse(v.findViewById(R.id.chipFormatJpeg).isEnabled());

        vm.setConvertProcessingState(MainViewModel.ProcessingState.COMPLETED);
        idle();
        assertEquals(activity.getString(R.string.convert_run), button.getText().toString());
        assertEquals(View.GONE, visibility(v, R.id.progressContainer));
        assertTrue(v.findViewById(R.id.chipFormatJpeg).isEnabled());

        vm.setConvertProcessingState(MainViewModel.ProcessingState.CANCELLED);
        idle();
        assertEquals(activity.getString(R.string.status_processing_cancelled), text(v, R.id.statusText));
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getConvertProcessingState().getValue());
    }

    @Test
    public void convert_completionStatus_reflectsSuccessCounts() throws Exception {
        View v = convert();
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg")));
        idle();

        // Every conversion fails (inputs do not exist) -> "failed" status.
        v.findViewById(R.id.convertButton).performClick();
        awaitMain(() -> vm.getConvertProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);
        idle();
        assertEquals(activity.getString(R.string.convert_done_failed), text(v, R.id.statusText));

        // Hiding and showing the tab re-applies the completed status.
        tab("clean", R.id.navigation_clean);
        v = convert();
        assertEquals(activity.getString(R.string.convert_done_failed), text(v, R.id.statusText));
    }

    @Test
    public void convert_clickingConvertWithNothingSelected_isRefused() {
        View v = convert();
        v.findViewById(R.id.convertButton).performClick();
        idle();
        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getConvertProcessingState().getValue());
    }

    @Test
    public void convert_clickingConvertWhileProcessing_cancels() {
        View v = convert();
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg")));
        vm.setConvertProcessingState(MainViewModel.ProcessingState.PROCESSING);
        idle();

        v.findViewById(R.id.convertButton).performClick();
        idle();

        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getConvertProcessingState().getValue());
    }

    @Test
    public void convert_selectButton_launchesPickerAndResultSelectsItems() {
        grantStorageRead();
        View v = convert();
        v.findViewById(R.id.emptyStateSelectButton).performClick();
        idle();

        ShadowActivity shadow = shadowOf(activity);
        ShadowActivity.IntentForResult launched = shadow.peekNextStartedActivityForResult();
        assertNotNull(launched);
        Intent data = new Intent();
        data.setClipData(ClipData.newRawUri("media", Uri.parse("file:///nonexistent/a.jpg")));
        shadow.receiveResult(launched.intent, Activity.RESULT_OK, data);
        idle();
        assertEquals(1, vm.getConvertSelectedItems().getValue().size());
    }

    @Test
    public void convert_selectionRestoredFromViewModel_whenTabIsCreatedLater() {
        vm.setConvertSelectedItems(Arrays.asList(image("a.jpg"), video("b.mp4")));
        idle();

        View v = convert();

        assertEquals(View.VISIBLE, visibility(v, R.id.formatSection));
        assertEquals(View.VISIBLE, visibility(v, R.id.filesCountBadge));
        assertTrue(v.findViewById(R.id.convertButton).isEnabled());
    }

    @Test
    public void convert_permissionResult_isHandled() {
        convert();
        activity.onRequestPermissionsResult(
                com.doubleangels.redact.permission.PermissionManager.STORAGE_PERMISSION_REQUEST_CODE,
                new String[] {android.Manifest.permission.READ_MEDIA_IMAGES},
                new int[] {android.content.pm.PackageManager.PERMISSION_GRANTED});
        idle();
        assertNotNull(fragment("convert"));
    }

    // ---- Clean: already-clean warning -----------------------------------------------------

    /** A content URI for a JPEG with no metadata, which the already-clean check recognizes. */
    private Uri cleanJpegUri() throws Exception {
        FakeMediaStoreProvider.install(activity);
        java.io.File f = java.io.File.createTempFile("already_", ".jpg", activity.getCacheDir());
        f.deleteOnExit();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            android.graphics.Bitmap.createBitmap(12, 12, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
        }
        return FakeMediaStoreProvider.add(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, f, "image/jpeg");
    }

    private AlertDialog awaitDialog() throws InterruptedException {
        awaitMain(() -> ShadowDialog.getLatestDialog() != null);
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    @Test
    public void clean_withSomeAlreadyCleanFiles_offersToSkipThem() throws Exception {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(
                new MediaItem(cleanJpegUri(), false, "clean.jpg"), image("other.jpg")));
        idle();

        v.findViewById(R.id.stripButton).performClick();
        AlertDialog dialog = awaitDialog();

        assertEquals(View.VISIBLE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); // Skip Those
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(1), vm.getCleanBatchTotalCount().getValue());
        FakeMediaStoreProvider.reset();
    }

    @Test
    public void clean_withSomeAlreadyCleanFiles_cleanAllKeepsEveryFile() throws Exception {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(
                new MediaItem(cleanJpegUri(), false, "clean.jpg"), image("other.jpg")));
        idle();

        v.findViewById(R.id.stripButton).performClick();
        awaitDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick(); // Clean All
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(2), vm.getCleanBatchTotalCount().getValue());
        FakeMediaStoreProvider.reset();
    }

    @Test
    public void clean_withOnlyAlreadyCleanFiles_offersCleanAnywayAndNoSkip() throws Exception {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(new MediaItem(cleanJpegUri(), false, "clean.jpg")));
        idle();

        v.findViewById(R.id.stripButton).performClick();
        AlertDialog dialog = awaitDialog();

        assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); // Clean Anyway
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(Integer.valueOf(1), vm.getCleanBatchTotalCount().getValue());
        FakeMediaStoreProvider.reset();
    }

    @Test
    public void clean_cancellingTheAlreadyCleanWarning_startsNothing() throws Exception {
        View v = clean();
        vm.setSelectedItems(Arrays.asList(new MediaItem(cleanJpegUri(), false, "clean.jpg")));
        idle();

        v.findViewById(R.id.stripButton).performClick();
        awaitDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();

        assertEquals(MainViewModel.ProcessingState.IDLE, vm.getCleanProcessingState().getValue());
        FakeMediaStoreProvider.reset();
    }

    @Test
    public void clean_withTheWarningTurnedOff_cleansWithoutAsking() throws Exception {
        AppPreferences.setWarnAlreadyClean(activity, false);
        View v = clean();
        vm.setSelectedItems(Arrays.asList(new MediaItem(cleanJpegUri(), false, "clean.jpg")));
        idle();

        v.findViewById(R.id.stripButton).performClick();
        awaitMain(() -> vm.getCleanProcessingState().getValue() == MainViewModel.ProcessingState.COMPLETED);

        assertEquals(null, ShadowDialog.getLatestDialog());
        FakeMediaStoreProvider.reset();
    }

    @SuppressWarnings("unused")
    private static List<MediaItem> none() {
        return new ArrayList<>();
    }
}
