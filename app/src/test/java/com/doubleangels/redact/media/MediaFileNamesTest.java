package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class MediaFileNamesTest {

    @Test
    public void generateShortRandomName_hasExpectedLength() {
        assertEquals(12, MediaFileNames.generateShortRandomName().length());
    }

    @Test
    public void generateShortRandomName_usesOnlyAlphanumerics() {
        for (int i = 0; i < 100; i++) {
            String name = MediaFileNames.generateShortRandomName();
            assertTrue("Unexpected character in: " + name, name.matches("[A-Za-z0-9]{12}"));
        }
    }

    @Test
    public void generateShortRandomName_producesDistinctNames() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            names.add(MediaFileNames.generateShortRandomName());
        }
        assertEquals(1000, names.size());
    }

    @Test
    public void generateShortRandomName_doesNotIncludeFileSeparatorOrSpaces() {
        String name = MediaFileNames.generateShortRandomName();
        assertTrue(!name.contains("/") && !name.contains("\\") && !name.contains(" "));
    }
}