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
        assertEquals(second, vm.regions().get(1));

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
        assertEquals(moved, vm.regions().get(0));

        vm.undo();
        assertEquals(BOX, vm.regions().get(0));
        assertEquals(1, vm.regions().size());

        vm.redo();
        assertEquals(moved, vm.regions().get(0));
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
        assertEquals(second, vm.regions().get(0));

        vm.undo();
        assertEquals(BOX, vm.regions().get(0));
        assertEquals(2, vm.regions().size());

        vm.redo();
        assertEquals(1, vm.regions().size());

        vm.removeRegion(7);
        assertEquals("an index out of range is ignored", 1, vm.regions().size());
    }

    @Test
    public void theEffect_isRememberedForTheNextEditor() {
        assertEquals(CensorEffects.Effect.PIXELATE, vm.getEffect().getValue());
        vm.setEffect(CensorEffects.Effect.BLUR);

        CensorViewModel next = new CensorViewModel(RuntimeEnvironment.getApplication());
        assertEquals(CensorEffects.Effect.BLUR, next.getEffect().getValue());
    }

    @Test
    public void anUnknownSavedEffect_fallsBackToPixelate() {
        com.doubleangels.redact.AppPreferences.setCensorEffect(RuntimeEnvironment.getApplication(), "SWIRL");
        assertEquals(CensorEffects.Effect.PIXELATE,
                CensorViewModel.savedEffect(RuntimeEnvironment.getApplication()));
    }
}
