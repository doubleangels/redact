package com.doubleangels.redact.media;

import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * A clean, convert or share stopped because the user cancelled it, the batch was replaced, or
 * Android stopped the work. Not a failure, so it is never reported as one.
 */
public final class ProcessingCancelledException extends IOException {

    public ProcessingCancelledException(@Nullable String message) {
        super(message);
    }

    public ProcessingCancelledException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
