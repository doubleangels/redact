package com.doubleangels.redact.sentry;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
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
            Pattern.compile("(?:/storage/|/data/|/sdcard/|/mnt/)[^\\s\"']+", Pattern.CASE_INSENSITIVE);
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
    /**
     * The words a logged filename follows ("fileName=IMG_1.jpg", "resolved processed file a.png").
     * Only these words are a pattern; {@link #redactNamedFiles} finds the name after them. A
     * pattern for the name as well rescans the rest of a long run with no extension from every
     * place the words occur ("file!file!file!…"), which is quadratic in the length of the text.
     */
    private static final Pattern FILENAME_PREFIX =
            Pattern.compile(
                    "((?:file(?:Name)?|resolved processed file|item:)\\s*[:=]?)\\s*",
                    Pattern.CASE_INSENSITIVE);
    /** The longest name a file system allows, so the filename patterns look no further. */
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_EXTENSION_LENGTH = 8;
    /**
     * Catches a media filename anywhere in text, not just after one of the literal prefixes
     * {@link #FILENAME_PREFIX} requires (e.g. a raw filename embedded in a vendor
     * IOException/FileNotFoundException message). Scoped to this app's own media extensions
     * so it doesn't also redact harmless tokens like source file names in stack traces. A name can
     * hold spaces and brackets ("IMG_1 (1).jpg", "My Trip.jpg") and the text does not show where
     * it starts, so up to three words before it go too: losing a little context is better than
     * sending part of a name.
     */
    private static final Pattern MEDIA_FILENAME =
            Pattern.compile(
                    "(?:[\\w.\\-()'\\[\\]]{1,255} ){0,3}[\\w.\\-()'\\[\\]]{0,255}"
                            + "\\.(?:jpe?g|png|webp|heic|heics|heif|avif|dng|tiff?|jxl|gif|bmp|apng"
                            + "|mp4|mov|m4v|avi|mkv|webm|3gp|vp[89]|bin)\\b",
                    Pattern.CASE_INSENSITIVE);

    /** Eight times the 500 characters a scrubbed text keeps, room for what redaction removes. */
    private static final int MAX_SCRUBBED_INPUT = 4000;

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
        // At most 500 characters survive, so a longer text is cut before the patterns run on it,
        // which keeps them quick on text of any length. The cut is at whitespace, so it never
        // splits a filename and leaves its first part unredacted.
        if (s.length() > MAX_SCRUBBED_INPUT) {
            int cut = s.lastIndexOf(' ', MAX_SCRUBBED_INPUT);
            s = s.substring(0, cut > 0 ? cut : MAX_SCRUBBED_INPUT);
        }
        s = CONTENT_URI.matcher(s).replaceAll(REDACTED);
        s = FILE_URI.matcher(s).replaceAll(REDACTED);
        s = ABSOLUTE_PATH.matcher(s).replaceAll(REDACTED);
        s = WINDOWS_PATH.matcher(s).replaceAll(REDACTED);
        s = LAT_LON.matcher(s).replaceAll(REDACTED);
        s = GPS_COORD_PAIR.matcher(s).replaceAll(REDACTED);
        s = GPS_VIDEO_LOCATION.matcher(s).replaceAll(REDACTED);
        s = redactNamedFiles(s);
        s = MEDIA_FILENAME.matcher(s).replaceAll(REDACTED);
        if (s.length() > 500) {
            s = s.substring(0, 500) + "…";
        }
        return s;
    }

    /**
     * Replaces the name after each {@link #FILENAME_PREFIX} with {@link #REDACTED}: the run up to
     * whitespace, a comma or a semicolon, through the last extension within
     * {@link #MAX_NAME_LENGTH} characters. When "fileName" is followed by no such name, the name
     * may start right after "file" instead ("fileName.txt"), as a pattern would backtrack to.
     */
    private static String redactNamedFiles(String s) {
        Matcher m = FILENAME_PREFIX.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        int copied = 0;
        int from = 0;
        while (from < s.length() && m.find(from)) {
            int nameStart = m.end();
            int end = nameEnd(s, nameStart);
            if (end < 0) {
                int shortest = m.start() + shortestPrefixLength(m.group(1));
                if (shortest < nameStart && runEnd(s, shortest) >= nameStart) {
                    nameStart = shortest;
                    end = nameEnd(s, nameStart);
                }
            }
            if (end < 0) {
                from = m.start() + 1;
                continue;
            }
            out.append(s, copied, m.start())
                    .append(s, m.start(), Math.min(m.end(1), nameStart))
                    .append(' ')
                    .append(REDACTED);
            copied = end;
            from = end;
        }
        return copied == 0 ? s : out.append(s, copied, s.length()).toString();
    }

    private static int shortestPrefixLength(String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        if (lower.startsWith("file")) {
            return "file".length();
        }
        return lower.startsWith("item:") ? "item:".length() : "resolved processed file".length();
    }

    /** Where a filename starting at {@code start} ends, after its extension, or -1 if it has none. */
    private static int nameEnd(String s, int start) {
        int lastDot = -1;
        int limit = Math.min(s.length(), start + MAX_NAME_LENGTH + 1);
        for (int i = start; i < limit && !isNameEnd(s.charAt(i)); i++) {
            if (s.charAt(i) == '.' && i > start && i + 1 < s.length()
                    && isAsciiLetterOrDigit(s.charAt(i + 1))) {
                lastDot = i;
            }
        }
        if (lastDot < 0) {
            return -1;
        }
        int end = lastDot + 1;
        while (end < s.length() && end - lastDot <= MAX_EXTENSION_LENGTH
                && isAsciiLetterOrDigit(s.charAt(end))) {
            end++;
        }
        return end;
    }

    /** The end of the run of name characters starting at {@code start}. */
    private static int runEnd(String s, int start) {
        int i = start;
        while (i < s.length() && !isNameEnd(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** What ends a name: a comma, a semicolon or whitespace, as {@code [\s,;]} matches it. */
    private static boolean isNameEnd(char c) {
        return c == ',' || c == ';' || c == ' ' || c == '\t' || c == '\n' || c == 0x0B
                || c == '\f' || c == '\r';
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * Scrubs a class, package or method name. Only URIs and paths are removed: the filename
     * patterns would also match package segments such as {@code media3.extractor.mp4} or
     * {@code integration.webp}, which breaks deobfuscation and splits issue grouping.
     */
    @Nullable
    static String scrubCodeIdentifier(@Nullable String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String s = input;
        s = CONTENT_URI.matcher(s).replaceAll(REDACTED);
        s = FILE_URI.matcher(s).replaceAll(REDACTED);
        s = ABSOLUTE_PATH.matcher(s).replaceAll(REDACTED);
        s = WINDOWS_PATH.matcher(s).replaceAll(REDACTED);
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
        transaction.setUser(null);
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
                    ex.setModule(scrubCodeIdentifier(ex.getModule()));
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
        // The SDK fills in a random ID per installation, which would tie every report from one
        // device together. Logs and metrics already drop it; events go without a user at all.
        event.setUser(null);
    }

    /**
     * Scrubs a user feedback event: the event itself, plus the feedback context, which is not a
     * map and so is skipped by {@link #scrubEvent}. Name, email and URL are dropped outright.
     */
    public static void scrubFeedbackEvent(SentryEvent event) {
        scrubEvent(event);
        if (event == null || event.getContexts() == null) {
            return;
        }
        io.sentry.protocol.Feedback feedback = event.getContexts().getFeedback();
        if (feedback == null) {
            return;
        }
        feedback.setMessage(scrub(feedback.getMessage() != null ? feedback.getMessage() : ""));
        feedback.setName(null);
        feedback.setContactEmail(null);
        feedback.setUrl(null);
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
                frame.setModule(scrubCodeIdentifier(frame.getModule()));
            }
            if (frame.getFunction() != null) {
                frame.setFunction(scrubCodeIdentifier(frame.getFunction()));
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
