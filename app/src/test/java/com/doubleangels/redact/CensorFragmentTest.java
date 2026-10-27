package com.doubleangels.redact;

import static com.doubleangels.redact.TestWaits.awaitMain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.CensorRegion;
import com.doubleangels.redact.ui.CensorViewModel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Tests the Censor tab UI: empty state, opened photo state, tool toggle switching,
 * brush slider visibility, effect selection, history controls, and action buttons.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CensorFragmentTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private CensorViewModel vm;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        vm = new ViewModelProvider(activity).get(CensorViewModel.class);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        FakeMediaStoreProvider.reset();
        AppProcessingScope.resetForTests();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private Fragment fragment(String tag) {
        return activity.getSupportFragmentManager().findFragmentByTag(tag);
    }

    private View censorTab() {
        activity.selectTab(R.id.navigation_censor);
        idle();
        View v = fragment("censor").getView();
        assertNotNull(v);
        return v;
    }

    private Uri createTestJpeg(String name) throws Exception {
        FakeMediaStoreProvider.install(activity);
        File f = new File(activity.getCacheDir(), name);
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
                    .compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
        }
        return FakeMediaStoreProvider.add(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, f, "image/jpeg");
    }

    @Test
    public void emptyState_isShownInitially() {
        View root = censorTab();
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorEmptyState).getVisibility());
        assertEquals(View.GONE, root.findViewById(R.id.censorContent).getVisibility());
        assertNotNull(root.findViewById(R.id.emptyStateSelectButton));
    }

    @Test
    public void openPhoto_showsSummaryCanvasAndToolPill() throws Exception {
        View root = censorTab();
        Uri testUri = createTestJpeg("sample.jpg");
        vm.requestOpen(testUri);
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        assertEquals(View.GONE, root.findViewById(R.id.censorEmptyState).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorContent).getVisibility());

        assertNotNull(root.findViewById(R.id.censorSummaryCard));
        assertNotNull(root.findViewById(R.id.censorCanvasCard));
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorToolPill).getVisibility());

        TextView fileName = root.findViewById(R.id.summaryTitle);
        assertNotNull(fileName);
        assertEquals("sample.jpg", fileName.getText().toString());
    }

    @Test
    public void toolToggle_switchesBrushSliderVisibility() throws Exception {
        View root = censorTab();
        Uri testUri = createTestJpeg("sample.jpg");
        vm.requestOpen(testUri);
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        View brushContainer = root.findViewById(R.id.censorBrushSizeContainer);
        com.google.android.material.button.MaterialButton rect = root.findViewById(R.id.censorToolRect);
        com.google.android.material.button.MaterialButton brush = root.findViewById(R.id.censorToolBrush);
        com.google.android.material.button.MaterialButton move = root.findViewById(R.id.censorToolMove);

        // Rectangle by default: the brush size bar is hidden
        assertTrue(rect.isChecked());
        assertEquals(View.GONE, brushContainer.getVisibility());

        // Brush shows the bar and is the only tool checked
        brush.performClick();
        idle();
        assertEquals(View.VISIBLE, brushContainer.getVisibility());
        assertTrue(brush.isChecked());
        assertFalse(rect.isChecked());

        // Tapping the checked tool again keeps it checked
        brush.performClick();
        idle();
        assertTrue(brush.isChecked());

        // Move hides the bar again
        move.performClick();
        idle();
        assertEquals(View.GONE, brushContainer.getVisibility());
        assertTrue(move.isChecked());
        assertFalse(brush.isChecked());
    }

    @Test
    public void choosingATool_isRemembered_butARebuiltViewDoesNotSaveItAgain() {
        View root = censorTab();
        root.findViewById(R.id.censorToolBrush).performClick();
        idle();
        assertEquals("BRUSH", AppPreferences.getCensorTool(activity));

        // Reset All Settings clears the choice; the view coming back must not write it again.
        AppPreferences.resetAll(activity);
        controller.recreate();
        activity = controller.get();
        idle();
        activity.selectTab(R.id.navigation_censor);
        idle();
        assertFalse(activity.getSharedPreferences(AppPreferences.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .contains("censor_tool"));
        // The buttons follow the reset tool, not the state they saved before the rebuild.
        View rebuilt = fragment("censor").getView();
        assertTrue(((com.google.android.material.button.MaterialButton)
                rebuilt.findViewById(R.id.censorToolRect)).isChecked());
        assertFalse(((com.google.android.material.button.MaterialButton)
                rebuilt.findViewById(R.id.censorToolBrush)).isChecked());
    }

    @Test
    public void effectsAndControls_areWiredAndFunctional() throws Exception {
        View root = censorTab();
        Uri testUri = createTestJpeg("sample.jpg");
        vm.requestOpen(testUri);
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        // The effect button names the current effect, and follows a new choice.
        View effectButton = root.findViewById(R.id.censorEffectButton);
        assertEquals(activity.getString(R.string.censor_effect_current,
                        activity.getString(R.string.censor_effect_pixelate)),
                effectButton.getContentDescription().toString());
        ((CensorFragment) fragment("censor")).chooseEffect(com.doubleangels.redact.media.CensorEffects.Effect.BLUR);
        idle();
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLUR, vm.getEffect().getValue());
        assertEquals(activity.getString(R.string.censor_effect_current,
                        activity.getString(R.string.censor_effect_blur)),
                effectButton.getContentDescription().toString());

        View undo = root.findViewById(R.id.censorUndoButton);
        View redo = root.findViewById(R.id.censorRedoButton);
        View delete = root.findViewById(R.id.censorDeleteButton);
        View save = root.findViewById(R.id.censorSaveButton);

        assertNotNull(undo);
        assertNotNull(redo);
        assertNotNull(delete);
        assertNotNull(save);

        // Undo, redo and delete float in one pill over the photo's top end, taking none of its height.
        android.widget.LinearLayout undoColumn = (android.widget.LinearLayout) undo.getParent();
        assertEquals(android.widget.LinearLayout.VERTICAL, undoColumn.getOrientation());
        assertTrue(undoColumn == redo.getParent() && undoColumn == delete.getParent());
        View pill = root.findViewById(R.id.censorHistoryControls);
        assertEquals(pill, undoColumn.getParent());
        assertEquals(root.findViewById(R.id.censorCanvasCard), pill.getParent());
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.END,
                ((android.widget.FrameLayout.LayoutParams) pill.getLayoutParams()).gravity);

        int expectedSize = Math.round(48 * root.getResources().getDisplayMetrics().density);
        assertEquals(expectedSize, undo.getLayoutParams().width);
        assertEquals(expectedSize, undo.getLayoutParams().height);
        assertEquals(expectedSize, redo.getLayoutParams().width);
        assertEquals(expectedSize, redo.getLayoutParams().height);

        // Initially no edits: undo/redo disabled, delete gone, save gone, and so is the pill
        assertFalse(undo.isEnabled());
        assertFalse(redo.isEnabled());
        assertEquals(View.GONE, delete.getVisibility());
        assertEquals(View.GONE, save.getVisibility());
        assertEquals(View.GONE, pill.getVisibility());

        // Add a region
        vm.addRegion(CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f));
        idle();

        // Now undo should be enabled, with its pill, and save visible
        assertTrue(undo.isEnabled());
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals(View.VISIBLE, save.getVisibility());

        // Undo the region
        undo.performClick();
        idle();
        assertFalse(undo.isEnabled());
        assertTrue(redo.isEnabled());
        // Redo is still there to use, so the pill stays.
        assertEquals(View.VISIBLE, pill.getVisibility());
    }

    @Test
    public void fullScreen_hidesEverythingButThePhoto_keepsTheTools_andBackLeavesIt() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("full.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        View nav = activity.findViewById(R.id.bottomNavigation);
        View pill = root.findViewById(R.id.censorToolPill);
        assertEquals(View.VISIBLE, pill.getVisibility());

        root.findViewById(R.id.censorFullScreenButton).performClick();
        idle();
        assertEquals(View.GONE, root.findViewById(R.id.censorSummaryCard).getVisibility());
        assertEquals(View.GONE, nav.getVisibility());
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals(activity.getString(R.string.censor_exit_full_screen),
                root.findViewById(R.id.censorFullScreenButton).getContentDescription().toString());

        // The tools work the same in full screen.
        root.findViewById(R.id.censorToolMove).performClick();
        idle();
        assertTrue(((com.google.android.material.button.MaterialButton)
                root.findViewById(R.id.censorToolMove)).isChecked());
        assertEquals("MOVE", AppPreferences.getCensorTool(activity));

        activity.getOnBackPressedDispatcher().onBackPressed();
        idle();
        assertFalse(vm.isFullScreen());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorSummaryCard).getVisibility());
        assertEquals(View.VISIBLE, nav.getVisibility());
        assertEquals(activity.getString(R.string.censor_full_screen),
                root.findViewById(R.id.censorFullScreenButton).getContentDescription().toString());
        // The photo is still open: Back only left full screen.
        assertEquals(CensorViewModel.State.READY, vm.getState().getValue());
    }

    @Test
    public void toolPill_makesRoomForTheZoomChip_onlyWhileItShows() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("zoom.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        View pill = root.findViewById(R.id.censorToolPill);
        View chip = root.findViewById(R.id.censorZoomChip);
        float density = root.getResources().getDisplayMetrics().density;
        int bottom = ((android.view.ViewGroup.MarginLayoutParams) pill.getLayoutParams()).bottomMargin;
        assertEquals(Math.round(12 * density), bottom);

        java.lang.reflect.Method zoomBy = com.doubleangels.redact.ui.CensorCanvasView.class
                .getDeclaredMethod("zoomBy", float.class);
        zoomBy.setAccessible(true);
        zoomBy.invoke(root.findViewById(R.id.censorCanvas), 2f);
        idle();
        assertEquals(View.VISIBLE, chip.getVisibility());
        bottom = ((android.view.ViewGroup.MarginLayoutParams) pill.getLayoutParams()).bottomMargin;
        assertEquals(Math.round(64 * density), bottom);

        chip.performClick();
        idle();
        assertEquals(View.GONE, chip.getVisibility());
        bottom = ((android.view.ViewGroup.MarginLayoutParams) pill.getLayoutParams()).bottomMargin;
        assertEquals(Math.round(12 * density), bottom);
    }

    @Test
    public void fullScreen_endsWhenThePhotoCloses() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("close.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        root.findViewById(R.id.censorFullScreenButton).performClick();
        idle();

        vm.close();
        idle();
        assertFalse(vm.isFullScreen());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.bottomNavigation).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorEmptyState).getVisibility());
    }

    @Test
    public void fullScreen_endsWhenAReplacementFailsToOpen() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("first.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        root.findViewById(R.id.censorFullScreenButton).performClick();
        idle();

        vm.requestOpen(Uri.parse("content://com.doubleangels.redact.missing/photo/1"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.LOAD_FAILED
                || vm.getState().getValue() == CensorViewModel.State.UNSUPPORTED);
        idle();
        assertFalse(vm.isFullScreen());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorSummaryCard).getVisibility());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.bottomNavigation).getVisibility());
    }

    @Test
    public void fullScreen_endsWhenLeavingTheTab() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("leave.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        root.findViewById(R.id.censorFullScreenButton).performClick();
        idle();

        activity.selectTab(R.id.navigation_scan);
        idle();
        assertEquals(View.VISIBLE, activity.findViewById(R.id.bottomNavigation).getVisibility());
        activity.selectTab(R.id.navigation_censor);
        idle();
        assertFalse(vm.isFullScreen());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorSummaryCard).getVisibility());
    }

    @Test
    @Config(qualifiers = "land")
    public void fullScreen_inLandscape_hidesTheColumn() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("land-full.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorColumn).getVisibility());

        root.findViewById(R.id.censorFullScreenButton).performClick();
        idle();
        assertEquals(View.GONE, root.findViewById(R.id.censorColumn).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorCanvasCard).getVisibility());

        root.findViewById(R.id.censorFullScreenButton).performClick();
        idle();
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorColumn).getVisibility());
    }

    /** Opens a photo on the Censor tab and covers one rectangle of it. */
    private View openWithOneRegion() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("sample.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        vm.addRegion(CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f));
        idle();
        return root;
    }

    private void saveAndWait() throws InterruptedException {
        vm.save();
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY
                && (vm.getLastSaved() != null || vm.isLastSaveFailed()));
        idle();
    }

    @Test
    public void saving_showsTheResult_andScanResultOpensTheCopyInScan() throws Exception {
        View root = openWithOneRegion();

        saveAndWait();

        assertNotNull(vm.getLastSaved());
        TextView status = root.findViewById(R.id.statusText);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertEquals(activity.getString(R.string.censor_saved), status.getText().toString());
        assertEquals(View.VISIBLE, root.findViewById(R.id.shareResultsButton).getVisibility());
        View scanResult = root.findViewById(R.id.scanResultButton);
        assertEquals(View.VISIBLE, scanResult.getVisibility());

        scanResult.performClick();
        idle();

        assertEquals(R.id.navigation_scan,
                ((com.google.android.material.navigation.NavigationBarView)
                        activity.findViewById(R.id.bottomNavigation)).getSelectedItemId());
    }

    @Test
    public void aSaveThatCannotWrite_saysSo_andOffersNoShare() throws Exception {
        View root = openWithOneRegion();
        FakeMediaStoreProvider.failInsert = true;
        try {
            saveAndWait();
        } finally {
            FakeMediaStoreProvider.failInsert = false;
        }

        assertTrue(vm.isLastSaveFailed());
        TextView status = root.findViewById(R.id.statusText);
        assertEquals(activity.getString(R.string.censor_error_save), status.getText().toString());
        assertEquals(View.GONE, root.findViewById(R.id.shareResultsButton).getVisibility());
    }

    @Test
    public void savingWithDeleteOriginalsOn_stillSaves() throws Exception {
        AppPreferences.setDeleteOriginalsAfterClean(activity, true);
        try {
            openWithOneRegion();
            saveAndWait();
        } finally {
            AppPreferences.setDeleteOriginalsAfterClean(activity, false);
        }

        assertNotNull(vm.getLastSaved());
    }

    @Test
    public void undoRedo_andClearWithUnsavedEdits_asksBeforeDiscarding() throws Exception {
        View root = openWithOneRegion();

        root.findViewById(R.id.censorUndoButton).performClick();
        idle();
        assertFalse(vm.hasRegions());
        root.findViewById(R.id.censorRedoButton).performClick();
        idle();
        assertTrue(vm.hasRegions());

        root.findViewById(R.id.summaryClearButton).performClick();
        idle();
        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(vm.hasRegions());

        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        assertEquals(View.VISIBLE, root.findViewById(R.id.censorEmptyState).getVisibility());
    }

    @Test
    public void anotherPhotoWithUnsavedEdits_asks_andKeepEditingKeepsThem() throws Exception {
        openWithOneRegion();

        vm.requestOpen(createTestJpeg("other.jpg"));
        idle();
        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());

        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
        idle();

        assertTrue(vm.hasRegions());
        assertEquals(null, vm.getPendingReplacement().getValue());
    }

    @Test
    public void theEffectButton_opensAMenu_whoseChoiceIsUsed() throws Exception {
        View root = openWithOneRegion();
        View effectButton = root.findViewById(R.id.censorEffectButton);

        effectButton.performClick();
        effectButton.performClick(); // a second tap while it is open opens no second menu
        idle();
        java.lang.reflect.Field field = CensorFragment.class.getDeclaredField("effectMenu");
        field.setAccessible(true);
        androidx.appcompat.widget.PopupMenu menu =
                (androidx.appcompat.widget.PopupMenu) field.get(fragment("censor"));
        assertNotNull(menu);
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.values().length, menu.getMenu().size());

        int black = com.doubleangels.redact.media.CensorEffects.Effect.BLACK.ordinal();
        menu.getMenu().performIdentifierAction(black, 0);
        idle();

        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLACK, vm.getEffect().getValue());
        // The choice is remembered for later photos; put the default back for the other tests.
        vm.setEffect(com.doubleangels.redact.media.CensorEffects.Effect.PIXELATE);
    }

    @Test
    public void selectPhoto_withoutAFilePicker_saysSomethingWentWrong() {
        View root = censorTab();
        com.doubleangels.redact.ui.Notice.resetForTests();
        shadowOf(activity.getApplication()).checkActivities(true);

        root.findViewById(R.id.emptyStateSelectButton).performClick();
        idle();

        assertEquals(activity.getString(R.string.share_error_generic),
                com.doubleangels.redact.ui.Notice.lastShownForTests());
    }

    @Test
    @Config(qualifiers = "land")
    public void landscape_inflatesAndBindsSuccessfully() throws Exception {
        View root = censorTab();
        assertNotNull(root.findViewById(R.id.censorEmptyState));
        Uri testUri = createTestJpeg("landscape.jpg");
        vm.requestOpen(testUri);
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        assertEquals(View.VISIBLE, root.findViewById(R.id.censorContent).getVisibility());
        assertNotNull(root.findViewById(R.id.censorToolPill));
        assertNotNull(root.findViewById(R.id.censorCanvasCard));
        assertNotNull(root.findViewById(R.id.censorSummaryCard));
    }
}
