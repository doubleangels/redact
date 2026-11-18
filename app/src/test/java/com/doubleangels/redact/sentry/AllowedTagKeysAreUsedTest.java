package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The tag allow-list in {@link SentryManager} is the gate for what may be tagged, so it should
 * name only keys the app sets. This fails when a key on it is no longer set anywhere, so the gate
 * narrows along with the code instead of staying open for keys nobody uses.
 */
public class AllowedTagKeysAreUsedTest {

    private static final File SOURCES = new File("src/main/java");
    private static final Pattern ALLOW_LIST =
            Pattern.compile("ALLOWED_TAG_KEYS = new HashSet<>\\(Arrays\\.asList\\((.*?)\\)\\);", Pattern.DOTALL);
    private static final Pattern KEY_SETS =
            Pattern.compile("private static final Set<String> \\w+ = new HashSet<>\\(Arrays\\.asList\\(.*?\\)\\);",
                    Pattern.DOTALL);

    @Test
    public void everyAllowListedKey_isSetSomewhere() throws IOException {
        assertTrue("Run from the app module so " + SOURCES + " exists.", SOURCES.isDirectory());
        Path manager = SOURCES.toPath().resolve("com/doubleangels/redact/sentry/SentryManager.java");
        String managerSource = new String(Files.readAllBytes(manager), StandardCharsets.UTF_8);
        Matcher list = ALLOW_LIST.matcher(managerSource);
        assertTrue("ALLOWED_TAG_KEYS not found", list.find());
        Set<String> keys = new LinkedHashSet<>();
        Matcher key = Pattern.compile("\"([a-z_]+)\"").matcher(list.group(1));
        while (key.find()) {
            keys.add(key.group(1));
        }
        assertFalse(keys.isEmpty());

        // Every source, with SentryManager's own key lists taken out so they don't count as uses.
        StringBuilder everything = new StringBuilder(KEY_SETS.matcher(managerSource).replaceAll(""));
        try (Stream<Path> files = Files.walk(SOURCES.toPath())) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (!file.equals(manager)) {
                    everything.append(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                }
            }
        }
        List<String> unused = new ArrayList<>();
        for (String k : keys) {
            if (everything.indexOf("\"" + k + "\"") < 0) {
                unused.add(k);
            }
        }
        assertEquals("Allow-listed but never set; remove them from ALLOWED_TAG_KEYS: " + unused,
                0, unused.size());
    }
}
