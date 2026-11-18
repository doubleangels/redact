package com.doubleangels.redact.media;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** The private copies a share works from, which hold the original media until they are deleted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class InboundSnapshotsTest {

    private final Context app = RuntimeEnvironment.getApplication();

    private Uri source(String name, String content) throws Exception {
        File f = new File(app.getFilesDir(), name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return Uri.fromFile(f);
    }

    @Test
    public void copiesEachItem_andCountsTheOnesThatCannotBeRead() throws Exception {
        Uri photo = source("a.png", "picture bytes");
        Uri gone = Uri.fromFile(new File(app.getFilesDir(), "missing.jpg"));
        List<File> kept = new ArrayList<>();
        AtomicInteger failed = new AtomicInteger(7);

        List<Uri> copies = InboundSnapshots.copyAll(app, List.of(photo, gone), () -> true, kept::add, failed);

        assertEquals(1, copies.size());
        assertEquals(1, failed.get());
        File copy = new File(copies.get(0).getPath());
        assertTrue(copy.getName().startsWith(InboundSnapshots.PREFIX));
        assertTrue("named for what it is", copy.getName().endsWith(".png"));
        assertEquals(app.getCacheDir(), copy.getParentFile());
        assertArrayEquals("picture bytes".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(copy.toPath()));
        assertEquals(List.of(copy), kept);
    }

    @Test
    public void aCopyTheShareNoLongerWants_isDeleted_andCopyingStops() throws Exception {
        Uri first = source("b.jpg", "one");
        Uri second = source("c.jpg", "two");
        List<File> refused = new ArrayList<>();

        List<Uri> copies = InboundSnapshots.copyAll(app, List.of(first, second), () -> true, snapshot -> {
            refused.add(snapshot);
            return false; // the share's snapshots were already handed over for deletion
        }, new AtomicInteger());

        assertTrue(copies.isEmpty());
        assertEquals("stops after the first refusal", 1, refused.size());
        assertFalse("a refused copy holds the original, so it goes", refused.get(0).exists());
    }

    @Test
    public void aCancelledShare_copiesNothing() throws Exception {
        List<Uri> copies = InboundSnapshots.copyAll(app, List.of(source("d.jpg", "x")), () -> false,
                snapshot -> true, new AtomicInteger());

        assertTrue(copies.isEmpty());
    }
}
