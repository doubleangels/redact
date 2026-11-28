package com.doubleangels.redact.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorRegion;
import com.doubleangels.redact.media.VideoCensorRegion;
import com.doubleangels.redact.media.VideoMedia3Converter;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Censoring a video: its areas' time ranges, the frame shown, and opening and saving one. */
@RunWith(RobolectricTestRunner.class)
public class CensorViewModelVideoTest {

    @Rule
    public InstantTaskExecutorRule instantTasks = new InstantTaskExecutorRule();

    private static final CensorRegion BOX = CensorRegion.rect(0.1f, 0.1f, 0.5f, 0.5f);
    private static final CensorRegion OTHER = CensorRegion.rect(0.6f, 0.6f, 0.9f, 0.9f);
    private static final long SIX_SECONDS = 6_000_000L;
    /** 30 frames a second. */
    private static final long FRAME = 33_333L;

    private Application app;
    private CensorViewModel vm;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        vm = new CensorViewModel(app);
    }

    @After
    public void tearDown() {
        VideoMedia3Converter.setAwaitTimeoutForTests(null);
        ShadowMediaMetadataRetriever.reset();
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = CensorViewModel.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** Opens a stand-in for a six-second video without decoding it. */
    private void openFakeVideo() throws Exception {
        vm.requestOpen(Uri.parse("content://media/external/video/media/7"));
        set(vm, "video", true);
        set(vm, "durationUs", SIX_SECONDS);
        set(vm, "frameIntervalUs", FRAME);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out");
            }
            ShadowLooper.idleMainLooper();
            Thread.sleep(5);
        }
        ShadowLooper.idleMainLooper();
    }

    // ---- what is a video ------------------------------------------------------------------------

    @Test
    public void isVideoFile_followsTheTypeOrTheName() throws IOException {
        File clip = File.createTempFile("clip", ".mp4", app.getCacheDir());
        File photo = File.createTempFile("photo", ".jpg", app.getCacheDir());
        assertTrue(CensorViewModel.isVideoFile(app, Uri.fromFile(clip)));
        assertFalse(CensorViewModel.isVideoFile(app, Uri.fromFile(photo)));
        assertFalse(CensorViewModel.isVideoFile(app, Uri.parse("content://nowhere")));
        clip.delete();
        photo.delete();
    }

    // ---- time ranges ------------------------------------------------------------------------------

    @Test
    public void aNewArea_coversTheWholeVideo() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        assertTrue(vm.regionTimes(0).isWhole());
        assertTrue("an index with no area reads as the whole video", vm.regionTimes(3).isWhole());
    }

    @Test
    public void settingAnAreasTimes_isAnEditThatCanBeUndone() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        vm.setRegionTimes(0, 2_000_000L, 1_000_000L);
        assertEquals(new CensorViewModel.Span(1_000_000L, 2_000_000L), vm.regionTimes(0));

        vm.undo();
        assertTrue(vm.regionTimes(0).isWhole());
        vm.redo();
        assertEquals(new CensorViewModel.Span(1_000_000L, 2_000_000L), vm.regionTimes(0));

        // The same range again is no edit: one undo goes back to the whole video.
        vm.setRegionTimes(0, 1_000_000L, 2_000_000L);
        vm.undo();
        assertTrue(vm.regionTimes(0).isWhole());
        // Out of range: nothing changes.
        vm.setRegionTimes(4, 0L, 1L);
        vm.setRegionTimes(-1, 0L, 1L);
        assertEquals(1, vm.regions().size());
    }

    @Test
    public void anEndAtTheLastFrame_coversToTheEndOfTheVideo() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        vm.setRegionTimes(0, 500_000L, SIX_SECONDS - 10_000L);
        assertEquals(VideoCensorRegion.END_OF_VIDEO, vm.regionTimes(0).endUs());
        assertFalse(vm.regionTimes(0).isWhole());
        vm.setRegionTimes(0, -5L, SIX_SECONDS);
        assertTrue(vm.regionTimes(0).isWhole());
    }

    @Test
    public void removingAnArea_removesItsTimes() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        vm.addRegion(OTHER);
        vm.setRegionTimes(1, 1_000_000L, 2_000_000L);
        vm.removeRegion(0);
        assertEquals(new CensorViewModel.Span(1_000_000L, 2_000_000L), vm.regionTimes(0));
    }

    @Test
    public void aVideoTakesNoBrushStrokes() throws Exception {
        openFakeVideo();
        vm.addRegion(CensorRegion.stroke(new float[] {0.1f, 0.1f, 0.4f, 0.4f}, 0.05f));
        assertTrue(vm.regions().isEmpty());
        vm.addRegion(BOX);
        assertEquals(1, vm.regions().size());
    }

    @Test
    public void changedTimes_areUnsaved() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        set(vm, "savedRegions", vm.regions());
        set(vm, "savedTimes", List.of(CensorViewModel.Span.WHOLE));
        set(vm, "savedMotions", List.of(List.of()));
        set(vm, "savedEffect", vm.getEffect().getValue());
        assertFalse(vm.hasUnsavedEdits());
        vm.setRegionTimes(0, 0L, 1_000_000L);
        assertTrue(vm.hasUnsavedEdits());
    }

    @Test
    public void thePreview_coversOnlyTheAreasActiveAtThePlayhead() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        vm.addRegion(OTHER);
        vm.setRegionTimes(1, 3_000_000L, 4_000_000L);
        set(vm, "playheadUs", 1_000_000L);
        assertEquals(List.of(vm.regions().get(0)), vm.visibleRegions());
        set(vm, "playheadUs", 3_500_000L);
        assertEquals(vm.regions(), vm.visibleRegions());
        set(vm, "playheadUs", 5_000_000L);
        assertEquals(List.of(vm.regions().get(0)), vm.visibleRegions());
    }

    @Test
    public void aPhotoShowsEveryArea_andIgnoresAPlayhead() {
        vm.requestOpen(Uri.parse("content://media/external/images/media/1"));
        vm.addRegion(BOX);
        vm.setPlayhead(2_000_000L);
        assertEquals(0L, vm.getPlayheadUs());
        assertEquals(vm.regions(), vm.visibleRegions());
        assertFalse(vm.isVideo());
    }

    @Test
    public void timed_pairsEachAreaWithItsRange() {
        List<VideoCensorRegion> timed = CensorViewModel.timed(List.of(BOX, OTHER),
                List.of(new CensorViewModel.Span(1L, 2L)));
        assertEquals(VideoCensorRegion.of(BOX, 1L, 2L), timed.get(0));
        assertTrue("an area with no range covers the whole video", timed.get(1).coversWholeVideo());
    }

    // ---- moving areas -----------------------------------------------------------------------------

    @Test
    public void anAreaThatMoves_isPlacedByKeyframesSetAtThePlayhead() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        set(vm, "playheadUs", 1_000_000L);
        vm.setMoving(0, true);
        assertTrue(vm.isMoving(0));
        assertEquals(List.of(1_000_000L), vm.keyframeTimes(0));
        assertTrue(vm.hasKeyframeAtPlayhead(0));

        // Moved at 3 s: a second keyframe there, and it slides between them.
        set(vm, "playheadUs", 3_000_000L);
        assertFalse(vm.hasKeyframeAtPlayhead(0));
        vm.replaceRegion(0, OTHER);
        assertEquals(List.of(1_000_000L, 3_000_000L), vm.keyframeTimes(0));
        assertEquals(OTHER.withEffect(CensorEffects.Effect.BLACK), vm.regions().get(0));
        set(vm, "playheadUs", 2_000_000L);
        assertArrayEquals(new float[] {0.35f, 0.35f, 0.7f, 0.7f}, vm.regions().get(0).points(), 1e-5f);
        assertEquals(vm.regions(), vm.visibleRegions());

        // Moved again on a keyframe's own frame: that keyframe changes, no new one.
        set(vm, "playheadUs", 3_010_000L);
        vm.replaceRegion(0, CensorRegion.rect(0.7f, 0.7f, 0.95f, 0.95f));
        assertEquals(List.of(1_000_000L, 3_000_000L), vm.keyframeTimes(0));

        assertTrue(CensorViewModel.timed(List.of(BOX), List.of(CensorViewModel.Span.WHOLE),
                List.of(List.of(new VideoCensorRegion.Keyframe(0L, BOX),
                        new VideoCensorRegion.Keyframe(9L, OTHER)))).get(0).moves());
    }

    @Test
    public void keyframes_canBeSteppedThroughAndDeleted() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        set(vm, "playheadUs", 1_000_000L);
        vm.setMoving(0, true);
        set(vm, "playheadUs", 3_000_000L);
        vm.replaceRegion(0, OTHER);
        set(vm, "playheadUs", 2_000_000L);
        assertEquals(1_000_000L, vm.adjacentKeyframe(0, false));
        assertEquals(3_000_000L, vm.adjacentKeyframe(0, true));
        set(vm, "playheadUs", 3_000_000L);
        assertEquals(-1L, vm.adjacentKeyframe(0, true));

        vm.removeKeyframeAtPlayhead(0);
        assertEquals(List.of(1_000_000L), vm.keyframeTimes(0));
        assertEquals(BOX.withEffect(CensorEffects.Effect.BLACK), vm.regions().get(0));
        vm.undo();
        assertEquals(List.of(1_000_000L, 3_000_000L), vm.keyframeTimes(0));
        vm.redo();
        // Nothing to delete off a keyframe.
        set(vm, "playheadUs", 2_000_000L);
        vm.removeKeyframeAtPlayhead(0);
        assertEquals(1, vm.keyframeTimes(0).size());
        // The last one goes: the area stays put where it was.
        set(vm, "playheadUs", 1_000_000L);
        vm.removeKeyframeAtPlayhead(0);
        assertFalse(vm.isMoving(0));
        assertEquals(BOX.withEffect(CensorEffects.Effect.BLACK), vm.regions().get(0));
    }

    @Test
    public void stoppingAnArea_keepsItWhereItIsAtThePlayhead() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        set(vm, "playheadUs", 1_000_000L);
        vm.setMoving(0, true);
        set(vm, "playheadUs", 3_000_000L);
        vm.replaceRegion(0, OTHER);
        set(vm, "playheadUs", 2_000_000L);
        vm.setMoving(0, false);
        assertFalse(vm.isMoving(0));
        assertTrue(vm.keyframeTimes(0).isEmpty());
        assertArrayEquals(new float[] {0.35f, 0.35f, 0.7f, 0.7f}, vm.regions().get(0).points(), 1e-5f);
        vm.undo();
        assertTrue(vm.isMoving(0));
        // The same state again, or no such area, changes nothing.
        vm.setMoving(0, true);
        vm.setMoving(5, true);
        assertEquals(2, vm.keyframeTimes(0).size());
        assertFalse(vm.isMoving(5));
    }

    @Test
    public void changedKeyframes_areUnsaved() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        set(vm, "savedRegions", vm.regions());
        set(vm, "savedTimes", List.of(CensorViewModel.Span.WHOLE));
        set(vm, "savedMotions", List.of(List.of()));
        set(vm, "savedEffect", vm.getEffect().getValue());
        assertFalse(vm.hasUnsavedEdits());
        vm.setMoving(0, true);
        assertTrue(vm.hasUnsavedEdits());
    }

    @Test
    public void aPhotoCannotMove() {
        vm.requestOpen(Uri.parse("content://media/external/images/media/1"));
        vm.addRegion(BOX);
        vm.setMoving(0, true);
        assertFalse(vm.isMoving(0));
        assertFalse(vm.hasKeyframeAtPlayhead(0));
        assertEquals(-1L, vm.adjacentKeyframe(0, true));
    }

    // ---- opening, scrubbing and saving a real file ----------------------------------------------

    private Uri videoFile() throws IOException {
        File file = File.createTempFile("clip", ".mp4", app.getCacheDir());
        file.deleteOnExit();
        Uri uri = Uri.fromFile(file);
        DataSource source = DataSource.toDataSource(app, uri);
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_DURATION, "6000");
        ShadowMediaMetadataRetriever.addMetadata(source, MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT, "180");
        int max = CensorViewModel.previewMaxDimension(app);
        ShadowMediaMetadataRetriever.addScaledFrame(source, 0L, max, max,
                Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888));
        ShadowMediaMetadataRetriever.addScaledFrame(source, 2_000_000L, max, max,
                Bitmap.createBitmap(64, 36, Bitmap.Config.ARGB_8888));
        return uri;
    }

    @Test
    public void openingAVideo_readsItsLength_andShowsItsFirstFrame() throws Exception {
        vm.requestOpen(videoFile());
        await(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        assertTrue(vm.isVideo());
        assertEquals(SIX_SECONDS, vm.getDurationUs());
        assertEquals(0L, vm.getPlayheadUs());
        assertEquals(64, vm.getPreview().getValue().getWidth());
    }

    @Test
    public void movingThePlayhead_showsThatFrame() throws Exception {
        vm.requestOpen(videoFile());
        await(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        Bitmap first = vm.getPreview().getValue();
        Integer before = vm.getHistoryVersion().getValue();
        vm.setPlayhead(2_000_000L);
        assertEquals(2_000_000L, vm.getPlayheadUs());
        await(() -> !before.equals(vm.getHistoryVersion().getValue()));
        await(() -> vm.getPreview().getValue() != first);
        // Past the end: the last frame.
        vm.setPlayhead(60_000_000L);
        assertEquals(SIX_SECONDS - FRAME, vm.getPlayheadUs());
    }

    @Test
    public void aVideoWithNoFrame_cannotBeCensored() throws Exception {
        File file = File.createTempFile("empty", ".mp4", app.getCacheDir());
        file.deleteOnExit();
        vm.requestOpen(Uri.fromFile(file));
        await(() -> vm.getState().getValue() == CensorViewModel.State.UNSUPPORTED);
        assertFalse(vm.isVideo());
    }

    @Test
    public void aVideoSaveThatFails_saysSo() throws Exception {
        // Robolectric has no codecs, so Media3 never finishes: take its timeout path.
        VideoMedia3Converter.setAwaitTimeoutForTests(300L);
        vm.requestOpen(videoFile());
        await(() -> vm.getState().getValue() == CensorViewModel.State.READY);
        vm.addRegion(BOX);
        vm.save();
        assertEquals(CensorViewModel.State.SAVING, vm.getState().getValue());
        await(() -> vm.getState().getValue() == CensorViewModel.State.SAVE_FAILED);
        assertTrue(vm.isLastSaveFailed());
        assertEquals(Integer.valueOf(0), vm.getSaveProgress().getValue());
    }

    @Test
    public void closing_forgetsTheVideo() throws Exception {
        openFakeVideo();
        vm.addRegion(BOX);
        vm.close();
        assertFalse(vm.isVideo());
        assertEquals(0L, vm.getDurationUs());
        assertTrue(vm.regions().isEmpty());
        // Leaving the screen stops any export of this one; with none running it does nothing.
        vm.onCleared();
        assertTrue(Looper.getMainLooper().getThread().isAlive());
    }
}
