package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.net.Uri;

import com.doubleangels.redact.metadata.MetadataStripper;
import com.doubleangels.redact.sentry.SentryManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.sentry.ISpan;
import io.sentry.ITransaction;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE)
public class MediaProcessorTest {

    @Test
    public void processMediaItems_returnsImmediatelyForNullOrEmptyLists() {
        Activity activity = mockActivity();

        try (MockedConstruction<MetadataStripper> ignored = mockConstruction(MetadataStripper.class)) {
            MediaProcessor processor = new MediaProcessor(activity);
            CountDownLatch callbackLatch = new CountDownLatch(1);

            processor.processMediaItems(null, callback(callbackLatch, new int[1], new ArrayList<>()));
            processor.processMediaItems(new ArrayList<>(), callback(callbackLatch, new int[1], new ArrayList<>()));

            assertEquals(1L, callbackLatch.getCount());
            assertNull(processor.getLastProcessedFileUri());
        }
    }

    @Test
    public void processMediaItems_reportsProgressForImagesAndVideos() throws Exception {
        Activity activity = mockActivity();
        Uri imageResult = Uri.parse("content://tests/processed-image");
        Uri videoResult = Uri.parse("content://tests/processed-video");
        Uri imageUri = Uri.parse("content://tests/input-image");
        Uri videoUri = Uri.parse("content://tests/input-video");
        CountDownLatch completionLatch = new CountDownLatch(1);
        List<Integer> percents = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        int[] completeCount = new int[1];

        try (MockedStatic<SentryManager> sentry = mockSentry();
             MockedConstruction<MetadataStripper> mockedStripper =
                     mockConstruction(
                             MetadataStripper.class,
                             (stripper, context) -> {
                                 AtomicReference<MetadataStripper.ProgressCallback> progressRef =
                                         new AtomicReference<>();
                                 doAnswer(invocation -> {
                                     progressRef.set(invocation.getArgument(0));
                                     return null;
                                 }).when(stripper).setProgressCallback(any());
                                 when(stripper.stripExifData(imageUri, "photo.jpg")).thenAnswer(invocation -> {
                                     progressRef.get().onProgress(25, "Reading image...");
                                     return imageResult;
                                 });
                                 when(stripper.stripVideoMetadata(videoUri, "movie.mp4")).thenAnswer(invocation -> {
                                     progressRef.get().onProgress(50, "Transcoding...");
                                     return videoResult;
                                 });
                             })) {
            MediaProcessor processor = new MediaProcessor(activity);
            List<MediaItem> items = List.of(
                    new MediaItem(imageUri, false, "photo.jpg"),
                    new MediaItem(videoUri, true, "movie.mp4"));

            processor.processMediaItems(items, callback(completionLatch, completeCount, percents, messages));

            assertTrue(completionLatch.await(5, TimeUnit.SECONDS));
            assertEquals(2, completeCount[0]);
            assertEquals(videoResult, processor.getLastProcessedFileUri());
            assertTrue(percents.contains(0));
            assertTrue(percents.contains(50));
            assertTrue(percents.size() >= 2);
            assertTrue(messages.stream().anyMatch(message -> message.contains("1 of 2") && message.contains("photo.jpg")));
            assertTrue(messages.stream().anyMatch(message -> message.contains("2 of 2") && message.contains("movie.mp4")));

            MetadataStripper stripper = mockedStripper.constructed().get(0);
            verify(stripper, org.mockito.Mockito.atLeastOnce()).setProgressCallback(null);
        }
    }

    @Test
    public void processMediaItems_withRealActivityExecutesUiThreadProgressLambdas() throws Exception {
        Activity activity = org.mockito.Mockito.spy(Robolectric.buildActivity(Activity.class).setup().get());
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(activity).runOnUiThread(any());
        doAnswer(invocation ->
                String.format(
                        "%s of %s · %s",
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)))
                .when(activity)
                .getString(anyInt(), any(), any(), any());

        Uri imageUri = Uri.parse("content://tests/input-image");
        Uri imageResult = Uri.parse("content://tests/processed-image");
        CountDownLatch completionLatch = new CountDownLatch(1);
        List<Integer> percents = new ArrayList<>();

