package com.doubleangels.redact.media;

import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.doubleangels.redact.sentry.AppMetrics;
import com.doubleangels.redact.sentry.SentryManager;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SpanStatus;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Runs a batch of files through one job (Clean, Convert or Share) and does everything the three
 * have in common: a transaction with a span per file, the per-file and per-batch metrics, telling
 * a cancellation from a failure, out of memory from an unsupported format, and reporting a real
 * failure once. Each job supplies only the work on one file and its own bookkeeping, so the rules
 * for counting and cancelling can't drift apart between them again.
 */
public final class BatchRun {

    private static final String TAG = "BatchRun";

    /** How one file came back when its step returned instead of throwing. */
    public enum Outcome {
        SUCCEEDED,
        /** Not cleaned or converted, for a reason the job already knows. */
        NOT_CLEANED,
        /** In a format the phone can't open. */
        UNSUPPORTED
    }

    /** The work on one file. */
    public interface Step<T> {
        @NonNull
        Outcome run(@NonNull T item, int index) throws Exception;
    }

    /** What a job does around its files besides the work on each. */
    public interface Hooks<T> {
        /**
         * {@code item} could not be finished: {@code error} is what it threw, or null when its step
         * returned {@link Outcome#NOT_CLEANED} or {@link Outcome#UNSUPPORTED}. {@code outcome} is
         * the metric outcome ({@link AppMetrics#FAILED}, {@link AppMetrics#UNSUPPORTED} or
         * {@link AppMetrics#OUT_OF_MEMORY}). Not called for a file stopped by a cancellation.
         */
        default void onFailed(@NonNull T item, int index, @Nullable Throwable error, @NonNull String outcome) {
        }

        /** {@code item} is done, whether it succeeded or failed; not called once the batch stops. */
        default void afterItem(@NonNull T item, int index) {
        }
    }

    /** How a batch ended. */
    public record Result(int succeeded, int failed, boolean cancelled) {
    }

    private BatchRun() {
    }

    /**
     * Runs {@code step} on each of {@code items} in order until they are done or {@code stopped}
     * says the job was cancelled. A file that throws a cancellation, or comes back unfinished
     * while the job is stopped, ends the batch as cancelled; any other failure counts and the
     * batch goes on, since the next file may be fine.
     *
     * @param job the job's metric name ({@link AppMetrics#CLEAN}, {@link AppMetrics#CONVERT} or
     *     {@link AppMetrics#SHARE})
     * @param transactionName the Sentry transaction's name
     * @param alreadyFailed files the job was given but could not even start on (a share's items
     *     that could not be copied in), counted as given and failed
     */
    @NonNull
    public static <T> Result run(@NonNull String job, @NonNull String transactionName,
                                 @NonNull List<T> items, int alreadyFailed,
                                 @NonNull Predicate<T> isVideo, @NonNull BooleanSupplier stopped,
                                 @NonNull Step<T> step, @NonNull Hooks<T> hooks) {
        ITransaction transaction = SentryManager.startTransaction(transactionName, "task");
        long startedAt = SystemClock.elapsedRealtime();
        int succeeded = 0;
        int failed = 0;
        boolean stoppedByItem = false;
        AppMetrics.batchStarted(job, items.size() + alreadyFailed);
        try {
            for (int index = 0; index < items.size() && !stopped.getAsBoolean(); index++) {
                T item = items.get(index);
                boolean video = isVideo.test(item);
                ISpan span = transaction.startChild(job + "_item", "media_item");
                long itemStartedAt = SystemClock.elapsedRealtime();
                try {
                    Outcome outcome = step.run(item, index);
                    if (outcome == Outcome.SUCCEEDED) {
                        succeeded++;
                        AppMetrics.item(job, video, AppMetrics.SUCCESS,
                                SystemClock.elapsedRealtime() - itemStartedAt);
                        span.setStatus(SpanStatus.OK);
                    } else if (stopped.getAsBoolean()) {
                        // A file stopped by a cancel comes back unfinished too; it isn't a failure.
                        stoppedByItem = true;
                        span.setStatus(SpanStatus.CANCELLED);
                    } else {
                        failed++;
                        boolean unsupported = outcome == Outcome.UNSUPPORTED;
                        AppMetrics.itemNotCleaned(job, video, unsupported);
                        span.setStatus(SpanStatus.INTERNAL_ERROR);
                        hooks.onFailed(item, index, null, unsupported ? AppMetrics.UNSUPPORTED : AppMetrics.FAILED);
                    }
                } catch (Exception | OutOfMemoryError e) {
                    // Out of memory fails this file, not the app: the next file may be smaller.
                    if (stopped.getAsBoolean() || SentryManager.isUserCancellation(e)) {
                        stoppedByItem = true;
                        span.setStatus(SpanStatus.CANCELLED);
                    } else {
                        failed++;
                        boolean unsupported = UnsupportedMediaException.isCauseOf(e);
                        AppMetrics.itemFailed(job, video, e, unsupported);
                        Log.e(TAG, job + " failed on item " + index, e);
                        SentryManager.recordException(e);
                        span.setStatus(SpanStatus.INTERNAL_ERROR);
                        hooks.onFailed(item, index, e, AppMetrics.outcomeOf(e, unsupported));
                    }
                } finally {
                    span.finish();
                }
                if (stoppedByItem) {
                    break;
                }
                hooks.afterItem(item, index);
            }
        } finally {
            boolean cancelled = stoppedByItem || stopped.getAsBoolean();
            AppMetrics.batchFinished(job, succeeded, failed + alreadyFailed, cancelled);
            SentryManager.distributionDurationMs("processing.duration_ms",
                    SystemClock.elapsedRealtime() - startedAt, "operation_type", job);
            transaction.setStatus(cancelled ? SpanStatus.CANCELLED
                    : succeeded == 0 && failed + alreadyFailed > 0 ? SpanStatus.INTERNAL_ERROR
                    : SpanStatus.OK);
            transaction.finish();
        }
        return new Result(succeeded, failed, stoppedByItem || stopped.getAsBoolean());
    }
}
