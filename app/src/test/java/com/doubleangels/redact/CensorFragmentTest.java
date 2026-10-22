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
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;

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
    public void openPhoto_showsSummaryCanvasAndToolsCards() throws Exception {
        View root = censorTab();
        Uri testUri = createTestJpeg("sample.jpg");
        vm.requestOpen(testUri);
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        assertEquals(View.GONE, root.findViewById(R.id.censorEmptyState).getVisibility());
        assertEquals(View.VISIBLE, root.findViewById(R.id.censorContent).getVisibility());

        assertNotNull(root.findViewById(R.id.censorSummaryCard));
        assertNotNull(root.findViewById(R.id.censorCanvasCard));
        assertNotNull(root.findViewById(R.id.censorToolsCard));

        TextView fileName = root.findViewById(R.id.censorFileName);
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

        MaterialButtonToggleGroup toolGroup = root.findViewById(R.id.censorToolGroup);
        View brushSlider = root.findViewById(R.id.censorBrushSize);
        View brushContainer = root.findViewById(R.id.censorBrushSizeContainer);

        // Rect tool by default -> slider is GONE
        assertEquals(View.GONE, brushSlider.getVisibility());
        if (brushContainer != null) {
            assertEquals(View.GONE, brushContainer.getVisibility());
        }

        // Switch to Brush tool -> slider and container become VISIBLE
        toolGroup.check(R.id.censorToolBrush);
        idle();
        assertEquals(View.VISIBLE, brushSlider.getVisibility());
        if (brushContainer != null) {
            assertEquals(View.VISIBLE, brushContainer.getVisibility());
        }

        // Switch to Move tool -> slider and container become GONE
        toolGroup.check(R.id.censorToolMove);
        idle();
        assertEquals(View.GONE, brushSlider.getVisibility());
        if (brushContainer != null) {
            assertEquals(View.GONE, brushContainer.getVisibility());
        }
    }

    @Test
    public void choosingATool_isRemembered_butARebuiltViewDoesNotSaveItAgain() {
        View root = censorTab();
        MaterialButtonToggleGroup toolGroup = root.findViewById(R.id.censorToolGroup);
        toolGroup.check(R.id.censorToolBrush);
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
    }

    @Test
    public void effectsAndControls_areWiredAndFunctional() throws Exception {
        View root = censorTab();
        Uri testUri = createTestJpeg("sample.jpg");
        vm.requestOpen(testUri);
        awaitMain(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        idle();

        ChipGroup effectGroup = root.findViewById(R.id.censorEffectGroup);
        assertNotNull(effectGroup);
        assertEquals(R.id.censorEffectPixelate, effectGroup.getCheckedChipId());

        android.widget.LinearLayout.LayoutParams effectLp = (android.widget.LinearLayout.LayoutParams) effectGroup.getLayoutParams();
        assertEquals(android.view.Gravity.CENTER_HORIZONTAL, effectLp.gravity);

        View undo = root.findViewById(R.id.censorUndoButton);
        View redo = root.findViewById(R.id.censorRedoButton);
        View delete = root.findViewById(R.id.censorDeleteButton);
        View save = root.findViewById(R.id.censorSaveButton);

        assertNotNull(undo);
        assertNotNull(redo);
        assertNotNull(delete);
        assertNotNull(save);

        android.view.ViewGroup undoRow = (android.view.ViewGroup) undo.getParent();
        assertTrue(undoRow instanceof android.widget.LinearLayout);
        android.widget.LinearLayout undoLinear = (android.widget.LinearLayout) undoRow;
        assertEquals(android.view.Gravity.CENTER, undoLinear.getGravity());

        int expectedSize = Math.round(48 * root.getResources().getDisplayMetrics().density);
        assertEquals(expectedSize, undo.getLayoutParams().width);
        assertEquals(expectedSize, undo.getLayoutParams().height);
        assertEquals(expectedSize, redo.getLayoutParams().width);
        assertEquals(expectedSize, redo.getLayoutParams().height);

        // Initially no edits: undo/redo disabled, delete gone, save gone
        assertFalse(undo.isEnabled());
        assertFalse(redo.isEnabled());
        assertEquals(View.GONE, delete.getVisibility());
        assertEquals(View.GONE, save.getVisibility());

        // Add a region
        vm.addRegion(CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f));
        idle();

        // Now undo should be enabled and save visible
        assertTrue(undo.isEnabled());
        assertEquals(View.VISIBLE, save.getVisibility());

        // Undo the region
        undo.performClick();
        idle();
        assertFalse(undo.isEnabled());
        assertTrue(redo.isEnabled());
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
        assertNotNull(root.findViewById(R.id.censorToolsCard));
        assertNotNull(root.findViewById(R.id.censorCanvasCard));
        assertNotNull(root.findViewById(R.id.censorSummaryCard));
    }
}
