package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.doubleangels.redact.media.CleanFailedException;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;

/**
 * The app's own failures keep their message in Sentry, so an issue says what went wrong; a
 * library's message, which can hold a path or a file name, is still removed.
 */
public class SentryOwnFailureMessagesTest {

    private static final File SOURCES = new File("src/main/java");
    private static final Pattern CONSTRUCTION = Pattern.compile("new CleanFailedException\\(");
    /** A plain string literal, optionally followed by a cause variable, and nothing else. */
    private static final Pattern LITERAL_ONLY =
            Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"(\\s*,\\s*[A-Za-z_]\\w*)?\\s*\\)");
    private static final Pattern LITERAL = Pattern.compile("new CleanFailedException\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES.toPath())) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Every message the app throws one with. */
    private static TreeSet<String> messages() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        for (Path file : sources()) {
            Matcher m = LITERAL.matcher(read(file));
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    private static SentryException sent(String type, String module, String value) {
        SentryException ex = new SentryException();
        ex.setType(type);
        ex.setModule(module);
        ex.setValue(value);
        return ex;
    }

    /** Only fixed text may go in: a message built from another exception's would be kept too. */
    @Test
    public void everyCleanFailedException_isBuiltFromAPlainLiteral() throws IOException {
        List<String> wrong = new ArrayList<>();
        int count = 0;
        for (Path file : sources()) {
            String source = read(file);
            Matcher m = CONSTRUCTION.matcher(source);
            while (m.find()) {
                count++;
                Matcher arg = LITERAL_ONLY.matcher(source);
                arg.region(m.end(), source.length());
                if (!arg.lookingAt()) {
                    int end = Math.min(source.length(), m.end() + 80);
                    wrong.add(file.getFileName() + ": " + source.substring(m.start(), end));
                }
            }
        }
        assertTrue("The scan found no constructions; is the pattern still right?", count > 10);
        assertEquals("Build these from a plain literal, or throw IOException: " + wrong, 0, wrong.size());
    }

    @Test
    public void eachOwnFailureMessage_reachesSentryAsItIs() throws IOException {
        TreeSet<String> messages = messages();
        assertTrue(messages.containsAll(Arrays.asList(
                "Video metadata verification failed", "Converted image verification failed",
                "Video conversion stalled")));
        for (String message : messages) {
            // As a debug build reports it, and as a minified one does (renamed, no package).
            SentryException debug = sent("CleanFailedException", "com.doubleangels.redact.media", message);
            SentryException release = sent(CleanFailedException.class.getName(), null, message);
            SentryEvent event = new SentryEvent();
            event.setExceptions(new ArrayList<>(Arrays.asList(debug, release)));

            SentryPrivacyScrubber.scrubEvent(event);

            assertEquals(message, debug.getValue());
            assertEquals(message, release.getValue());
        }
    }

    /** A library's text in the cause, or in a plain IOException's message, is still removed. */
    @Test
    public void aLibrarysMessage_isStillRemoved() {
        SentryException own = sent("CleanFailedException", "com.doubleangels.redact.media",
                "Failed to clean video safely");
        SentryException cause = sent("IllegalStateException", "java.lang",
                "Failed to open /storage/emulated/0/DCIM/Trip to Kyiv/IMG_1.mp4");
        SentryException wrapped = sent("IOException", "java.io",
                "Video conversion failed: /storage/emulated/0/DCIM/IMG_2.mp4");
        SentryEvent event = new SentryEvent();
        event.setExceptions(new ArrayList<>(Arrays.asList(cause, own, wrapped)));

        SentryPrivacyScrubber.scrubEvent(event);

        assertEquals("Failed to clean video safely", own.getValue());
        assertEquals(SentryPrivacyScrubber.MESSAGE_REMOVED, cause.getValue());
        assertEquals(SentryPrivacyScrubber.MESSAGE_REMOVED, wrapped.getValue());
        assertFalse(own.getValue().contains("/storage"));
    }
}
