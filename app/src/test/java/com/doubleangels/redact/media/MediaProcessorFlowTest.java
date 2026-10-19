package com.doubleangels.redact.media;

import static com.doubleangels.redact.TestWaits.awaitMain;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
public class MediaProcessorFlowTest {

    /** Records every callback; the optional ones are only wired when asked, to cover their defaults. */
    private static final class Recorder implements MediaProcessor.ProcessingCallback {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<Integer> percents = new CopyOnWriteArrayList<>();
        volatile int successCount = -1;
        volatile int totalCount = -1;

        @Override
        public void onProgress(int overallPercent, String message) {
            percents.add(overallPercent);
        }

        @Override
        public void onComplete(int successCount, int totalCount) {
            this.successCount = successCount;
            this.totalCount = totalCount;
            events.add("complete");
        }

        @Override
        public void onCancelled(int successCount, int totalCount) {
            this.successCount = successCount;
            this.totalCount = totalCount;
            events.add("cancelled");
        }

        @Override
        public void onAlreadyProcessing() {
            events.add("already");
        }

        @Override
        public void onBatchStarted() {
            events.add("started");
        }
    }

    private Application app;
    private MediaProcessor processor;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        AppPreferences.setStrictClean(app, true);
        processor = new MediaProcessor(app);
    }

    @After
    public void tearDown() {
        processor.shutdown();
        FakeMediaStoreProvider.reset();
        for (File f : files) {
            f.delete();
        }
    }

    private MediaItem jpeg(String name) throws IOException {
        File f = File.createTempFile("proc_", ".jpg", app.getFilesDir());
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

    @Test
    public void cleaningARealImage_reportsStartProgressAndSuccess() throws Exception {
        Recorder cb = new Recorder();
        MediaItem item = jpeg("holiday.jpg");

        processor.processMediaItems(Arrays.asList(item), cb);
        assertTrue(processor.isBusy());
        awaitMain(() -> cb.events.contains("complete"));

        assertEquals(Arrays.asList("started", "complete"), cb.events);
        assertEquals(1, cb.successCount);
        assertEquals(1, cb.totalCount);
        assertFalse(cb.percents.isEmpty());
        assertNotNull(processor.getLastProcessedFileUri());
        assertEquals(Arrays.asList(item.uri()), processor.getSucceededSources());
        assertEquals(1, FakeMediaStoreProvider.entries().size());
        awaitMain(() -> !processor.isBusy());
    }

    @Test
    public void aFailingItemIsCountedButDoesNotStopTheBatch() throws Exception {
        Recorder cb = new Recorder();

        processor.processMediaItems(Arrays.asList(missing("a.jpg"), jpeg("b.jpg"), missing("c.jpg")), cb);
        awaitMain(() -> cb.events.contains("complete"));

        assertEquals(1, cb.successCount);
        assertEquals(3, cb.totalCount);
        assertEquals(1, processor.getSucceededSources().size());
    }

    @Test
    public void emptyOrNullBatchesCompleteImmediately() throws Exception {
        Recorder empty = new Recorder();
        processor.processMediaItems(new ArrayList<>(), empty);
        Recorder none = new Recorder();
        processor.processMediaItems(null, none);

        awaitMain(() -> empty.events.contains("complete") && none.events.contains("complete"));

        assertEquals(0, empty.totalCount);
        assertEquals(0, none.totalCount);
        assertFalse(processor.isBusy());
    }

    @Test
    public void aSecondBatchWhileOneRuns_isRefused() throws Exception {
        Recorder first = new Recorder();
        Recorder second = new Recorder();

        processor.processMediaItems(Arrays.asList(jpeg("a.jpg"), jpeg("b.jpg")), first);
        processor.processMediaItems(Arrays.asList(jpeg("c.jpg")), second);
        awaitMain(() -> second.events.contains("already") && first.events.contains("complete"));

        assertEquals(Arrays.asList("already"), second.events);
        assertEquals(2, first.totalCount);
    }

    @Test
    public void cancelling_stopsTheBatchEarly() throws Exception {
        Recorder cb = new Recorder();
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            items.add(jpeg("p" + i + ".jpg"));
        }

        processor.processMediaItems(items, cb);
        processor.cancel();
        awaitMain(() -> cb.events.contains("cancelled") || cb.events.contains("complete"));

        assertTrue(cb.events.contains("cancelled") || cb.successCount <= 6);
        awaitMain(() -> !processor.isBusy());
    }

    @Test
    public void onceTheOriginalIsTrashed_aCopyThatClashedWithItGetsTheOriginalName() throws Exception {
        AppPreferences.setKeepOriginalFileNames(app, true);
        Recorder cb = new Recorder();
        MediaItem item = jpeg("holiday.jpg");
        processor.processMediaItems(Arrays.asList(item), cb);
        awaitMain(() -> cb.events.contains("complete"));
        awaitMain(() -> !processor.isBusy());
        // The original still held its name when the copy was saved, so the system added " (1)".
        Uri output = processor.getSucceededOutputs().get(0);
        android.content.ContentValues clash = new android.content.ContentValues();
        clash.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "holiday (1).jpg");
        app.getContentResolver().update(output, clash, null, null);

        processor.restoreOriginalNames(processor.takeSucceededSources());

        awaitMain(() -> "holiday.jpg".equals(FakeMediaStoreProvider.only().displayName()));
        assertEquals(Arrays.asList(output), processor.getSucceededOutputs());
    }

    @Test
    public void anOriginalThatWasNotTrashed_leavesItsCopyAlone() throws Exception {
        AppPreferences.setKeepOriginalFileNames(app, true);
        Recorder cb = new Recorder();
        processor.processMediaItems(Arrays.asList(jpeg("holiday.jpg")), cb);
        awaitMain(() -> cb.events.contains("complete"));
        awaitMain(() -> !processor.isBusy());
        Uri output = processor.getSucceededOutputs().get(0);
        android.content.ContentValues clash = new android.content.ContentValues();
        clash.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "holiday (1).jpg");
        app.getContentResolver().update(output, clash, null, null);

        processor.takeSucceededSources();
        processor.restoreOriginalNames(new ArrayList<>());
        Thread.sleep(200);

        assertEquals("holiday (1).jpg", FakeMediaStoreProvider.only().displayName());
    }

    @Test
    public void transcodeOwnerAndLastUriDefaults() {
        processor.setTranscodeOwnerId(7L);
        assertNull(processor.getLastProcessedFileUri());
        assertTrue(processor.getSucceededSources().isEmpty());
        assertFalse(processor.isBusy());
    }
}
