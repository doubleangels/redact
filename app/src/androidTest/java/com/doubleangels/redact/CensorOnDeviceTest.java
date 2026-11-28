package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorRegion;
import com.doubleangels.redact.media.CensorVerifier;
import com.doubleangels.redact.media.CleanFailedException;
import com.doubleangels.redact.media.SecureDelete;
import com.doubleangels.redact.media.VideoCensorExporter;
import com.doubleangels.redact.media.VideoCensorRegion;
import com.doubleangels.redact.media.VideoMedia3Converter;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Censors the checkerboard fixtures (from make_instrumented_media.py) with real codecs and the real
 * GPU effect, and checks the saved frames without {@link CensorVerifier}: inside each area the
 * checkerboard's fine detail must be gone, and outside it, still there.
 */
@RunWith(AndroidJUnit4.class)
public class CensorOnDeviceTest {

    private static final CensorRegion CENTRE = CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f);
    /** A strip along the top: unmoved by a flip left to right, moved by one top to bottom. */
    private static final CensorRegion TOP = CensorRegion.rect(0.1f, 0.05f, 0.9f, 0.35f);
    /** Average step between neighbouring pixels: about 73 on the checkerboard. */
    private static final double DETAILED = 30.0;
    private static final double COVERED = 12.0;

    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final List<File> made = new ArrayList<>();
    private final List<Uri> published = new ArrayList<>();

    @After
    public void tearDown() {
        for (File file : made) {
            if (file.exists()) {
                SecureDelete.secureDelete(file);
            }
        }
        for (Uri uri : published) {
            context.getContentResolver().delete(uri, null, null);
        }
    }

    @Test
    public void eachEffect_coversItsAreaForTheWholeVideo_andNothingElse() throws Exception {
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            File saved = censor("detail.mp4", List.of(VideoCensorRegion.wholeVideo(TOP)), null, effect);
            for (long time : new long[] {0L, 1_000_000L, 1_930_000L}) {
                Bitmap frame = frameAt(saved, time);
                assertEquals(320, frame.getWidth());
                assertEquals(240, frame.getHeight());
                assertCovered(effect + " at " + time, frame, TOP);
                assertDetailed(effect + " below the area at " + time, frame,
                        CensorRegion.rect(0.1f, 0.55f, 0.9f, 0.95f));
                frame.recycle();
            }
        }
    }

    @Test
    public void anAreaWithATimeRange_isCoveredOnlyThen() throws Exception {
        File saved = censor("detail.mp4",
                List.of(VideoCensorRegion.of(CENTRE, 500_000L, 1_000_000L)), null, CensorEffects.Effect.PIXELATE);
        assertDetailed("before", frameAt(saved, 200_000L), CENTRE);
        assertCovered("at the start", frameAt(saved, 500_000L), CENTRE);
        assertCovered("in the middle", frameAt(saved, 750_000L), CENTRE);
        assertCovered("at the end", frameAt(saved, 1_000_000L), CENTRE);
        assertDetailed("after", frameAt(saved, 1_500_000L), CENTRE);
    }

    @Test
    public void aVideoFilmedOnItsSide_isCoveredWhereTheUprightPictureShowsTheArea() throws Exception {
        File saved = censor("detail_rotated.mp4", List.of(VideoCensorRegion.wholeVideo(TOP)), null,
                CensorEffects.Effect.BLUR);
        Bitmap frame = frameAt(saved, 1_000_000L);
        assertEquals(240, frame.getWidth());
        assertEquals(320, frame.getHeight());
        assertCovered("upright top", frame, TOP);
        assertDetailed("upright bottom", frame, CensorRegion.rect(0.1f, 0.55f, 0.9f, 0.95f));
    }

    @Test
    public void aCroppedVideo_keepsItsAreasInPlace() throws Exception {
        CensorRegion rightHalf = CensorRegion.rect(0.5f, 0f, 1f, 1f);
        File saved = censor("detail.mp4", List.of(VideoCensorRegion.wholeVideo(TOP)), rightHalf,
                CensorEffects.Effect.PIXELATE);
        Bitmap frame = frameAt(saved, 1_000_000L);
        assertEquals(160, frame.getWidth(), 2);
        assertEquals(240, frame.getHeight(), 2);
        // TOP spans 0.1 to 0.9 of the whole width: 0 to 0.8 of the right half.
        assertCovered("kept part of the area", frame, CensorRegion.rect(0f, 0.05f, 0.8f, 0.35f));
        assertDetailed("below it", frame, CensorRegion.rect(0f, 0.55f, 1f, 0.95f));
    }

    /**
     * An area sliding across the frame, as keyframes make it, is covered wherever it is at each
     * moment, on its own moving grid, and the frame is left alone where it has moved away from.
     */
    @Test
    public void aMovingArea_isCoveredWhereverItIs() throws Exception {
        CensorRegion left = CensorRegion.rect(0.05f, 0.3f, 0.35f, 0.7f);
        CensorRegion right = CensorRegion.rect(0.65f, 0.3f, 0.95f, 0.7f);
        VideoCensorRegion sliding = VideoCensorRegion.wholeVideo(left).withKeyframes(List.of(
                new VideoCensorRegion.Keyframe(200_000L, left),
                new VideoCensorRegion.Keyframe(1_800_000L, right)));
        for (CensorEffects.Effect effect : new CensorEffects.Effect[] {
                CensorEffects.Effect.PIXELATE, CensorEffects.Effect.BLACK}) {
            File saved = censor("detail.mp4", List.of(sliding), null, effect);
            assertCovered(effect + " at the first keyframe", frameAt(saved, 200_000L), left);
            assertDetailed(effect + " where it will go", frameAt(saved, 200_000L), right);
            assertCovered(effect + " halfway", frameAt(saved, 1_000_000L),
                    CensorRegion.rect(0.35f, 0.3f, 0.65f, 0.7f));
            assertCovered(effect + " at the last keyframe", frameAt(saved, 1_800_000L), right);
            assertDetailed(effect + " where it has left", frameAt(saved, 1_800_000L), left);
        }
    }

    /** The check in the save itself must catch an effect that drew nothing. */
    @Test
    public void aVideoWhoseAreasWereNotDrawn_failsTheCoverageCheck() throws Exception {
        File source = fixture("detail.mp4");
        File plain = new File(context.getCacheDir(), "censor_test_plain.mp4");
        made.add(plain);
        VideoMedia3Converter.transcodeEditedToPath(context, Uri.fromFile(source), plain.getAbsolutePath(),
                0, List.of(), null, -1L);
        assertThrows(CleanFailedException.class, () -> CensorVerifier.requireVideoAreasCovered(plain, 320, 240,
                List.of(VideoCensorRegion.wholeVideo(TOP)), null, CensorEffects.Effect.PIXELATE, 33_334L));
    }

    @Test
    public void cancellingMidExport_leavesNothingBehind() throws Exception {
        File source = fixture("detail.mp4");
        int entriesBefore = galleryVideos();
        int workingBefore = workingCopies();
        long owner = 4242L;
        boolean[] asked = {false};
        try {
            VideoCensorExporter.export(context, Uri.fromFile(source), "detail.mp4",
                    List.of(VideoCensorRegion.wholeVideo(CENTRE)), null, CensorEffects.Effect.BLUR,
                    percent -> {
                        if (!asked[0]) {
                            asked[0] = true;
                            VideoMedia3Converter.cancelActiveTranscode(owner);
                        }
                    }, owner, () -> asked[0]);
            fail("A cancelled censor saved a video");
        } catch (IOException expected) {
            // Cancelled.
        }
        assertEquals(entriesBefore, galleryVideos());
        assertEquals(workingBefore, workingCopies());
    }

    // ---- helpers --------------------------------------------------------------------------------

    private File fixture(String name) throws IOException {
        File source = new File(context.getCacheDir(), "censor_test_" + name);
        made.add(source);
        try (InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("media/" + name);
                OutputStream out = new FileOutputStream(source)) {
            in.transferTo(out);
        }
        return source;
    }

    /** Censors a fixture into the gallery and returns a private copy of what was saved. */
    private File censor(String name, List<VideoCensorRegion> regions, CensorRegion crop,
                        CensorEffects.Effect effect) throws IOException {
        File source = fixture(name);
        Uri uri = VideoCensorExporter.export(context, Uri.fromFile(source), name, regions, crop, effect,
                null, -1L, null);
        assertNotNull(uri);
        published.add(uri);
        File copy = File.createTempFile("censor_test_saved_", ".mp4", context.getCacheDir());
        made.add(copy);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(copy)) {
            assertNotNull(in);
            in.transferTo(out);
        }
        return copy;
    }

    private static Bitmap frameAt(File video, long timeUs) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(video.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            assertNotNull("No frame at " + timeUs, frame);
            return frame;
        } finally {
            retriever.release();
        }
    }

    /** The average luma step between horizontal neighbours inside {@code area}, inset 4 px. */
    private static double detail(Bitmap frame, CensorRegion area) {
        float[] p = area.points();
        int x0 = (int) (p[0] * frame.getWidth()) + 4;
        int y0 = (int) (p[1] * frame.getHeight()) + 4;
        int x1 = (int) (p[2] * frame.getWidth()) - 4;
        int y1 = (int) (p[3] * frame.getHeight()) - 4;
        long sum = 0;
        long count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x + 1 < x1; x++) {
                sum += Math.abs(luma(frame.getPixel(x, y)) - luma(frame.getPixel(x + 1, y)));
                count++;
            }
        }
        return count == 0 ? 0 : (double) sum / count;
    }

    private static int luma(int c) {
        return (299 * ((c >> 16) & 0xff) + 587 * ((c >> 8) & 0xff) + 114 * (c & 0xff)) / 1000;
    }

    private static void assertCovered(String what, Bitmap frame, CensorRegion area) {
        double d = detail(frame, area);
        assertTrue(what + ": detail " + d + " is left", d < COVERED);
    }

    private static void assertDetailed(String what, Bitmap frame, CensorRegion area) {
        double d = detail(frame, area);
        assertTrue(what + ": detail " + d + " is gone", d > DETAILED);
    }

    private int galleryVideos() {
        Bundle args = new Bundle();
        args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                new String[] {MediaStore.MediaColumns._ID}, args, null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    private int workingCopies() {
        File[] files = context.getCacheDir().listFiles((dir, name) -> name.startsWith("vid_censor_"));
        return files == null ? 0 : files.length;
    }
}
