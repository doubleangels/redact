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

    /** Whether {@code view} is {@code ancestor} or inside it. */
    private static boolean isInside(View view, View ancestor) {
        for (Object v = view; v instanceof View; v = ((View) v).getParent()) {
            if (v == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Selects the area at {@code index} the way TalkBack does, once the canvas has the regions. */
    private void selectArea(View root, int index) throws InterruptedException {
        com.doubleangels.redact.ui.CensorCanvasView canvas = root.findViewById(R.id.censorCanvas);
        androidx.customview.widget.ExploreByTouchHelper helper = (androidx.customview.widget.ExploreByTouchHelper)
                androidx.core.view.ViewCompat.getAccessibilityDelegate(canvas);
        assertNotNull(helper);
        androidx.core.view.accessibility.AccessibilityNodeProviderCompat provider =
                helper.getAccessibilityNodeProvider(canvas);
        assertNotNull(provider);
        awaitMain(() -> {
            idle();
            return provider.performAction(index,
                    androidx.core.view.accessibility.AccessibilityNodeInfoCompat.ACTION_CLICK, null);
        });
        idle();
    }

    private static boolean isChecked(View root, int id) {
        return ((com.google.android.material.button.MaterialButton) root.findViewById(id)).isChecked();
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
    public void openPhoto_showsSummaryCanvasAndToolbar() throws Exception {
        View root = censorTab();
        Uri testUri = createTestJpeg("sample.jpg");
        vm.requestOpen(testUri);
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        assertEquals(View.GONE, root.findViewById(R.id.censorEmptyState).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorContent).getVisibility());

        assertNotNull(root.findViewById(R.id.censorSummaryCard));
        assertNotNull(root.findViewById(R.id.censorCanvasCard));
        View toolbar = root.findViewById(R.id.censorToolbar);
        assertEquals(View.VISIBLE, toolbar.getVisibility());
        // Floating over the photo: a layer beside it, raised above it so it is drawn and touched first.
        View canvasCard = root.findViewById(R.id.censorCanvasCard);
        assertFalse(isInside(toolbar, canvasCard));
        assertEquals(canvasCard.getParent(), toolbar.getParent());
        assertTrue(toolbar.getZ() > canvasCard.getZ());

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

        // The effect buttons are one tap each, with the current effect checked.
        assertTrue(isChecked(root, R.id.censorEffectBlack));
        assertFalse(isChecked(root, R.id.censorEffectBlur));
        ((CensorFragment) fragment("censor")).chooseEffect(com.doubleangels.redact.media.CensorEffects.Effect.BLUR);
        idle();
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLUR, vm.getEffect().getValue());
        assertTrue(isChecked(root, R.id.censorEffectBlur));
        assertFalse(isChecked(root, R.id.censorEffectBlack));
        vm.setEffect(com.doubleangels.redact.media.CensorEffects.Effect.BLACK);
        idle();

        View undo = root.findViewById(R.id.censorUndoButton);
        View redo = root.findViewById(R.id.censorRedoButton);
        View fullScreen = root.findViewById(R.id.censorFullScreenButton);
        View delete = root.findViewById(R.id.censorDeleteButton);
        View save = root.findViewById(R.id.censorSaveButton);

        assertNotNull(undo);
        assertNotNull(redo);
        assertNotNull(delete);
        assertNotNull(save);

        // Undo, redo and full screen float in one row over the photo's top end.
        android.widget.LinearLayout undoRow = (android.widget.LinearLayout) undo.getParent();
        assertEquals(android.widget.LinearLayout.HORIZONTAL, undoRow.getOrientation());
        assertTrue(undoRow == redo.getParent() && undoRow == fullScreen.getParent());
        View pill = root.findViewById(R.id.censorHistoryControls);
        assertEquals(pill, undoRow.getParent());
        assertEquals(root.findViewById(R.id.censorCanvasCard), pill.getParent());
        assertEquals(android.view.Gravity.TOP | android.view.Gravity.END,
                ((android.widget.FrameLayout.LayoutParams) pill.getLayoutParams()).gravity);
        // Delete is not in it: it sits beside the selected rectangle.
        assertTrue(isInside(delete, root.findViewById(R.id.censorContextActions)));

        int expectedSize = Math.round(48 * root.getResources().getDisplayMetrics().density);
        assertEquals(expectedSize, undo.getLayoutParams().width);
        assertEquals(expectedSize, undo.getLayoutParams().height);
        assertEquals(expectedSize, redo.getLayoutParams().width);
        assertEquals(expectedSize, redo.getLayoutParams().height);

        // Initially no edits: undo/redo disabled, delete and save gone; the pill still shows, so it
        // never appears under the user's finger.
        assertFalse(undo.isEnabled());
        assertFalse(redo.isEnabled());
        assertEquals(View.GONE, delete.getVisibility());
        assertEquals(View.GONE, save.getVisibility());
        assertEquals(View.VISIBLE, pill.getVisibility());

        // Add a region
        vm.addRegion(CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f));
        idle();

        // Now undo should be enabled and save visible
        assertTrue(undo.isEnabled());
        assertEquals(View.VISIBLE, pill.getVisibility());
        assertEquals(View.VISIBLE, save.getVisibility());

        // Undo the region
        undo.performClick();
        idle();
        assertFalse(undo.isEnabled());
        assertTrue(redo.isEnabled());
        assertEquals(View.VISIBLE, pill.getVisibility());
    }

    @Test
    public void addArea_dropsASelectedRectangle_andSwitchesToTheRectangleTool() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("sample.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        root.findViewById(R.id.censorToolBrush).performClick();
        idle();

        View add = root.findViewById(R.id.censorAddAreaButton);
        assertEquals(activity.getString(R.string.censor_add_area), add.getContentDescription().toString());
        // It takes room in the toolbar only while an accessibility service is on.
        assertEquals(View.GONE, add.getVisibility());
        android.view.accessibility.AccessibilityManager manager = (android.view.accessibility.AccessibilityManager)
                activity.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE);
        shadowOf(manager).setEnabled(true);
        try {
            controller.pause().resume();
            idle();
            assertEquals(View.VISIBLE, add.getVisibility());
        } finally {
            shadowOf(manager).setEnabled(false);
        }
        add.performClick();
        idle();

        assertTrue(((com.google.android.material.button.MaterialButton)
                root.findViewById(R.id.censorToolRect)).isChecked());
        assertEquals(1, vm.regions().size());
        assertEquals(CensorRegion.Shape.RECT, vm.regions().get(0).shape());
        awaitMain(() -> {
            idle();
            return root.findViewById(R.id.censorDeleteButton).getVisibility() == View.VISIBLE;
        });
    }

    @Test
    public void deletingAnAreaFromTalkBack_removesItFromThePhoto() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("sample.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        vm.addRegion(CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f));
        idle();
        com.doubleangels.redact.ui.CensorCanvasView canvas = root.findViewById(R.id.censorCanvas);
        androidx.customview.widget.ExploreByTouchHelper helper = (androidx.customview.widget.ExploreByTouchHelper)
                androidx.core.view.ViewCompat.getAccessibilityDelegate(canvas);
        assertNotNull(helper);
        androidx.core.view.accessibility.AccessibilityNodeProviderCompat provider =
                helper.getAccessibilityNodeProvider(canvas);
        assertNotNull(provider);
        // The canvas gets the region once its preview is drawn; the action works from then on.
        awaitMain(() -> {
            idle();
            return provider.performAction(0, R.id.censor_action_delete, null);
        });
        idle();
        assertTrue(vm.regions().isEmpty());
    }

    @Test
    public void fullScreen_hidesEverythingButThePhoto_keepsTheTools_andBackLeavesIt() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("full.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        View nav = activity.findViewById(R.id.bottomNavigation);
        View pill = root.findViewById(R.id.censorToolbar);
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
    public void theZoomChip_showsWhileZoomedIn_andTappingItShowsTheWholePhoto() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("zoom.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        View chip = root.findViewById(R.id.censorZoomChip);
        assertEquals(View.GONE, chip.getVisibility());

        java.lang.reflect.Method zoomBy = com.doubleangels.redact.ui.CensorCanvasView.class
                .getDeclaredMethod("zoomBy", float.class);
        zoomBy.setAccessible(true);
        zoomBy.invoke(root.findViewById(R.id.censorCanvas), 2f);
        idle();
        assertEquals(View.VISIBLE, chip.getVisibility());

        chip.performClick();
        idle();
        assertEquals(View.GONE, chip.getVisibility());
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
        com.doubleangels.redact.ui.Notice.resetForTests();

        saveAndWait();

        assertNotNull(vm.getLastSaved());
        // A full-size copy says so in a short notice, leaving nothing over the result.
        assertEquals(activity.getString(R.string.censor_saved),
                com.doubleangels.redact.ui.Notice.lastShownForTests());
        assertEquals(View.GONE, root.findViewById(R.id.statusText).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.shareResultsButton).getVisibility());
        View scanResult = root.findViewById(R.id.scanResultButton);
        assertEquals(View.VISIBLE, scanResult.getVisibility());

        // Open shows the copy in the photo viewer.
        View open = root.findViewById(R.id.openResultButton);
        assertEquals(View.VISIBLE, open.getVisibility());
        open.performClick();
        idle();
        android.content.Intent started = shadowOf(activity).getNextStartedActivity();
        android.content.Intent view = android.content.Intent.ACTION_CHOOSER.equals(started.getAction())
                ? started.getParcelableExtra(android.content.Intent.EXTRA_INTENT) : started;
        assertEquals(android.content.Intent.ACTION_VIEW, view.getAction());
        assertEquals(vm.getLastSaved().uri, view.getData());

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
        assertTrue(vm.regions().isEmpty());
        root.findViewById(R.id.censorRedoButton).performClick();
        idle();
        assertFalse(vm.regions().isEmpty());

        root.findViewById(R.id.summaryClearButton).performClick();
        idle();
        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertFalse(vm.regions().isEmpty());

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

        assertFalse(vm.regions().isEmpty());
        assertEquals(null, vm.getPendingReplacement().getValue());
    }

    @Test
    public void anEffectButton_setsTheEffectForNewAreas_andLeavesCoveredOnesAlone() throws Exception {
        View root = openWithOneRegion();
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLACK, vm.regions().get(0).effect());

        root.findViewById(R.id.censorEffectBlur).performClick();
        idle();
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLUR, vm.getEffect().getValue());
        assertTrue(isChecked(root, R.id.censorEffectBlur));
        // The area already covered keeps its effect; the next one gets the new one.
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLACK, vm.regions().get(0).effect());
        vm.addRegion(CensorRegion.rect(0.6f, 0.6f, 0.9f, 0.9f));
        idle();
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLUR, vm.regions().get(1).effect());
        // The choice is remembered for later photos; put the default back for the other tests.
        vm.setEffect(com.doubleangels.redact.media.CensorEffects.Effect.BLACK);
    }

    @Test
    public void anEffectButton_withARectangleSelected_changesIt_asAnEditThatCanBeUndone() throws Exception {
        View root = openWithOneRegion();
        selectArea(root, 0);
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorDeleteButton).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorContextActions).getVisibility());

        root.findViewById(R.id.censorEffectPixelate).performClick();
        idle();
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.PIXELATE, vm.regions().get(0).effect());
        assertTrue(isChecked(root, R.id.censorEffectPixelate));
        // Still selected, so Delete stays beside it.
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorDeleteButton).getVisibility());

        root.findViewById(R.id.censorUndoButton).performClick();
        idle();
        assertEquals(com.doubleangels.redact.media.CensorEffects.Effect.BLACK, vm.regions().get(0).effect());
        // The selected rectangle's effect is what the buttons show.
        assertTrue(isChecked(root, R.id.censorEffectBlack));
        vm.setEffect(com.doubleangels.redact.media.CensorEffects.Effect.BLACK);
    }

    @Test
    public void theHint_hidesOnceSomethingIsCovered() throws Exception {
        AppPreferences.resetAll(activity);
        View root = censorTab();
        vm.requestOpen(createTestJpeg("hint.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        View hint = root.findViewById(R.id.censorSummaryCard).findViewById(R.id.summaryHint);
        assertEquals(View.VISIBLE, hint.getVisibility());

        vm.addRegion(CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f));
        idle();
        assertEquals(View.GONE, hint.getVisibility());

        root.findViewById(R.id.censorUndoButton).performClick();
        idle();
        assertEquals(View.VISIBLE, hint.getVisibility());
    }

    @Test
    public void selectPhoto_withoutAFilePicker_saysThePickerCouldNotOpen() {
        View root = censorTab();
        com.doubleangels.redact.ui.Notice.resetForTests();
        shadowOf(activity.getApplication()).checkActivities(true);

        root.findViewById(R.id.emptyStateSelectButton).performClick();
        idle();

        assertEquals(activity.getString(R.string.status_media_picker_fail),
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
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorToolbar).getVisibility());
        assertNotNull(root.findViewById(R.id.censorCanvasCard));
        assertNotNull(root.findViewById(R.id.censorSummaryCard));
    }

    // ---- crop ------------------------------------------------------------------------------------

    private View openedCensorTab() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestJpeg("crop.jpg"));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        return root;
    }

    @Test
    public void theCropTool_isOneOfTheTools_andRemembered() throws Exception {
        View root = openedCensorTab();
        com.google.android.material.button.MaterialButton crop = root.findViewById(R.id.censorToolCrop);
        com.google.android.material.button.MaterialButton rect = root.findViewById(R.id.censorToolRect);

        crop.performClick();
        idle();
        assertTrue(crop.isChecked());
        assertFalse(rect.isChecked());
        assertEquals(View.GONE, root.findViewById(R.id.censorBrushSizeContainer).getVisibility());
        assertEquals("CROP", AppPreferences.getCensorTool(activity));
    }

    /** A crop alone is something to save, and Reset Crop shows with the crop tool while there is one. */
    @Test
    public void aCropAlone_offersSaveCopy_andResetCropShowsWithTheCropTool() throws Exception {
        View root = openedCensorTab();
        View save = root.findViewById(R.id.censorSaveButton);
        View reset = root.findViewById(R.id.censorResetCropButton);
        assertEquals(View.GONE, save.getVisibility());

        root.findViewById(R.id.censorToolCrop).performClick();
        idle();
        assertEquals("nothing to reset yet", View.GONE, reset.getVisibility());

        vm.setCrop(CensorRegion.rect(0.1f, 0.1f, 0.6f, 0.6f));
        idle();
        assertEquals(View.VISIBLE, save.getVisibility());
        assertEquals(View.VISIBLE, reset.getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorHistoryControls).getVisibility());

        root.findViewById(R.id.censorToolRect).performClick();
        idle();
        assertEquals("only with the crop tool", View.GONE, reset.getVisibility());

        root.findViewById(R.id.censorToolCrop).performClick();
        idle();
        reset.performClick();
        idle();
        org.junit.Assert.assertNull(vm.getCrop());
        assertEquals(View.GONE, reset.getVisibility());
        assertEquals(View.GONE, save.getVisibility());
        assertTrue("the reset can be undone", vm.canUndo());
    }

    @Test
    public void aCrop_isUnsavedWork_thatBackAsksAbout() throws Exception {
        openedCensorTab();
        vm.setCrop(CensorRegion.rect(0.1f, 0.1f, 0.6f, 0.6f));
        idle();
        assertTrue(vm.hasUnsavedEdits());
        assertTrue(activity.getOnBackPressedDispatcher().hasEnabledCallbacks());
    }

    @Test
    public void savingACropAlone_offersShareAndScanResult() throws Exception {
        View root = openedCensorTab();
        vm.setCrop(CensorRegion.rect(0f, 0f, 0.5f, 0.5f));
        idle();

        root.findViewById(R.id.censorSaveButton).performClick();
        awaitMain(() -> vm.getLastSaved() != null
                && vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        assertFalse(vm.hasUnsavedEdits());
        assertEquals(View.VISIBLE, root.findViewById(R.id.shareResultsButton).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.scanResultButton).getVisibility());
        android.graphics.Bitmap out;
        try (java.io.InputStream in = activity.getContentResolver().openInputStream(vm.getLastSaved().uri)) {
            out = android.graphics.BitmapFactory.decodeStream(in);
        }
        assertEquals(50, out.getWidth());
        assertEquals(50, out.getHeight());
    }
    // ---- video ----------------------------------------------------------------------------------

    /** A stand-in for a six-second video at 30 frames a second, with frames at 0 and 3 seconds. */
    private Uri createTestVideo() throws Exception {
        File f = new File(activity.getCacheDir(), "clip.mp4");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[] {0, 0, 0, 8, 'f', 't', 'y', 'p'});
        }
        Uri uri = Uri.fromFile(f);
        org.robolectric.shadows.util.DataSource source =
                org.robolectric.shadows.util.DataSource.toDataSource(activity.getApplication(), uri);
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(source,
                android.media.MediaMetadataRetriever.METADATA_KEY_DURATION, "6000");
        org.robolectric.shadows.ShadowMediaMetadataRetriever.addMetadata(source,
                android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, "180");
        int max = CensorViewModel.previewMaxDimension(activity);
        for (long time : new long[] {0L, 3_000_000L}) {
            org.robolectric.shadows.ShadowMediaMetadataRetriever.addScaledFrame(source, time, max, max,
                    android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888));
        }
        return uri;
    }

    @Test
    public void aVideo_showsItsTimeline_hidesTheBrush_andTimesTheSelectedArea() throws Exception {
        View root = censorTab();
        vm.requestOpen(createTestVideo());
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();
        try {
            assertEquals(View.VISIBLE, root.findViewById(R.id.censorVideoControls).getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.censorToolBrush).getVisibility());
            assertEquals(View.GONE, root.findViewById(R.id.censorAreaTimes).getVisibility());
            assertEquals("00:00 / 00:06",
                    ((TextView) root.findViewById(R.id.censorVideoTime)).getText().toString());

            // A key moves the scrubber, as a keyboard or Switch Access does: the frame at that
            // time is asked for.
            View position = root.findViewById(R.id.censorVideoPosition);
            position.requestFocus();
            position.onKeyDown(android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                    new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
            idle();
            assertTrue("moved to " + vm.getPlayheadUs(), vm.getPlayheadUs() > 0L);

            vm.addRegion(com.doubleangels.redact.media.CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f));
            idle();
            selectArea(root, 0);
            assertEquals(View.VISIBLE, root.findViewById(R.id.censorAreaTimes).getVisibility());
            View wholeVideo = root.findViewById(R.id.censorWholeVideoButton);
            assertFalse("a new area already covers the whole video", wholeVideo.isEnabled());
            assertEquals(activity.getString(R.string.censor_area_times, 1),
                    root.findViewById(R.id.censorAreaTimesSlider).getContentDescription().toString());

            vm.setRegionTimes(0, 1_000_000L, 2_000_000L);
            idle();
            assertEquals("00:01–00:02",
                    ((TextView) root.findViewById(R.id.censorAreaTimesLabel)).getText().toString());
            assertTrue(wholeVideo.isEnabled());
            wholeVideo.performClick();
            idle();
            assertTrue(vm.regionTimes(0).isWhole());

            // A photo again: the timeline goes and the brush comes back.
            vm.close();
            idle();
            assertEquals(View.GONE, root.findViewById(R.id.censorVideoControls).getVisibility());
            assertEquals(View.VISIBLE, root.findViewById(R.id.censorToolBrush).getVisibility());
        } finally {
            org.robolectric.shadows.ShadowMediaMetadataRetriever.reset();
        }
    }

    @Test
    public void aVideoThatCannotBeOpened_saysItIsAVideo() throws Exception {
        View root = censorTab();
        File f = new File(activity.getCacheDir(), "broken.mp4");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(1);
        }
        vm.requestOpen(Uri.fromFile(f));
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.UNSUPPORTED);
        idle();
        assertEquals(activity.getString(R.string.censor_error_unsupported_video),
                ((TextView) root.findViewById(R.id.censorMessage)).getText().toString());
    }
}
