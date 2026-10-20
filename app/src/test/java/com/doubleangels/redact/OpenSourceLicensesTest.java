package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.doubleangels.redact.ui.OpenSourceLicenses;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class OpenSourceLicensesTest {

    private static final File BUILD_GRADLE = new File("build.gradle");
    private static final File VERSION_CATALOG = new File("../gradle/libs.versions.toml");

    /** Fails when a library is added to app/build.gradle without an entry on the licenses screen. */
    @Test
    public void everyShippedLibraryIsCredited() throws IOException {
        Set<String> credited = new HashSet<>();
        for (OpenSourceLicenses.Entry entry : OpenSourceLicenses.ENTRIES) {
            credited.addAll(entry.artifacts());
        }
        List<String> shipped = shippedArtifacts();
        assertFalse("No implementation dependencies found in app/build.gradle", shipped.isEmpty());

        List<String> missing = new ArrayList<>();
        for (String artifact : shipped) {
            if (!credited.contains(artifact)) {
                missing.add(artifact);
            }
        }
        assertTrue("Add these to OpenSourceLicenses: " + missing, missing.isEmpty());
    }

    @Test
    public void everyLicenseTextIsBundled_andEveryEntryLinksSomewhere() throws IOException {
        Context context = RuntimeEnvironment.getApplication();
        for (OpenSourceLicenses.Entry entry : OpenSourceLicenses.ENTRIES) {
            try (InputStream in = context.getResources().openRawResource(entry.licenseText())) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertFalse(entry.name() + " has an empty license text", text.isBlank());
            }
            assertTrue(entry.name() + " needs an https link", entry.url().startsWith("https://"));
        }
    }

    @Test
    public void entriesAreSortedByName() {
        List<String> names = new ArrayList<>();
        for (OpenSourceLicenses.Entry entry : OpenSourceLicenses.ENTRIES) {
            names.add(entry.name());
        }
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        assertEquals(sorted, names);
    }

    /** {@code group:name} of every {@code implementation libs.x} line, resolved through the catalog. */
    private static List<String> shippedArtifacts() throws IOException {
        Map<String, String> catalog = new HashMap<>();
        Pattern library = Pattern.compile(
                "^([\\w-]+)\\s*=\\s*\\{\\s*(?:module\\s*=\\s*\"([^\"]+)\"|group\\s*=\\s*\"([^\"]+)\"\\s*,\\s*name\\s*=\\s*\"([^\"]+)\")");
        boolean inLibraries = false;
        for (String line : Files.readAllLines(VERSION_CATALOG.toPath())) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inLibraries = trimmed.equals("[libraries]");
                continue;
            }
            Matcher m = library.matcher(trimmed);
            if (inLibraries && m.find()) {
                String coordinates = m.group(2) != null ? m.group(2) : m.group(3) + ":" + m.group(4);
                // Gradle accessors turn dashes into dots: media3-common is libs.media3.common.
                catalog.put(m.group(1).replace('-', '.'), coordinates);
            }
        }

        List<String> shipped = new ArrayList<>();
        Pattern implementation = Pattern.compile("^implementation\\s*\\(?\\s*libs\\.([\\w.]+)");
        for (String line : Files.readAllLines(BUILD_GRADLE.toPath())) {
            Matcher m = implementation.matcher(line.trim());
            if (m.find()) {
                String coordinates = catalog.get(m.group(1));
                assertTrue("Unknown catalog alias libs." + m.group(1), coordinates != null);
                shipped.add(coordinates);
            }
        }
        return shipped;
    }
}
