package com.doubleangels.redact.sentry;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

import io.sentry.Breadcrumb;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;

/**
 * Redacts URIs, filesystem paths, filenames, and GPS coordinates from Sentry payloads.
 */
public final class SentryPrivacyScrubber {

    private static final String REDACTED = "[redacted]";

    private static final Pattern CONTENT_URI =
            Pattern.compile("content://[^\\s\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_URI =
            Pattern.compile("file://[^\\s\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABSOLUTE_PATH =
            Pattern.compile("(?:/storage/|/data/)[^\\s\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAT_LON =
            Pattern.compile(
                    "(?:lat(?:itude)?|lon(?:gitude)?)\\s*[=:]\\s*[-+]?\\d+(?:\\.\\d+)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern GPS_COORD_PAIR =
            Pattern.compile(
                    "\\b[-+]?\\d{1,3}\\.\\d+\\s*,\\s*[-+]?\\d{1,3}\\.\\d+\\b");
    private static final Pattern FILENAME_IN_MESSAGE =
            Pattern.compile(
                    "((?:file(?:Name)?|resolved processed file|item:)\\s*[:=]?)\\s*[^\\s,;]+\\.[a-zA-Z0-9]{1,8}",
                    Pattern.CASE_INSENSITIVE);

    private SentryPrivacyScrubber() {
    }

    @Nullable
    public static String scrub(@Nullable String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String s = input;
        s = CONTENT_URI.matcher(s).replaceAll(REDACTED);
        s = FILE_URI.matcher(s).replaceAll(REDACTED);
        s = ABSOLUTE_PATH.matcher(s).replaceAll(REDACTED);
        s = LAT_LON.matcher(s).replaceAll(REDACTED);
        s = GPS_COORD_PAIR.matcher(s).replaceAll(REDACTED);
        s = FILENAME_IN_MESSAGE.matcher(s).replaceAll("$1 " + REDACTED);
        if (s.length() > 500) {
            s = s.substring(0, 500) + "…";
        }
        return s;
    }

    public static void scrubBreadcrumb(Breadcrumb breadcrumb) {
        if (breadcrumb == null) {
            return;
        }
        if (breadcrumb.getMessage() != null) {
            breadcrumb.setMessage(scrub(breadcrumb.getMessage()));
        }
        if (breadcrumb.getData() != null) {
            breadcrumb.getData().replaceAll((k, v) -> v instanceof String ? scrub((String) v) : v);
        }
    }

    public static void scrubEvent(SentryEvent event) {
        if (event == null) {
            return;
        }
        Message message = event.getMessage();
        if (message != null && message.getMessage() != null) {
            message.setMessage(scrub(message.getMessage()));
        }
        if (event.getThrowable() != null) {
            String msg = event.getThrowable().getMessage();
            if (msg != null) {
                event.getThrowable().setStackTrace(event.getThrowable().getStackTrace());
            }
        }
        if (event.getBreadcrumbs() != null) {
            for (Breadcrumb b : event.getBreadcrumbs()) {
                scrubBreadcrumb(b);
            }
        }
        if (event.getTags() != null) {
            event.getTags().replaceAll((k, v) -> scrubTag(k, v));
        }
    }

    private static String scrubTag(String key, String value) {
        if (value == null) {
            return null;
        }
        String lower = key != null ? key.toLowerCase(Locale.US) : "";
        if (lower.contains("file_name")
                || lower.contains("filename")
                || lower.contains("uri")
                || lower.contains("path")
                || lower.contains("location")
                || lower.contains("latitude")
                || lower.contains("longitude")
                || lower.contains("progress_percent")) {
            return REDACTED;
        }
        return scrub(value);
    }
}
