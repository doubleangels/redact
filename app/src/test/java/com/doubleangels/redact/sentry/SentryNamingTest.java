package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Keeps what the app sends to Sentry consistent: breadcrumb categories in Title Case, metric names
 * as lower-case dotted words, and every metric attribute key on SentryManager's allow-list. A key
 * that is not allowed makes SentryManager drop the metric without a sound, so a typo there would
 * quietly lose the data.
 */
public class SentryNamingTest {

    private static final File SOURCES = new File("src/main/java");
    private static final Pattern LOG_EVENT = Pattern.compile("\\blogEvent\\(\\s*\"([^\"]*)\"");
    private static final Pattern METRIC_CALL =
            Pattern.compile("SentryManager\\.(count|distribution|distributionDurationMs|gauge)\\s*\\(");
    private static final Pattern TITLE_CASE = Pattern.compile("[A-Z][A-Za-z]*|[A-Z]{2,}");
    private static final Pattern METRIC_NAME = Pattern.compile("[a-z]+(_[a-z]+)*(\\.[a-z]+(_[a-z]+)*)+");

    @Test
    public void breadcrumbCategories_areTitleCase() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (Path file : sources()) {
            Matcher m = LOG_EVENT.matcher(read(file));
            while (m.find()) {
                if (!TITLE_CASE.matcher(m.group(1)).matches()) {
                    wrong.add(file.getFileName() + ": " + m.group(1));
                }
            }
        }
        assertEquals("Breadcrumb categories should be Title Case: " + wrong, 0, wrong.size());
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
                if (name == null || !METRIC_NAME.matcher(name).matches()) {
                    wrong.add(file.getFileName() + ": metric name " + args.get(0));
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
