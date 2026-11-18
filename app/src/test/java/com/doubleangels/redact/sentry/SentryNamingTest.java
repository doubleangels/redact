package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps what the app sends to Sentry to one naming scheme, written out above SentryManager's key
 * lists: breadcrumb categories and metric names start with an area from {@link #AREAS}, tag and
 * metric attribute keys are "group.field" with a group from {@link #GROUPS} or one of the bare
 * result keys, and every metric attribute key is on SentryManager's allow-list. A key that is not
 * allowed makes SentryManager drop the metric or tag without a sound, so a typo there would
 * quietly lose the data.
 */
@RunWith(RobolectricTestRunner.class)
public class SentryNamingTest {

    private static final File SOURCES = new File("src/main/java");
    /** The parts of the app a breadcrumb or metric can be about; each name starts with one. */
    static final Set<String> AREAS = Set.of(
            "app", "already_clean", "cache", "censor", "clean", "convert", "image", "job", "media",
            "memory", "metadata", "notification", "onboarding", "output", "permission", "scan",
            "secure_delete", "service", "share", "storage", "ui", "update", "verify", "video");

    /** The groups a state key belongs to: the areas, and what describes a file, phone or launch. */
    static final Set<String> GROUPS;

    static {
        Set<String> groups = new HashSet<>(AREAS);
        groups.addAll(Set.of("file", "device", "setting", "intent"));
        GROUPS = Set.copyOf(groups);
    }

    /** Keys that say what happened, the same in every tag and metric. */
    static final Set<String> RESULT_KEYS = Set.of("outcome", "reason", "error_type");

    private static final String WORD = "[a-z][a-z0-9]*(?:_[a-z0-9]+)*";
    private static final Pattern LOG_EVENT = Pattern.compile("\\blogEvent\\(\\s*\"([^\"]*)\"");
    private static final Pattern METRIC_CALL = Pattern.compile(
            "SentryManager\\.(count|distribution|distributionDurationMs|distributionMegabytes|gauge)\\s*\\(");
    private static final Pattern CATEGORY = Pattern.compile("(" + WORD + ")(?:\\." + WORD + ")?");
    private static final Pattern COUNTER_NAME = Pattern.compile("(" + WORD + ")\\." + WORD);
    private static final Pattern DISTRIBUTION_NAME = Pattern.compile("(" + WORD + ")\\." + WORD + "\\." + WORD);
    private static final Pattern STATE_KEY = Pattern.compile("(" + WORD + ")\\." + WORD);
    private static final Pattern SET_CUSTOM_KEY = Pattern.compile("setCustomKey\\(\\s*\"([^\"]+)\"\\s*,");
    /** Units belong in the metric's unit, not its name. */
    private static final Pattern UNIT_SUFFIX = Pattern.compile("_(ms|mb|kb|bytes|seconds|percent)$");
    /** The scrubber blanks a tag whose key mentions one of these. */
    private static final Pattern SCRUBBED_KEY = Pattern.compile("file_name|filename|uri|path|location|latitude|longitude");

    @Test
    public void breadcrumbCategories_areALowerCaseArea() throws IOException {
        List<String> wrong = new ArrayList<>();
        int calls = 0;
        for (Path file : sources()) {
            Matcher m = LOG_EVENT.matcher(read(file));
            while (m.find()) {
                calls++;
                Matcher category = CATEGORY.matcher(m.group(1));
                if (!category.matches() || !AREAS.contains(category.group(1))) {
                    wrong.add(file.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertTrue("The scan found no breadcrumbs; is the pattern still right?", calls > 50);
        assertEquals("Breadcrumb categories should be an area, optionally .sub: " + wrong, 0, wrong.size());
    }

    /** Every key a tag or metric can use: the allow-list, the app-state tags and each call site. */
    @Test
    public void tagAndAttributeKeys_followTheScheme() throws Exception {
        java.lang.reflect.Field allowedField = SentryManager.class.getDeclaredField("ALLOWED_TAG_KEYS");
        allowedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> keys = new TreeSet<>((Set<String>) allowedField.get(null));
        keys.addAll(AppStateTags.collect(org.robolectric.RuntimeEnvironment.getApplication()).keySet());
        Method allowed = SentryManager.class.getDeclaredMethod("isAllowedTagKey", String.class);
        allowed.setAccessible(true);
        List<String> wrong = new ArrayList<>();
        for (Path file : sources()) {
            Matcher m = SET_CUSTOM_KEY.matcher(read(file));
            while (m.find()) {
                keys.add(m.group(1));
                if (!(boolean) allowed.invoke(null, m.group(1))) {
                    wrong.add(file.getFileName() + ": " + m.group(1) + " is not on the allow-list");
                }
            }
        }
        for (String type : new String[] {"android.permission.READ_MEDIA_IMAGES", "android.permission.ACCESS_MEDIA_LOCATION", null}) {
            keys.add("permission." + AppMetrics.permissionType(type));
        }
        for (String key : keys) {
            if (RESULT_KEYS.contains(key)) {
                continue;
            }
            Matcher state = STATE_KEY.matcher(key);
            if (!state.matches() || !GROUPS.contains(state.group(1))) {
                wrong.add(key + " is not group.field");
            }
            if (key.length() > 32) {
                wrong.add(key + " is over Sentry's 32-character limit");
            }
            if (SCRUBBED_KEY.matcher(key).find()) {
                wrong.add(key + " would be blanked by the scrubber");
            }
        }
        assertTrue("The scan found too few keys; is the pattern still right?", keys.size() > 60);
        assertEquals("Fix these keys: " + wrong, 0, wrong.size());
    }

    /** A breadcrumb or log line in a SentryManager call, up to the end of its statement. */
    private static final Pattern SENTRY_TEXT_CALL =
            Pattern.compile("SentryManager\\.(log|logEvent)\\(([^;]*);", Pattern.DOTALL);

    /**
     * The app's own breadcrumbs describe what the app did. Taps and screens are recorded by the SDK
     * by view ID, so app messages never need to narrate what someone did.
     */
    @Test
    public void breadcrumbs_neverDescribeWhatTheUserDid() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Path file : sources()) {
            Matcher m = SENTRY_TEXT_CALL.matcher(read(file));
            while (m.find()) {
                String call = m.group(2);
                if (call.contains("\"The user") || call.contains("\"User ")) {
                    wrong.add(file.getFileName() + ": " + call.replaceAll("\\s+", " ").trim());
                }
            }
        }
        assertEquals("Breadcrumbs that record what the user did: " + wrong, 0, wrong.size());
    }

    /**
     * The "Names in Sentry" tables in README.md and site/index.html list every name the app sends,
     * so someone reading a report, or deciding whether to turn reports on, sees each one. A new
     * tag, metric or breadcrumb category fails here until both pages name it.
     */
    @Test
    public void everyName_isListedInTheReadmeAndOnTheSite() throws Exception {
        java.lang.reflect.Field allowedField = SentryManager.class.getDeclaredField("ALLOWED_TAG_KEYS");
        allowedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> names = new TreeSet<>((Set<String>) allowedField.get(null));
        names.addAll(AppStateTags.collect(org.robolectric.RuntimeEnvironment.getApplication()).keySet());
        for (Path file : sources()) {
            String source = read(file);
            Matcher crumb = LOG_EVENT.matcher(source);
            while (crumb.find()) {
                names.add(crumb.group(1));
            }
            Matcher metric = METRIC_CALL.matcher(source);
            while (metric.find()) {
                String name = literal(topLevelArguments(source, metric.end()).get(0), source);
                if (name != null) {
                    names.add(name);
                }
            }
        }
        String readme = new String(Files.readAllBytes(new File("../README.md").toPath()), StandardCharsets.UTF_8);
        String site = new String(Files.readAllBytes(new File("../site/index.html").toPath()), StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            if (!readme.contains("`" + name + "`")) {
                missing.add("README.md: " + name);
            }
            if (!site.contains("<code>" + name + "</code>")) {
                missing.add("site/index.html: " + name);
            }
        }
        assertTrue("The scan found too few names; are the patterns still right?", names.size() > 100);
        assertEquals("Add these to the Names in Sentry tables: " + missing, 0, missing.size());
    }

    @Test
    public void metricNamesAndAttributeKeys_followTheRules() throws Exception {
        Method allowed = SentryManager.class.getDeclaredMethod("isAllowedTagKey", String.class);
        allowed.setAccessible(true);
        List<String> wrong = new ArrayList<>();
        int calls = 0;
        for (Path file : sources()) {
            if (file.getFileName().toString().equals("SentryManager.java")) {
                continue; // its own overloads pass the names through
            }
            String source = read(file);
            Matcher m = METRIC_CALL.matcher(source);
            while (m.find()) {
                List<String> args = topLevelArguments(source, m.end());
                calls++;
                String name = literal(args.get(0), source);
                Matcher shape = m.group(1).equals("count")
                        ? COUNTER_NAME.matcher(name != null ? name : "")
                        : DISTRIBUTION_NAME.matcher(name != null ? name : "");
                if (!shape.matches() || !AREAS.contains(shape.group(1)) || UNIT_SUFFIX.matcher(name).find()) {
                    wrong.add(file.getFileName() + ": metric name " + args.get(0)
                            + (m.group(1).equals("count") ? " (area.event)" : " (area.event.measure, no unit)"));
                }
                // name, value, then key/value pairs (distributionDurationMs too: name, millis, key, value)
                for (int i = 2; i + 1 < args.size(); i += 2) {
                    String key = literal(args.get(i), source);
                    if (key == null || !(boolean) allowed.invoke(null, key)) {
                        wrong.add(file.getFileName() + ": " + name + " key " + args.get(i));
                    }
                }
            }
        }
        assertTrue("The scan found no metric calls; is the pattern still right?", calls > 10);
        assertEquals("Fix these metric calls: " + wrong, 0, wrong.size());
    }

    /**
     * Anonymous totals of how the app is used are allowed (CLAUDE.md), but a metric must never be
     * able to tell one phone or person apart: no attribute that names a user, device, install or
     * session, and the app's own breadcrumbs still describe the app, not the person.
     */
    @Test
    public void nothingIdentifiesAPhoneOrNarratesThePerson() throws Exception {
        Pattern usageMetric = Pattern.compile("(?i)(^|[._])(user|device|install|session|distinct)(_id)?($|[._])");
        Pattern choiceCrumb = Pattern.compile(
                "(?i)selected a tab|user chose|user tapped|activity (resumed|paused)|shared to|tile opened");
        List<String> wrong = new ArrayList<>();
        for (Path file : sources()) {
            String source = read(file);
            Matcher m = METRIC_CALL.matcher(source);
            while (m.find()) {
                String name = literal(topLevelArguments(source, m.end()).get(0), source);
                if (name != null && usageMetric.matcher(name).find()) {
                    wrong.add(file.getFileName() + ": metric " + name);
                }
            }
            Matcher crumb = Pattern.compile("SentryManager\\.(logEvent|log)\\(([^;]*)\\);").matcher(source);
            while (crumb.find()) {
                if (choiceCrumb.matcher(crumb.group(2)).find()) {
                    wrong.add(file.getFileName() + ": breadcrumb " + crumb.group(2));
                }
            }
        }
        assertEquals("These could identify a phone or narrate what the person did: " + wrong, 0, wrong.size());
    }

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES.toPath())) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /**
     * The text of a string literal argument, or of a {@code static final String} constant declared
     * in the same file; null when it is neither.
     */
    private static String literal(String arg, String source) {
        String t = arg.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        Matcher constant = Pattern.compile("static final String " + Pattern.quote(t) + "\\s*=\\s*\"([^\"]*)\"")
                .matcher(source);
        return t.matches("[A-Za-z_]\\w*") && constant.find() ? constant.group(1) : null;
    }

    /** The call's arguments, split on commas outside nested parentheses and strings. */
    private static List<String> topLevelArguments(String source, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 1;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                int j = i + 1;
                while (source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                current.append(source, i, j + 1);
                i = j;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    args.add(current.toString());
                    return args;
                }
            } else if (c == ',' && depth == 1) {
                args.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        return args;
    }
}