        try (MockedStatic<SentryManager> ignored = mockSentry();
             MockedConstruction<MetadataStripper> mockedStripper =
                     mockConstruction(
                             MetadataStripper.class,
                             (stripper, context) -> {
                                 AtomicReference<MetadataStripper.ProgressCallback> progressRef =
                                         new AtomicReference<>();
                                 doAnswer(invocation -> {
                                     progressRef.set(invocation.getArgument(0));
                                     return null;
                                 }).when(stripper).setProgressCallback(any());
                                 when(stripper.stripExifData(imageUri, "photo.jpg")).thenAnswer(invocation -> {
                                     progressRef.get().onProgress(60, "Reading image...");
                                     return imageResult;
                                 });
                             })) {
            MediaProcessor processor = new MediaProcessor(activity);
            processor.processMediaItems(
                    List.of(new MediaItem(imageUri, false, "photo.jpg")),
                    callback(completionLatch, new int[1], percents));

            assertTrue(completionLatch.await(5, TimeUnit.SECONDS));

            assertTrue(percents.contains(0));
            assertTrue(percents.size() >= 1);
            assertEquals(imageResult, processor.getLastProcessedFileUri());
            verify(mockedStripper.constructed().get(0), org.mockito.Mockito.atLeastOnce())
                    .setProgressCallback(any());
        }
    }

    @Test
    public void processMediaItems_countsOnlySuccessfulResults() throws Exception {
        Activity activity = mockActivity();
        Uri firstUri = Uri.parse("content://tests/input-image");
        Uri secondUri = Uri.parse("content://tests/input-video");
        Uri videoResult = Uri.parse("content://tests/processed-video");
        CountDownLatch completionLatch = new CountDownLatch(1);
        int[] completeCount = new int[1];

        try (MockedStatic<SentryManager> ignored = mockSentry();
             MockedConstruction<MetadataStripper> mockedStripper =
                     mockConstruction(
                             MetadataStripper.class,
                             (stripper, context) -> {
                                 when(stripper.stripExifData(firstUri, "photo.jpg")).thenReturn(null);
                                 when(stripper.stripVideoMetadata(secondUri, "movie.mp4")).thenReturn(videoResult);
                             })) {
            MediaProcessor processor = new MediaProcessor(activity);
            processor.processMediaItems(
                    List.of(
                            new MediaItem(firstUri, false, "photo.jpg"),
                            new MediaItem(secondUri, true, "movie.mp4")),
                    callback(completionLatch, completeCount, new ArrayList<>()));

            assertTrue(completionLatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, completeCount[0]);
            assertEquals(videoResult, processor.getLastProcessedFileUri());
            verify(mockedStripper.constructed().get(0)).stripExifData(firstUri, "photo.jpg");
            verify(mockedStripper.constructed().get(0)).stripVideoMetadata(secondUri, "movie.mp4");
        }
    }

    @Test
    public void processMediaItems_recordsExceptionsAndStillCompletes() throws Exception {
        Activity activity = mockActivity();
        Uri imageUri = Uri.parse("content://tests/input-image");
        RuntimeException failure = new RuntimeException("boom");
        CountDownLatch completionLatch = new CountDownLatch(1);
        int[] completeCount = new int[1];

        try (MockedStatic<SentryManager> sentry = mockSentry();
             MockedConstruction<MetadataStripper> mockedStripper =
                     mockConstruction(
                             MetadataStripper.class,
                             (stripper, context) -> when(stripper.stripExifData(imageUri, "photo.jpg"))
                                     .thenThrow(failure))) {
            MediaProcessor processor = new MediaProcessor(activity);
            processor.processMediaItems(
                    List.of(new MediaItem(imageUri, false, "photo.jpg")),
                    callback(completionLatch, completeCount, new ArrayList<>()));

            assertTrue(completionLatch.await(5, TimeUnit.SECONDS));
            assertEquals(0, completeCount[0]);
            assertNull(processor.getLastProcessedFileUri());
            verify(mockedStripper.constructed().get(0)).setProgressCallback(null);
        }
    }

    @Test
    public void processMediaItems_handlesExceptionInThread() throws Exception {
        Activity activity = mockActivity();
        CountDownLatch completionLatch = new CountDownLatch(1);
        int[] completeCount = new int[1];

        try (MockedStatic<SentryManager> sentry = mockSentry()) {
            MediaProcessor processor = new MediaProcessor(activity);
            
            @SuppressWarnings("unchecked")
            List<MediaItem> badList = mock(List.class);
            when(badList.size()).thenThrow(new RuntimeException("forced thread error"));

            processor.processMediaItems(badList, callback(completionLatch, completeCount, new ArrayList<>()));

            assertTrue(completionLatch.await(5, TimeUnit.SECONDS));
            assertEquals(0, completeCount[0]);
        }
    }

    @Test
    public void processMediaItems_ignoresOverlappingInvocationsWhileBusy() throws Exception {
        Activity activity = mockActivity();
        Uri imageUri = Uri.parse("content://tests/input-image");
        Uri imageResult = Uri.parse("content://tests/processed-image");
        CountDownLatch enteredStripper = new CountDownLatch(1);
        CountDownLatch allowFinish = new CountDownLatch(1);
        CountDownLatch firstComplete = new CountDownLatch(1);
        CountDownLatch secondComplete = new CountDownLatch(1);
        AtomicBoolean secondCallbackCalled = new AtomicBoolean(false);

        try (MockedStatic<SentryManager> ignored = mockSentry();
             MockedConstruction<MetadataStripper> mockedStripper =
                     mockConstruction(
                             MetadataStripper.class,
                             (stripper, context) -> when(stripper.stripExifData(imageUri, "photo.jpg"))
                                     .thenAnswer(invocation -> {
                                         enteredStripper.countDown();
                                         assertTrue(allowFinish.await(5, TimeUnit.SECONDS));
                                         return imageResult;
                                     }))) {
            MediaProcessor processor = new MediaProcessor(activity);
            List<MediaItem> items = List.of(new MediaItem(imageUri, false, "photo.jpg"));

            processor.processMediaItems(items, callback(firstComplete, new int[1], new ArrayList<>()));
            assertTrue(enteredStripper.await(5, TimeUnit.SECONDS));

            processor.processMediaItems(
                    items,
                    new MediaProcessor.ProcessingCallback() {
                        @Override
                        public void onProgress(int overallPercent, String message) {
                            secondCallbackCalled.set(true);
                        }

                        @Override
                        public void onComplete(int processedCount) {
                            secondCallbackCalled.set(true);
                            secondComplete.countDown();
                        }
                    });

            assertFalse(secondComplete.await(250, TimeUnit.MILLISECONDS));
            assertFalse(secondCallbackCalled.get());

            allowFinish.countDown();
            assertTrue(firstComplete.await(5, TimeUnit.SECONDS));

            processor.processMediaItems(items, callback(secondComplete, new int[1], new ArrayList<>()));
            assertTrue(secondComplete.await(5, TimeUnit.SECONDS));
            assertEquals(1, mockedStripper.constructed().size());
            verify(mockedStripper.constructed().get(0), org.mockito.Mockito.times(2))
                    .stripExifData(imageUri, "photo.jpg");
        }
    }

    @Test
    public void processMediaItems_progressUiLambdas_areCoveredDirectly() throws Exception {
        List<Integer> percents = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        MediaProcessor.ProcessingCallback callback =
                callback(new CountDownLatch(1), new int[1], percents, messages);

        invokeProgressLambda("lambda$processMediaItems$0", callback, 12, "batch progress");
        invokeProgressLambda("lambda$processMediaItems$1", callback, 34, "detailed progress");

        assertEquals(List.of(12, 34), percents);
        assertEquals(List.of("batch progress", "detailed progress"), messages);
    }

    private static Activity mockActivity() {
        Activity activity = mock(Activity.class);
        when(activity.getApplicationContext()).thenReturn(activity);
        when(activity.getString(anyInt(), any(), any(), any())).thenAnswer(invocation ->
                String.format(
                        "%s of %s · %s",
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(0);
            runnable.run();
            return null;
        }).when(activity).runOnUiThread(any());
        return activity;
    }

    private static MockedStatic<SentryManager> mockSentry() {
        MockedStatic<SentryManager> sentry = mockStatic(SentryManager.class);
        ITransaction transaction = mock(ITransaction.class);
        ISpan span = mock(ISpan.class);
        when(transaction.startChild(anyString(), anyString())).thenReturn(span);
        sentry.when(() -> SentryManager.startTransaction("clean_multiple", "task"))
                .thenReturn(transaction);
        return sentry;
    }

    private static MediaProcessor.ProcessingCallback callback(
            CountDownLatch completionLatch,
            int[] completeCount,
            List<Integer> percents) {
        return callback(completionLatch, completeCount, percents, new ArrayList<>());
    }

    private static MediaProcessor.ProcessingCallback callback(
            CountDownLatch completionLatch,
            int[] completeCount,
            List<Integer> percents,
            List<String> messages) {
        return new MediaProcessor.ProcessingCallback() {
            @Override
            public void onProgress(int overallPercent, String message) {
                percents.add(overallPercent);
                messages.add(message);
            }

            @Override
            public void onComplete(int processedCount) {
                completeCount[0] = processedCount;
                completionLatch.countDown();
            }
        };
    }

    private static void invokeProgressLambda(
            String methodName,
            MediaProcessor.ProcessingCallback callback,
            int percent,
            String message)
            throws Exception {
        Method method =
                MediaProcessor.class.getDeclaredMethod(
                        methodName, MediaProcessor.ProcessingCallback.class, int.class, String.class);
        method.setAccessible(true);
        Object target = Modifier.isStatic(method.getModifiers()) ? null : new MediaProcessor(mockActivity());
        method.invoke(target, callback, percent, message);
    }

}
