package com.doubleangels.redact;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

/** Waits for work that background threads post back to the main looper. */
public final class TestWaits {

    private static final long TIMEOUT_MS = 20_000;

    private TestWaits() {
    }

    /** Runs the main looper until the condition holds, failing after 20 seconds. */
    public static void awaitMain(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!condition.getAsBoolean()) {
            shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for background work");
            }
            Thread.sleep(10);
        }
    }

    /**
     * Runs work on a background thread, as the app does, while the main looper keeps running. Work
     * that posts to the main thread and then waits for it would otherwise block forever when called
     * from the test thread, which is the main thread.
     */
    public static <T> T callOffMain(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        new Thread(task, "test-off-main").start();
        awaitMain(task::isDone);
        try {
            return task.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }
}
