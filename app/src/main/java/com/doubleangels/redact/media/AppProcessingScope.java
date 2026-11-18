package com.doubleangels.redact.media;

import android.app.Application;
import android.content.Context;

import androidx.annotation.VisibleForTesting;
import androidx.annotation.NonNull;

import com.doubleangels.redact.ui.ProcessingRepository;

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

    /** Two, so a slow cloud-backed read doesn't hold up the next screen's. */
    private static final int IO_THREADS = 2;

    private final Context app;
    private final MediaProcessor mediaProcessor;
    /** The Clean and Convert jobs' state, made on first use; see {@link #repository()}. */
    private volatile ProcessingRepository repository;
    private final ExecutorService convertExecutor;
    private final ExecutorService ioExecutor;
    private final AtomicInteger cleanGeneration = new AtomicInteger(0);
    private final AtomicInteger convertGeneration = new AtomicInteger(0);
    private final AtomicBoolean convertInProgress = new AtomicBoolean(false);
    /**
     * Set by the foreground service when Android stops it (time limit). The convert loop treats it
     * as a cancellation and reports {@code CANCELLED} through the ViewModel, rather than the
     * service moving the generation counter, which would make the loop drop its own final update.
     */
    private final AtomicBoolean convertTimedOut = new AtomicBoolean(false);
    /** Shares being cleaned, so cache sweeps and Reset leave their working files alone. */
    private final AtomicInteger activeShares = new AtomicInteger(0);

    private AppProcessingScope(@NonNull Context context) {
        app = context.getApplicationContext();
        mediaProcessor = new MediaProcessor(app);
        convertExecutor =
                Executors.newSingleThreadExecutor(
                        r -> {
                            Thread thread = new Thread(r, "redact-convert");
                            thread.setDaemon(false);
                            return thread;
                        });
        AtomicInteger ioThreads = new AtomicInteger();
        ioExecutor = Executors.newFixedThreadPool(IO_THREADS,
                r -> {
                    Thread thread = new Thread(r, "redact-io-" + ioThreads.incrementAndGet());
                    // Short reads before a job: nothing that must finish if the app goes.
                    thread.setDaemon(true);
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

    /**
     * The jobs and what the Clean and Convert tabs show, one per scope: it lives as long as the
     * workers that run them, so a job's results reach the screen showing when it ends.
     */
    @NonNull
    public ProcessingRepository repository() {
        if (repository == null) {
            synchronized (this) {
                if (repository == null) {
                    repository = new ProcessingRepository((Application) app, this);
                }
            }
        }
        return repository;
    }

    @NonNull
    public MediaProcessor mediaProcessor() {
        return mediaProcessor;
    }

    @NonNull
    public ExecutorService convertExecutor() {
        return convertExecutor;
    }

    /**
     * For the short reads a screen needs before a job (checking a pick, warning about size or
     * files that are already clean): bounded, so repeated taps queue instead of starting a thread
     * each, and named, so a report shows what was running.
     */
    @NonNull
    public ExecutorService ioExecutor() {
        return ioExecutor;
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

    @NonNull
    public AtomicBoolean convertTimedOut() {
        return convertTimedOut;
    }

    public boolean isCleanBusy() {
        return mediaProcessor.isBusy();
    }

    public boolean isConvertBusy() {
        return convertInProgress.get();
    }

    /** Counts a share that started cleaning; {@link #shareEnded} must follow it. */
    public void shareStarted() {
        activeShares.incrementAndGet();
    }

    public void shareEnded() {
        activeShares.updateAndGet(count -> Math.max(0, count - 1));
    }

    public boolean isShareRunning() {
        return activeShares.get() > 0;
    }

    /** Whether a share, clean or convert is running: the one question to ask before touching their files. */
    public boolean isAnyJobRunning() {
        return isShareRunning() || repository().isProcessingActive();
    }

    @VisibleForTesting
    @NonNull
    public AtomicInteger activeSharesForTests() {
        return activeShares;
    }

    /** Visible for unit tests. */
    public static void resetForTests() {
        instance = null;
    }
}
