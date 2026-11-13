package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A library decides what goes into an exception's message, so it can hold a path, a URI, a file
 * name or a metadata value. Text sent to Sentry names the exception class instead, and this test
 * fails when a call to {@code SentryManager.log}, {@code logEvent} or {@code recordException} puts
 * an exception's own text into what it sends.
 */
public class SentryExceptionTextTest {

    private static final File SOURCES = new File("src/main/java");

    private static final Pattern SENTRY_CALL =
            Pattern.compile("SentryManager\\.(log|logEvent|recordException)\\s*\\(");
    private static final Pattern CATCH_PARAMETER =
            Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?[\\w.]+(?:\\s*\\|\\s*[\\w.]+)*\\s+(\\w+)\\s*\\)");
    private static final Pattern EXCEPTION_TEXT =
            Pattern.compile("\\.(getMessage|getLocalizedMessage|toString|getStackTrace)\\s*\\(\\s*\\)"
                    + "|String\\.valueOf\\s*\\(|Log\\.getStackTraceString\\s*\\(");

    @Test
    public void noSentryCallSendsExceptionText() throws IOException {
        assertTrue("Run from the app module so " + SOURCES + " exists.", SOURCES.isDirectory());
        List<String> leaks = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES.toPath())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                for (String leak : findLeaks(source)) {
                    leaks.add(SOURCES.toPath().relativize(file) + ": " + leak);
                }
            }
        }
        assertEquals("Log e.getClass().getSimpleName() instead of exception text:\n"
                + String.join("\n", leaks), 0, leaks.size());
    }

    @Test
    public void detector_flagsTheMessageOfAnException() {
        assertEquals(1, findLeaks("try {} catch (IOException e) {"
                + " SentryManager.log(\"Failed: \" + e.getMessage() + \".\"); }").size());
    }

    @Test
    public void detector_flagsTextSplitAcrossLines() {
        assertEquals(1, findLeaks("try {} catch (IOException | RuntimeException failed) {\n"
                + "  SentryManager.logEvent(\"video\",\n      \"Failed: \"\n"
                + "          + failed.getLocalizedMessage());\n}").size());
    }

    @Test
    public void detector_flagsToStringAndPlainConcatenation() {
        assertEquals(1, findLeaks("catch (Exception e) { SentryManager.log(\"x\" + e.toString()); }").size());
        assertEquals(1, findLeaks("catch (Exception e) { SentryManager.log(\"x\" + e + \".\"); }").size());
        assertEquals(1, findLeaks("catch (Exception e) { SentryManager.log(String.valueOf(e)); }").size());
    }

    @Test
    public void detector_flagsAWrapperThatCopiesTheMessage() {
        assertEquals(1, findLeaks("catch (Exception e) {"
                + " SentryManager.recordException(new Exception(\"Failed: \" + e.getMessage(), e)); }").size());
    }

    @Test
    public void detector_acceptsTheClassNameAndTheExceptionObject() {
        assertEquals(0, findLeaks("catch (IOException e) {"
                + " SentryManager.log(\"Failed: \" + e.getClass().getSimpleName() + \".\");"
                + " SentryManager.recordException(e);"
                + " SentryManager.recordException(new Exception(\"Failed.\", e)); }").size());
    }

    @Test
    public void detector_ignoresParenthesesAndKeywordsInsideStrings() {
        assertEquals(0, findLeaks("catch (IOException e) {"
                + " SentryManager.log(\"A (toString()) e.getMessage() note.\"); }").size());
    }

    /** Returns one entry for each Sentry call whose arguments carry an exception's text. */
    static List<String> findLeaks(String source) {
        Set<String> caught = new HashSet<>();
        Matcher catches = CATCH_PARAMETER.matcher(source);
        while (catches.find()) {
            caught.add(catches.group(1));
        }
        List<String> leaks = new ArrayList<>();
        Matcher call = SENTRY_CALL.matcher(source);
        while (call.find()) {
            String code = codeOutsideStrings(source, call.end());
            boolean recordsAnException = "recordException".equals(call.group(1));
            if (hasExceptionText(code, caught, recordsAnException)) {
                leaks.add(source.substring(call.start(), Math.min(source.length(), call.start() + 120))
                        .replaceAll("\\s+", " "));
            }
        }
        return leaks;
    }

    private static boolean hasExceptionText(String code, Set<String> caught, boolean recordsAnException) {
        if (EXCEPTION_TEXT.matcher(code).find()) {
            return true;
        }
        if (recordsAnException) {
            // recordException(e) and new Exception("...", e) send the object, which the
            // beforeSend scrubber handles; only text built from it is a leak here.
            return false;
        }
        for (String name : caught) {
            Matcher use = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\b(?!\\s*\\.\\s*getClass)")
                    .matcher(code);
            if (use.find()) {
                return true;
            }
        }
        return false;
    }

    /** The call's argument code up to its closing parenthesis, with string and char literals blanked. */
    private static String codeOutsideStrings(String source, int start) {
        StringBuilder code = new StringBuilder();
        int depth = 1;
        for (int i = start; i < source.length() && depth > 0; i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i, c);
                code.append(' ');
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            code.append(c);
        }
        return code.toString();
    }

    private static int endOfLiteral(String source, int open, char quote) {
        for (int i = open + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote) {
                return i;
            }
        }
        return source.length();
    }
}
