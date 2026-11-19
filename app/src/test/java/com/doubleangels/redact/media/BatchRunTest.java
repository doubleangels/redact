package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.sentry.AppMetrics;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** The counting and cancelling rules Clean, Convert and Share now share. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class BatchRunTest {

    private final List<String> failures = new ArrayList<>();
    private final List<Integer> finished = new ArrayList<>();

    private final BatchRun.Hooks<String> hooks = new BatchRun.Hooks<>() {
        @Override
        public void onFailed(@NonNull String item, int index, @Nullable Throwable error, @NonNull String outcome) {
            failures.add(item + ":" + outcome + ":" + (error == null ? "-" : error.getClass().getSimpleName()));
        }

        @Override
        public void afterItem(@NonNull String item, int index) {
            finished.add(index);
        }
    };

    private BatchRun.Result run(List<String> items, AtomicBoolean stopped, BatchRun.Step<String> step) {
        return BatchRun.run(AppMetrics.CLEAN, "test", items, 0, item -> false, stopped::get, step, hooks);
    }

    @Test
    public void everyKindOfFailure_isCountedOnce_andTheBatchGoesOn() {
        BatchRun.Result result = run(List.of("ok", "notCleaned", "unsupported", "throws", "oom", "ok2"),
                new AtomicBoolean(), (item, index) -> {
                    switch (item) {
                        case "notCleaned": return BatchRun.Outcome.NOT_CLEANED;
                        case "unsupported": return BatchRun.Outcome.UNSUPPORTED;
                        case "throws": throw new IOException("/storage/x.jpg");
                        case "oom": throw new OutOfMemoryError();
                        default: return BatchRun.Outcome.SUCCEEDED;
                    }
                });

        assertEquals(2, result.succeeded());
        assertEquals(4, result.failed());
        assertFalse(result.cancelled());
        assertEquals(List.of("notCleaned:failed:-", "unsupported:unsupported:-",
                "throws:failed:IOException", "oom:out_of_memory:OutOfMemoryError"), failures);
        assertEquals(List.of(0, 1, 2, 3, 4, 5), finished);
    }

    @Test
    public void aCancellationThrownByAFile_endsTheBatchAsCancelled_withoutCountingAFailure() {
        BatchRun.Result result = run(List.of("a", "b", "c"), new AtomicBoolean(), (item, index) -> {
            if (item.equals("b")) {
                throw new ProcessingCancelledException("stopped");
            }
            return BatchRun.Outcome.SUCCEEDED;
        });

        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
        assertTrue(result.cancelled());
        assertTrue(failures.isEmpty());
        assertEquals("the stopped file is not finished", List.of(0), finished);
    }

    @Test
    public void aFileThatComesBackUnfinishedAfterACancel_isNotAFailure() {
        AtomicBoolean stopped = new AtomicBoolean();
        BatchRun.Result result = run(List.of("a", "b", "c"), stopped, (item, index) -> {
            if (item.equals("b")) {
                stopped.set(true);
                return BatchRun.Outcome.NOT_CLEANED;
            }
            return BatchRun.Outcome.SUCCEEDED;
        });

        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
        assertTrue(result.cancelled());
        assertTrue(failures.isEmpty());
    }

    @Test
    public void aStoppedJob_startsNoFile() {
        AtomicBoolean stopped = new AtomicBoolean(true);
        boolean[] ran = {false};
        BatchRun.Result result = run(List.of("a"), stopped, (item, index) -> {
            ran[0] = true;
            return BatchRun.Outcome.SUCCEEDED;
        });

        assertFalse(ran[0]);
        assertTrue(result.cancelled());
    }

    @Test
    public void filesThatCouldNotStart_areCountedOnlyInTheBatch_notInTheResult() {
        BatchRun.Result result = BatchRun.run(AppMetrics.SHARE, "test", List.of("a"), 2, item -> true,
                () -> false, (item, index) -> BatchRun.Outcome.SUCCEEDED, new BatchRun.Hooks<>() { });

        assertEquals(1, result.succeeded());
        assertEquals("the job adds the ones it already knows about", 0, result.failed());
    }
}
