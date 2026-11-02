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

    /** The fixture HEIC with the tagged clip of the JPEG fixture in an mpvd box after it. */
    private File heicMotionPhoto() throws IOException {
        File jpeg = fixture("motion_photo_tagged.jpg");
        MotionPhoto.Found inJpeg = MotionPhoto.find(jpeg);
        byte[] all = Files.readAllBytes(jpeg.toPath());
        byte[] clip = Arrays.copyOfRange(all, (int) inJpeg.start, (int) inJpeg.end);
        byte[] heic = fixture("heic_tagged.heic").exists()
                ? Files.readAllBytes(new File(temp.getRoot(), "heic_tagged.heic").toPath()) : null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(heic);
        int size = clip.length + 8;
        out.write(new byte[] {(byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size});
        out.write("mpvd".getBytes(StandardCharsets.ISO_8859_1));
        out.write(clip);
        File file = new File(temp.getRoot(), "motion.heic");
        Files.write(file.toPath(), out.toByteArray());
        return file;
    }

    @Test
    public void heic_keepsItsVideoInAnMpvdBox_throughAnExifRebuildToo() throws Exception {
        File source = heicMotionPhoto();
        MotionPhoto.Found found = MotionPhoto.find(source);
        assertNotNull(found);
        assertEquals(source.length(), found.end);

        MotionPhoto.Clip clip = cleanClip(source, found);
        File out = new File(temp.getRoot(), "clean.heic");
        HeifContainerMetadata.sanitize(source, out, clip);

        byte[] clean = Files.readAllBytes(out.toPath());
        assertFalse(contains(clean, "SecretTitle"));
        assertFalse(contains(clean, "SecretMaker"));
        assertTrue(contains(clean, "GCamera:MotionPhoto=\"1\""));
        assertTrue(contains(clean, "Item:Mime=\"image/heic\" Item:Padding=\"8\""));
        assertTrue(contains(clean, "application/rdf+xml"));
        assertTrue("the clean video ends the file", Arrays.equals(Files.readAllBytes(clip.video.toPath()),
                Arrays.copyOfRange(clean, clean.length - (int) clip.video.length(), clean.length)));
        MotionPhoto.Found again = MotionPhoto.find(out);
        assertNotNull(again);
        assertEquals(out.length(), again.end);
        assertTrue(HeifContainerMetadata.isHeif(out));

        // Putting kept EXIF in rebuilds the file again; the video and its directory stay.
        byte[] tiff = {'I', 'I', 42, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        HeifContainerMetadata.addExif(out, tiff);
        byte[] withExif = Files.readAllBytes(out.toPath());
        assertTrue(contains(withExif, "GCamera:MotionPhoto=\"1\""));
        assertTrue(Arrays.equals(Files.readAllBytes(clip.video.toPath()),
                Arrays.copyOfRange(withExif, withExif.length - (int) clip.video.length(), withExif.length)));

        // A plain clean of the source still drops it.
        File still = new File(temp.getRoot(), "still.heic");
        HeifContainerMetadata.sanitize(source, still);
        assertNull(MotionPhoto.find(still));
        assertFalse(contains(Files.readAllBytes(still.toPath()), "mpvd"));
    }

    @Test
    public void heic_findsItsVideoInAnMpvdBoxWithA64BitSize() throws Exception {
        File jpeg = fixture("motion_photo_tagged.jpg");
        MotionPhoto.Found inJpeg = MotionPhoto.find(jpeg);
        byte[] all = Files.readAllBytes(jpeg.toPath());
        byte[] clip = Arrays.copyOfRange(all, (int) inJpeg.start, (int) inJpeg.end);
        byte[] heic = Files.readAllBytes(fixture("heic_tagged.heic").toPath());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(heic);
        out.write(new byte[] {0, 0, 0, 1});
        out.write("mpvd".getBytes(StandardCharsets.ISO_8859_1));
        out.write(java.nio.ByteBuffer.allocate(8).putLong(clip.length + 16L).array());
        out.write(clip);
        File file = new File(temp.getRoot(), "large.heic");
        Files.write(file.toPath(), out.toByteArray());

        MotionPhoto.Found found = MotionPhoto.find(file);

        assertNotNull(found);
        assertEquals(file.length(), found.end);
        assertEquals(heic.length + 16, found.start);
    }

    @Test
    public void heic_findsASamsungVideoAppendedAfterItsBoxes() throws Exception {
        File jpeg = fixture("motion_photo_tagged.jpg");
        MotionPhoto.Found inJpeg = MotionPhoto.find(jpeg);
        byte[] all = Files.readAllBytes(jpeg.toPath());
        byte[] clip = Arrays.copyOfRange(all, (int) inJpeg.start, (int) inJpeg.end);
        byte[] heic = Files.readAllBytes(fixture("heic_tagged.heic").toPath());
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(heic);
        out.write("MotionPhoto_Data".getBytes(StandardCharsets.ISO_8859_1));
        out.write(clip);
        // Samsung's trailer index, which ends with "SEFT".
        out.write(new byte[] {0, 0, 0, 0, 'S', 'E', 'F', 'H', 0, 0, 0, 0});
        out.write("SEFT".getBytes(StandardCharsets.ISO_8859_1));
        File file = new File(temp.getRoot(), "samsung.heic");
        Files.write(file.toPath(), out.toByteArray());

        MotionPhoto.Found found = MotionPhoto.find(file);

        assertNotNull(found);
        assertEquals(heic.length + "MotionPhoto_Data".length(), found.start);
    }
}
