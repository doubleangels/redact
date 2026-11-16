package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorRegion;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Opening, replacing and closing photos, and the undo history. Decoding is not needed here. */
@RunWith(RobolectricTestRunner.class)
public class CensorViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantTasks = new InstantTaskExecutorRule();

    private static final Uri A = Uri.parse("content://media/external/images/media/1");
    private static final Uri B = Uri.parse("content://media/external/images/media/2");
    private static final CensorRegion BOX = CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f);

    private CensorViewModel vm;

    @Before
    public void setUp() {
        vm = new CensorViewModel(RuntimeEnvironment.getApplication());
    }

    /** {@code region} as the editor stores it with the default effect, which every test starts on. */
    private static CensorRegion black(CensorRegion region) {
        return region.withEffect(CensorEffects.Effect.BLACK);
    }

    @Test
    public void aNewRegion_takesTheCurrentEffect_andKeepsItWhenTheEffectChanges() {
        vm.requestOpen(A);
        vm.setEffect(CensorEffects.Effect.BLUR);
        vm.addRegion(BOX);
        assertEquals(CensorEffects.Effect.BLUR, vm.regions().get(0).effect());

        vm.setEffect(CensorEffects.Effect.PIXELATE);
        assertEquals(CensorEffects.Effect.BLUR, vm.regions().get(0).effect());
        assertEquals(CensorEffects.Effect.BLUR, vm.regionEffect(0));
        vm.undo();
        assertTrue("changing the effect for new areas was no edit: one undo removes the box",
                vm.regions().isEmpty());
        vm.setEffect(CensorEffects.Effect.BLACK);
    }

    @Test
    public void aRegionDrawnWithItsOwnEffect_keepsIt() {
        vm.requestOpen(A);
        vm.addRegion(BOX.withEffect(CensorEffects.Effect.PIXELATE));
        assertEquals(CensorEffects.Effect.PIXELATE, vm.regions().get(0).effect());
    }

    @Test
    public void movingARegion_keepsItsEffect() {
        vm.requestOpen(A);
        vm.setEffect(CensorEffects.Effect.BLUR);
        vm.addRegion(BOX);
        vm.setEffect(CensorEffects.Effect.BLACK);

        vm.replaceRegion(0, CensorRegion.rect(0.4f, 0.4f, 0.8f, 0.8f));

        assertEquals(CensorEffects.Effect.BLUR, vm.regions().get(0).effect());
    }

    @Test
    public void changingOneRegionsEffect_isAnEditThatCanBeUndone() {
        vm.requestOpen(A);
        CensorRegion second = CensorRegion.rect(0.6f, 0.6f, 0.9f, 0.9f);
        vm.addRegion(BOX);
        vm.addRegion(second);

        vm.setRegionEffect(1, CensorEffects.Effect.BLUR);
        assertEquals(CensorEffects.Effect.BLACK, vm.regions().get(0).effect());
        assertEquals(CensorEffects.Effect.BLUR, vm.regions().get(1).effect());
        assertEquals("only that region changed; new ones keep the current effect",
                CensorEffects.Effect.BLACK, vm.getEffect().getValue());

        vm.undo();
        assertEquals(CensorEffects.Effect.BLACK, vm.regions().get(1).effect());
        vm.redo();
        assertEquals(CensorEffects.Effect.BLUR, vm.regions().get(1).effect());

        // The same effect again, or an index out of range, changes nothing.
        vm.setRegionEffect(1, CensorEffects.Effect.BLUR);
        vm.setRegionEffect(5, CensorEffects.Effect.PIXELATE);
        vm.undo();
        assertEquals(CensorEffects.Effect.BLACK, vm.regions().get(1).effect());
        assertNull(vm.regionEffect(5));
    }

    @Test
    public void afterASave_changingTheEffectForNewAreas_leavesNothingUnsaved() throws Exception {
        vm.requestOpen(A);
        vm.addRegion(BOX);
        java.lang.reflect.Field savedRegions = CensorViewModel.class.getDeclaredField("savedRegions");
        savedRegions.setAccessible(true);
        savedRegions.set(vm, vm.regions());
        assertFalse(vm.hasUnsavedEdits());

        vm.setEffect(CensorEffects.Effect.BLUR);
        assertFalse("the covered area keeps its effect, so the copy still matches", vm.hasUnsavedEdits());

        vm.setRegionEffect(0, CensorEffects.Effect.BLUR);
        assertTrue(vm.hasUnsavedEdits());
        vm.setEffect(CensorEffects.Effect.BLACK);
    }

    @Test
    public void startsWithNoPhoto() {
        assertEquals(CensorViewModel.State.NONE, vm.getState().getValue());
        assertNull(vm.getSource());
        assertFalse(vm.hasUnsavedEdits());
    }

    @Test
    public void openingTheSamePhotoAgain_keepsItsEdits() {
        vm.requestOpen(A);
        vm.addRegion(BOX);

        vm.requestOpen(A);

        assertEquals(1, vm.regions().size());
        assertNull(vm.getPendingReplacement().getValue());
    }

    @Test
    public void anotherPhotoWithoutEdits_replacesStraightAway() {
        vm.requestOpen(A);
        vm.requestOpen(B);

        assertEquals(B, vm.getSource());
        assertNull(vm.getPendingReplacement().getValue());
    }

    @Test
    public void anotherPhotoWithUnsavedEdits_waitsForTheUser() {
        vm.requestOpen(A);
        vm.addRegion(BOX);

        vm.requestOpen(B);
        assertEquals(A, vm.getSource());
        assertEquals(B, vm.getPendingReplacement().getValue());

        vm.cancelReplacement();
        assertEquals(A, vm.getSource());
        assertEquals(1, vm.regions().size());

        vm.requestOpen(B);
        vm.confirmReplacement();
        assertEquals(B, vm.getSource());
        assertTrue(vm.regions().isEmpty());
        assertNull(vm.getPendingReplacement().getValue());
    }

    @Test
    public void close_forgetsThePhotoAndItsEdits() {
        vm.requestOpen(A);
        vm.addRegion(BOX);

        vm.close();

        assertEquals(CensorViewModel.State.NONE, vm.getState().getValue());
        assertNull(vm.getSource());
        assertTrue(vm.regions().isEmpty());
        assertFalse(vm.canRedo());
    }

    @Test
    public void undoAndRedo_walkTheHistory_andANewRegionDropsRedo() {
        vm.requestOpen(A);
        CensorRegion second = CensorRegion.rect(0.6f, 0.6f, 0.9f, 0.9f);
        vm.addRegion(BOX);
        vm.addRegion(second);

        vm.undo();
        assertEquals(1, vm.regions().size());
        assertTrue(vm.canRedo());
        vm.redo();
        assertEquals(black(second), vm.regions().get(1));

        vm.undo();
        vm.addRegion(CensorRegion.stroke(new float[] {0.2f, 0.8f}, 0.05f));
        assertFalse(vm.canRedo());
    }

    @Test
    public void emptyRectangles_areNotAdded() {
        vm.requestOpen(A);
        vm.addRegion(CensorRegion.rect(0.3f, 0.3f, 0.3f, 0.6f));
        assertFalse(vm.canUndo());
    }

    @Test
    public void changingTheEffect_isAnUnsavedEdit_onlyWhenThereAreRegions() {
        vm.requestOpen(A);
        vm.setEffect(CensorEffects.Effect.BLACK);
        assertFalse(vm.hasUnsavedEdits());

        vm.addRegion(BOX);
        assertTrue(vm.hasUnsavedEdits());
    }

    @Test
    public void movingARectangle_canBeUndoneAndRedone() {
        vm.requestOpen(A);
        vm.addRegion(BOX);
        CensorRegion moved = CensorRegion.rect(0.4f, 0.4f, 0.8f, 0.8f);

        vm.replaceRegion(0, moved);
        assertEquals(black(moved), vm.regions().get(0));

        vm.undo();
        assertEquals(black(BOX), vm.regions().get(0));
        assertEquals(1, vm.regions().size());

        vm.redo();
        assertEquals(black(moved), vm.regions().get(0));
    }

    @Test
    public void replacingWithTheSameOrAnEmptyRegion_isNotAnEdit() {
        vm.requestOpen(A);
        vm.addRegion(BOX);

        vm.replaceRegion(0, BOX);
        vm.replaceRegion(0, CensorRegion.rect(0.5f, 0.5f, 0.5f, 0.9f));
        vm.replaceRegion(5, CensorRegion.rect(0.1f, 0.1f, 0.2f, 0.2f));

        vm.undo();
        assertTrue("only adding the box was an edit", vm.regions().isEmpty());
        assertFalse(vm.canUndo());
    }

    @Test
    public void deletingARegion_canBeUndoneAndRedone() {
        vm.requestOpen(A);
        CensorRegion second = CensorRegion.rect(0.6f, 0.6f, 0.9f, 0.9f);
        vm.addRegion(BOX);
        vm.addRegion(second);

        vm.removeRegion(0);
        assertEquals(1, vm.regions().size());
        assertEquals(black(second), vm.regions().get(0));

        vm.undo();
        assertEquals(black(BOX), vm.regions().get(0));
        assertEquals(2, vm.regions().size());

        vm.redo();
        assertEquals(1, vm.regions().size());

        vm.removeRegion(7);
        assertEquals("an index out of range is ignored", 1, vm.regions().size());
    }

    private static final CensorRegion CROP = CensorRegion.rect(0.2f, 0.1f, 0.8f, 0.9f);

    /** A crop alone is something to save, and undo and redo walk it with the regions. */
    @Test
    public void aCrop_isAnEditOfItsOwn_thatUndoAndRedoWalk() {
        vm.requestOpen(A);
        assertFalse(vm.hasEdits());

        vm.setCrop(CROP);
        assertEquals(CROP, vm.getCrop());
        assertTrue(vm.hasEdits());
        assertTrue("a crop alone can be saved", vm.hasUnsavedEdits());
        assertTrue(vm.regions().isEmpty());

        vm.addRegion(BOX);
        vm.undo();
        assertEquals(CROP, vm.getCrop());
        assertTrue(vm.regions().isEmpty());
        vm.undo();
        assertNull(vm.getCrop());
        assertFalse(vm.hasUnsavedEdits());

        vm.redo();
        assertEquals(CROP, vm.getCrop());
        vm.redo();
        assertEquals(1, vm.regions().size());
        assertEquals(CROP, vm.getCrop());
    }

    @Test
    public void aCropOfTheWholePhoto_orAnEmptyOne_isNoCrop() {
        vm.requestOpen(A);
        vm.setCrop(CensorRegion.rect(0f, 0f, 1f, 1f));
        assertNull(vm.getCrop());
        assertFalse("nothing changed, so nothing to undo", vm.canUndo());

        vm.setCrop(CensorRegion.rect(0.3f, 0.3f, 0.3f, 0.8f));
        assertNull(vm.getCrop());

        vm.setCrop(CROP);
        vm.setCrop(CROP);
        assertTrue(vm.canUndo());
        vm.undo();
        assertFalse("the same crop again is not another edit", vm.canUndo());
    }

    @Test
    public void resettingTheCrop_canBeUndone() {
        vm.requestOpen(A);
        vm.setCrop(CROP);
        vm.setCrop(null);
        assertNull(vm.getCrop());
        vm.undo();
        assertEquals(CROP, vm.getCrop());
    }

    /** The effect shows only on covered areas, so with just a crop, changing it changes nothing. */
    @Test
    public void changingTheEffect_withOnlyACrop_isNotAnotherEdit() throws Exception {
        vm.requestOpen(A);
        vm.setCrop(CROP);
        java.lang.reflect.Field savedRegions = CensorViewModel.class.getDeclaredField("savedRegions");
        java.lang.reflect.Field savedCrop = CensorViewModel.class.getDeclaredField("savedCrop");
        savedRegions.setAccessible(true);
        savedCrop.setAccessible(true);
        savedRegions.set(vm, new java.util.ArrayList<CensorRegion>());
        savedCrop.set(vm, CROP);
        assertFalse(vm.hasUnsavedEdits());

        vm.setEffect(CensorEffects.Effect.BLUR);
        assertFalse(vm.hasUnsavedEdits());
        vm.setCrop(CensorRegion.rect(0.3f, 0.1f, 0.8f, 0.9f));
        assertTrue(vm.hasUnsavedEdits());
    }

    @Test
    public void close_forgetsTheCrop() {
        vm.requestOpen(A);
        vm.setCrop(CROP);
        vm.close();
        assertNull(vm.getCrop());
        assertFalse(vm.canUndo());
    }

    @Test
    public void theEffect_isRememberedForTheNextEditor() {
        assertEquals(CensorEffects.Effect.BLACK, vm.getEffect().getValue());
        vm.setEffect(CensorEffects.Effect.BLUR);

        CensorViewModel next = new CensorViewModel(RuntimeEnvironment.getApplication());
        assertEquals(CensorEffects.Effect.BLUR, next.getEffect().getValue());
    }

    /** Black keeps nothing of what it covers; Pixelate and Blur keep an average colour per block. */
    @Test
    public void aFirstEditor_startsOnBlack_butAnEffectChosenBeforeIsKept() {
        assertEquals(CensorEffects.Effect.BLACK,
                CensorViewModel.savedEffect(RuntimeEnvironment.getApplication()));
        com.doubleangels.redact.AppPreferences.setCensorEffect(RuntimeEnvironment.getApplication(), "PIXELATE");
        assertEquals(CensorEffects.Effect.PIXELATE,
                CensorViewModel.savedEffect(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void anUnknownSavedEffect_fallsBackToBlack() {
        com.doubleangels.redact.AppPreferences.setCensorEffect(RuntimeEnvironment.getApplication(), "SWIRL");
        assertEquals(CensorEffects.Effect.BLACK,
                CensorViewModel.savedEffect(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void fitPreview_scalesALargePhotoDown_andLeavesASmallOneAlone() throws Exception {
        java.lang.reflect.Method fit = CensorViewModel.class.getDeclaredMethod(
                "fitPreview", android.graphics.Bitmap.class, int.class);
        fit.setAccessible(true);
        android.graphics.Bitmap small = android.graphics.Bitmap.createBitmap(100, 50, android.graphics.Bitmap.Config.ARGB_8888);
        org.junit.Assert.assertSame(small, fit.invoke(null, small, CensorViewModel.PREVIEW_MAX_DIMENSION));

        android.graphics.Bitmap large = android.graphics.Bitmap.createBitmap(6000, 10, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Bitmap scaled = (android.graphics.Bitmap) fit.invoke(null, large, CensorViewModel.PREVIEW_MAX_DIMENSION);
        org.junit.Assert.assertEquals(CensorViewModel.PREVIEW_MAX_DIMENSION, scaled.getWidth());
        org.junit.Assert.assertEquals(5, scaled.getHeight());

        android.graphics.Bitmap other = android.graphics.Bitmap.createBitmap(6000, 10, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Bitmap smaller = (android.graphics.Bitmap) fit.invoke(
                null, other, CensorViewModel.PREVIEW_MAX_DIMENSION_LOW_MEMORY);
        org.junit.Assert.assertEquals(CensorViewModel.PREVIEW_MAX_DIMENSION_LOW_MEMORY, smaller.getWidth());
    }

    private static org.robolectric.shadows.ShadowActivityManager activityManager() {
        return org.robolectric.Shadows.shadowOf((android.app.ActivityManager) RuntimeEnvironment.getApplication()
                .getSystemService(android.content.Context.ACTIVITY_SERVICE));
    }

    @Test
    public void previewLimit_followsTheHeap() {
        activityManager().setIsLowRamDevice(false);
        activityManager().setMemoryClass(512);
        org.junit.Assert.assertEquals(CensorViewModel.PREVIEW_MAX_DIMENSION,
                CensorViewModel.previewMaxDimension(RuntimeEnvironment.getApplication()));

        activityManager().setMemoryClass(CensorViewModel.LOW_MEMORY_CLASS_MB);
        org.junit.Assert.assertEquals(CensorViewModel.PREVIEW_MAX_DIMENSION,
                CensorViewModel.previewMaxDimension(RuntimeEnvironment.getApplication()));

        activityManager().setMemoryClass(192);
        org.junit.Assert.assertEquals(CensorViewModel.PREVIEW_MAX_DIMENSION_LOW_MEMORY,
                CensorViewModel.previewMaxDimension(RuntimeEnvironment.getApplication()));
    }

    @Test
    public void previewLimit_isSmallOnALowRamPhone_whateverItsHeap() {
        activityManager().setMemoryClass(512);
        activityManager().setIsLowRamDevice(true);
        org.junit.Assert.assertEquals(CensorViewModel.PREVIEW_MAX_DIMENSION_LOW_MEMORY,
                CensorViewModel.previewMaxDimension(RuntimeEnvironment.getApplication()));
    }
}
