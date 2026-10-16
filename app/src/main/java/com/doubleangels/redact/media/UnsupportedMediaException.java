package com.doubleangels.redact.media;

import androidx.annotation.Nullable;

import java.io.IOException;

/**
 * The input is not something this device can decode: a vector or non-image file shared as
 * {@code image/*}, an unknown container, or a codec no decoder here supports. The item is skipped
 * and counted as failed; this is a property of the input, so it is not reported as a crash.
 */
public final class UnsupportedMediaException extends IOException {

    public UnsupportedMediaException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /** Whether {@code t} or anything in its cause chain is an {@link UnsupportedMediaException}. */
    public static boolean isCauseOf(@Nullable Throwable t) {
        // Bounded walk: a cyclic cause chain must not hang error handling.
        for (int depth = 0; t != null && depth < 16; depth++, t = t.getCause()) {
            if (t instanceof UnsupportedMediaException) {
                return true;
            }
        }
        return false;
    }
}
