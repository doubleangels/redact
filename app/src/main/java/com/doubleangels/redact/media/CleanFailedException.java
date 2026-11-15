package com.doubleangels.redact.media;

import androidx.annotation.NonNull;

import java.io.IOException;

/**
 * A clean, convert or check that failed for a reason this app's own code names, in fixed text
 * written in this codebase ("Video metadata verification failed"). Its message never holds a
 * library's text, a path or a file name, so {@code SentryPrivacyScrubber} keeps it: the issue says
 * what went wrong, not just where. Anything whose message includes another exception's text is
 * thrown as a plain {@link IOException}, whose message the scrubber removes.
 */
public final class CleanFailedException extends IOException {

    public CleanFailedException(@NonNull String message) {
        super(message);
    }

    /** {@code cause} is reported as its own exception, and its message is removed as usual. */
    public CleanFailedException(@NonNull String message, @NonNull Throwable cause) {
        super(message, cause);
    }
}
