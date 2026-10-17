package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

    @Test
    public void baseNameOf_dropsFolderAndExtension_andUnsafeCharacters() {
        assertEquals("IMG_20260117_093012", MediaFileNames.baseNameOf("IMG_20260117_093012.jpg"));
        assertEquals("holiday.final", MediaFileNames.baseNameOf("DCIM/holiday.final.heic"));
        assertEquals("a_b_c", MediaFileNames.baseNameOf("a:b?c.png"));
        assertEquals("hidden", MediaFileNames.baseNameOf("..hidden.jpg"));
        assertEquals("", MediaFileNames.baseNameOf(".jpg"));
        assertEquals("", MediaFileNames.baseNameOf(null));
    }

    @Test
    public void nameBeforeClash_dropsOnlyTheSuffixAClashAdded() {
        assertEquals("IMG_1.jpg", MediaFileNames.nameBeforeClash("IMG_1 (1).jpg", "IMG_1.jpg"));
        // A converted copy keeps its own extension.
        assertEquals("IMG_1.png", MediaFileNames.nameBeforeClash("IMG_1 (12).png", "IMG_1.heic"));
        assertNull(MediaFileNames.nameBeforeClash("IMG_1.jpg", "IMG_1.jpg"));
        assertNull(MediaFileNames.nameBeforeClash("IMG_2 (1).jpg", "IMG_1.jpg"));
        assertNull(MediaFileNames.nameBeforeClash("aB3dE5fG7hJ9 (1).jpg", ""));
    }

    @Test
    public void baseNameOf_capsVeryLongNames() {
        assertEquals(100, MediaFileNames.baseNameOf("x".repeat(300) + ".jpg").length());
    }
}
