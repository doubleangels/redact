package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** A Motion Photo keeps its video through a clean, with nothing about the recording left in it. */
public class MotionPhotoTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File fixture(String name) throws IOException {
        File file = temp.newFile(name);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("image/" + name)) {
            if (in == null) {
                throw new IOException("Missing test resource");
            }
            Files.write(file.toPath(), in.readAllBytes());
        }
        return file;
    }

    private static boolean contains(byte[] hay, String needle) {
        return new String(hay, StandardCharsets.ISO_8859_1).contains(needle);
    }

    /** What a clean does with the clip: copy it out and scrub it. */
    private MotionPhoto.Clip cleanClip(File source, MotionPhoto.Found found) throws IOException {
        byte[] all = Files.readAllBytes(source.toPath());
        File video = temp.newFile();
        Files.write(video.toPath(), Arrays.copyOfRange(all, (int) found.start, (int) found.end));
        VideoContainerMetadata.scrub(video);
        assertTrue(VideoContainerMetadata.scan(video).fields.isEmpty());
        return new MotionPhoto.Clip(video, found.presentationTimestampUs);
    }

    private static ImageContainerMetadata.Report scan(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return ImageContainerMetadata.scan(in.getChannel());
        }
    }

    private static boolean kept(ImageContainerMetadata.Report report, String key) {
        for (ImageContainerMetadata.Finding finding : report.kept) {
            if (finding.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void find_readsGooglesDirectory() throws Exception {
        File source = fixture("motion_photo_tagged.jpg");
        MotionPhoto.Found found = MotionPhoto.find(source);

        assertNotNull(found);
        assertEquals(source.length(), found.end);
        assertEquals(250_000L, found.presentationTimestampUs);
        byte[] all = Files.readAllBytes(source.toPath());
        assertEquals("ftyp", new String(all, (int) found.start + 4, 4, StandardCharsets.ISO_8859_1));
    }

    @Test
    public void find_readsSamsungsTrailer_andStopsWhereTheVideoEnds() throws Exception {
        File source = fixture("motion_photo_samsung.jpg");
        MotionPhoto.Found found = MotionPhoto.find(source);

        assertNotNull(found);
        assertTrue("the trailer after the video is not part of it", found.end < source.length());
        assertEquals(-1L, found.presentationTimestampUs);
    }

    @Test
    public void find_ignoresAPhotoThatIsNotAMotionPhoto() throws Exception {
        File still = temp.newFile("still.jpg");
        Files.write(still.toPath(), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        assertNull(MotionPhoto.find(still));
    }

    @Test
    public void sanitize_putsTheCleanVideoBack_withItsDirectory() throws Exception {
        File source = fixture("motion_photo_tagged.jpg");
        MotionPhoto.Clip clip = cleanClip(source, MotionPhoto.find(source));
        File out = new File(temp.getRoot(), "clean.jpg");

        ImageContainerMetadata.sanitize(source, out, clip);

        byte[] clean = Files.readAllBytes(out.toPath());
        assertFalse(contains(clean, "SecretPerson"));
        assertFalse(contains(clean, "SecretTitle"));
        assertFalse(contains(clean, "+48.8584"));
        assertTrue(contains(clean, "GCamera:MotionPhoto=\"1\""));
        assertTrue(contains(clean, "GCamera:MotionPhotoPresentationTimestampUs=\"250000\""));
        assertTrue(contains(clean, "Item:Length=\"" + clip.video.length() + "\""));
        assertTrue("the video ends the file", Arrays.equals(Files.readAllBytes(clip.video.toPath()),
                Arrays.copyOfRange(clean, clean.length - (int) clip.video.length(), clean.length)));

        ImageContainerMetadata.Report report = scan(out);
        assertTrue(report.findings.toString(), report.isClean());
        assertTrue(kept(report, "MOTION_PHOTO"));
        assertTrue(kept(report, "MOTION_PHOTO_VIDEO"));
        MotionPhoto.Found again = MotionPhoto.find(out);
        assertNotNull(again);
        assertEquals(out.length(), again.end);
    }

    @Test
    public void sanitize_turnsSamsungsLayoutIntoGooglesAndDropsTheTrailer() throws Exception {
        File source = fixture("motion_photo_samsung.jpg");
        MotionPhoto.Clip clip = cleanClip(source, MotionPhoto.find(source));
        File out = new File(temp.getRoot(), "clean.jpg");

        ImageContainerMetadata.sanitize(source, out, clip);

        byte[] clean = Files.readAllBytes(out.toPath());
        assertFalse(contains(clean, "SEFT"));
        assertFalse(contains(clean, "MotionPhotoPresentationTimestampUs"));
        assertTrue(scan(out).isClean());
    }

    @Test
    public void scan_stillReportsAVideoThatWasNotCleaned() throws Exception {
        File source = fixture("motion_photo_tagged.jpg");
        MotionPhoto.Found found = MotionPhoto.find(source);
        byte[] all = Files.readAllBytes(source.toPath());
        File video = temp.newFile();
        Files.write(video.toPath(), Arrays.copyOfRange(all, (int) found.start, (int) found.end));
        File out = new File(temp.getRoot(), "clean.jpg");

        // The directory is written, but the video keeps its location and title.
        ImageContainerMetadata.sanitize(source, out, new MotionPhoto.Clip(video, -1));

        ImageContainerMetadata.Report report = scan(out);
        assertFalse(report.isClean());
        assertFalse(kept(report, "MOTION_PHOTO_VIDEO"));
    }

    @Test
    public void sanitize_withoutAClip_makesAStill() throws Exception {
        File out = new File(temp.getRoot(), "clean.jpg");
        ImageContainerMetadata.sanitize(fixture("motion_photo_tagged.jpg"), out);

        byte[] clean = Files.readAllBytes(out.toPath());
        assertFalse(contains(clean, "ftyp"));
        assertFalse(contains(clean, "MotionPhoto"));
        assertNull(MotionPhoto.find(out));
    }
}
