package com.doubleangels.redact.sentry;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.sentry.Breadcrumb;
import io.sentry.SentryAttributeType;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.SentryTransaction;
import io.sentry.SentryLogEvent;
import io.sentry.SentryMetricsEvent;

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
    private static final Pattern WINDOWS_PATH =
            Pattern.compile("(?:[A-Za-z]:\\\\|\\\\\\\\)[^\\s\"']+");
    private static final Pattern LAT_LON =
            Pattern.compile(
                    "(?:lat(?:itude)?|lon(?:gitude)?)\\s*[=:]\\s*[-+]?\\d+(?:\\.\\d+)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern GPS_COORD_PAIR =
            Pattern.compile(
                    "\\b[-+]?(?:90(?:\\.0+)?|[1-8]?\\d(?:\\.\\d+)?)\\s*,\\s*[-+]?(?:180(?:\\.0+)?|1[0-7]\\d(?:\\.\\d+)?|0?\\d{1,2}(?:\\.\\d+)?)\\b");
    /** Video location format e.g. +39.6594-104.9620 */
    private static final Pattern GPS_VIDEO_LOCATION =
            Pattern.compile("\\+?[-]?\\d{1,3}\\.\\d+[-+]\\d{1,3}\\.\\d+");
    private static final Pattern FILENAME_IN_MESSAGE =
            Pattern.compile(
                    "((?:file(?:Name)?|resolved processed file|item:)\\s*[:=]?)\\s*[^\\s,;]+\\.[a-zA-Z0-9]{1,8}",
                    Pattern.CASE_INSENSITIVE);
    /**
     * Catches a media filename anywhere in text, not just after one of the literal prefixes
     * {@link #FILENAME_IN_MESSAGE} requires (e.g. a raw filename embedded in a vendor
     * IOException/FileNotFoundException message). Scoped to this app's own media extensions
     * so it doesn't also redact harmless tokens like source file names in stack traces.
     */
    private static final Pattern MEDIA_FILENAME =
            Pattern.compile(
                    "\\b[\\w.\\-]+\\.(?:jpe?g|png|webp|heif?|gif|bmp|apng"
                            + "|mp4|mov|m4v|avi|mkv|webm|3gp|vp[89]|bin)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final String[] PII_ATTRIBUTE_KEYS = {
            "user.id", "user.name", "user.email", "user_id", "user_name", "user_email"
    };

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
        s = WINDOWS_PATH.matcher(s).replaceAll(REDACTED);
        s = LAT_LON.matcher(s).replaceAll(REDACTED);
        s = GPS_COORD_PAIR.matcher(s).replaceAll(REDACTED);
        s = GPS_VIDEO_LOCATION.matcher(s).replaceAll(REDACTED);
        s = FILENAME_IN_MESSAGE.matcher(s).replaceAll("$1 " + REDACTED);
        s = MEDIA_FILENAME.matcher(s).replaceAll(REDACTED);
        if (s.length() > 500) {
            s = s.substring(0, 500) + "…";
        }
        return s;
    }

    public static void scrubLog(@Nullable SentryLogEvent logEvent) {
        if (logEvent == null) {
            return;
        }
        if (logEvent.getBody() != null) {
            logEvent.setBody(scrub(logEvent.getBody()));
        }
        if (logEvent.getAttributes() != null) {
            for (String key : new java.util.ArrayList<>(logEvent.getAttributes().keySet())) {
                if (isPiiAttributeKey(key)) {
                    logEvent.getAttributes().remove(key);
                    continue;
                }
                SentryLogEventAttributeValue value = logEvent.getAttributes().get(key);
                if (value != null
                        && SentryAttributeType.STRING.apiName().equals(value.getType())) {
                    Object raw = value.getValue();
                    if (raw instanceof String stringValue) {
                        logEvent.setAttribute(
                                key,
                                new SentryLogEventAttributeValue(
                                        SentryAttributeType.STRING, scrub(stringValue)));
                    }
                }
            }
        }
    }

    public static void scrubMetric(@Nullable SentryMetricsEvent metric) {
        if (metric == null) {
            return;
        }
        if (metric.getAttributes() != null) {
            for (String key : new java.util.ArrayList<>(metric.getAttributes().keySet())) {
                if (isPiiAttributeKey(key) || isSensitiveMetricAttributeKey(key)) {
                    metric.getAttributes().remove(key);
                    continue;
                }
                SentryLogEventAttributeValue value = metric.getAttributes().get(key);
                if (value != null
                        && SentryAttributeType.STRING.apiName().equals(value.getType())) {
                    Object raw = value.getValue();
                    if (raw instanceof String stringValue) {
                        metric.setAttribute(
                                key,
                                new SentryLogEventAttributeValue(
                                        SentryAttributeType.STRING, scrub(stringValue)));
                    }
                }
            }
        }
    }

    public static void scrubBreadcrumb(Breadcrumb breadcrumb) {
        if (breadcrumb == null) {
            return;
        }
        if (breadcrumb.getMessage() != null) {
            breadcrumb.setMessage(scrub(breadcrumb.getMessage()));
        }
        if (breadcrumb.getData() != null) {
            breadcrumb.getData().replaceAll((k, v) -> scrubValue(v));
        }
    }

    public static void scrubTransaction(SentryTransaction transaction) {
        if (transaction == null) {
            return;
        }
        if (transaction.getSpans() != null) {
            for (SentrySpan span : transaction.getSpans()) {
                if (span.getData() != null) {
                    span.getData().replaceAll((k, v) -> scrubValue(v));
                }
                if (span.getTags() != null) {
                    span.getTags().replaceAll((k, v) -> scrubTag(k, v));
                }
            }
        }
        if (transaction.getTags() != null) {
            transaction.getTags().replaceAll((k, v) -> scrubTag(k, v));
        }
        scrubStringObjectMap(transaction.getExtras());
        if (transaction.getContexts() != null) {
            for (Map.Entry<String, Object> entry : transaction.getContexts().entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> map) {
                    scrubStringObjectMap(castToStringObjectMap(map));
                }
            }
        }
        if (transaction.getUnknown() != null) {
            scrubStringObjectMap(transaction.getUnknown());
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
        if (event.getExceptions() != null) {
            for (io.sentry.protocol.SentryException ex : event.getExceptions()) {
                if (ex.getValue() != null) {
                    ex.setValue(scrub(ex.getValue()));
                }
                if (ex.getModule() != null) {
                    ex.setModule(scrub(ex.getModule()));
                }
                scrubStacktrace(ex.getStacktrace());
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
        if (event.getThreads() != null) {
            for (io.sentry.protocol.SentryThread thread : event.getThreads()) {
                scrubStacktrace(thread.getStacktrace());
            }
        }
        scrubStringObjectMap(event.getExtras());
        if (event.getContexts() != null) {
            for (Map.Entry<String, Object> entry : event.getContexts().entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> map) {
                    scrubStringObjectMap(castToStringObjectMap(map));
                }
            }
        }
        if (event.getUser() != null) {
            if (event.getUser().getUsername() != null) {
                event.getUser().setUsername(scrub(event.getUser().getUsername()));
            }
            if (event.getUser().getEmail() != null) {
                event.getUser().setEmail(scrub(event.getUser().getEmail()));
            }
            if (event.getUser().getIpAddress() != null) {
                event.getUser().setIpAddress(scrub(event.getUser().getIpAddress()));
            }
        }
    }

    private static void scrubStringObjectMap(@Nullable Map<String, Object> map) {
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            entry.setValue(scrubValue(entry.getValue()));
        }
    }

    @Nullable
    private static Object scrubValue(@Nullable Object value) {
        if (value instanceof String stringValue) {
            return scrub(stringValue);
        }
        if (value instanceof Map<?, ?> nested) {
            scrubStringObjectMap(castToStringObjectMap(nested));
            return nested;
        }
        if (value instanceof java.util.List<?> list) {
            java.util.List<Object> scrubbed = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                scrubbed.add(scrubValue(item));
            }
            return scrubbed;
        }
        if (value instanceof Object[] array) {
            Object[] scrubbed = array.clone();
            for (int i = 0; i < scrubbed.length; i++) {
                scrubbed[i] = scrubValue(scrubbed[i]);
            }
            return scrubbed;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToStringObjectMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static void scrubStacktrace(@Nullable io.sentry.protocol.SentryStackTrace stacktrace) {
        if (stacktrace == null || stacktrace.getFrames() == null) {
            return;
        }
        for (io.sentry.protocol.SentryStackFrame frame : stacktrace.getFrames()) {
            if (frame.getFilename() != null) {
                frame.setFilename(scrub(frame.getFilename()));
            }
            if (frame.getAbsPath() != null) {
                frame.setAbsPath(scrub(frame.getAbsPath()));
            }
            if (frame.getModule() != null) {
                frame.setModule(scrub(frame.getModule()));
            }
            if (frame.getFunction() != null) {
                frame.setFunction(scrub(frame.getFunction()));
            }
        }
    }

    private static boolean isPiiAttributeKey(@Nullable String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        for (String pii : PII_ATTRIBUTE_KEYS) {
            if (lower.equals(pii)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSensitiveMetricAttributeKey(@Nullable String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.US);
        return lower.contains("file")
                || lower.contains("path")
                || lower.contains("uri")
                || lower.contains("location")
                || lower.contains("latitude")
                || lower.contains("longitude");
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
