package com.doubleangels.redact.media;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Application-scoped workers for long-running clean/convert batches. Survives activity teardown
 * (including "Don't keep activities") so media work is not aborted when {@link
 * com.doubleangels.redact.ui.MainViewModel#onCleared()} runs.
 */
public final class AppProcessingScope {

    private static volatile AppProcessingScope instance;

    private final MediaProcessor mediaProcessor;
    private final ExecutorService convertExecutor;
    private final AtomicInteger cleanGeneration = new AtomicInteger(0);
    private final AtomicInteger convertGeneration = new AtomicInteger(0);
    private final AtomicBoolean convertInProgress = new AtomicBoolean(false);

    private AppProcessingScope(@NonNull Context context) {
        Context app = context.getApplicationContext();
        mediaProcessor = new MediaProcessor(app);
        convertExecutor =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread thread = new Thread(r, "redact-convert");
                            thread.setDaemon(false);
                            return thread;
                        });
    }

    @NonNull
    public static AppProcessingScope get(@NonNull Context context) {
        if (instance == null) {
            synchronized (AppProcessingScope.class) {
                if (instance == null) {
                    instance = new AppProcessingScope(context);
                }
            }
        }
        return instance;
    }

    @NonNull
    public MediaProcessor mediaProcessor() {
        return mediaProcessor;
    }

    @NonNull
    public ExecutorService convertExecutor() {
        return convertExecutor;
    }

    @NonNull
    public AtomicInteger cleanGeneration() {
        return cleanGeneration;
    }

    @NonNull
    public AtomicInteger convertGeneration() {
        return convertGeneration;
    }

    @NonNull
    public AtomicBoolean convertInProgress() {
        return convertInProgress;
    }

    public boolean isCleanBusy() {
        return mediaProcessor.isBusy();
    }

    public boolean isConvertBusy() {
        return convertInProgress.get();
    }

    /** Visible for unit tests. */
    public static void resetForTests() {
        instance = null;
    }
}
