package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Lossless HEIC cleaning: the pictures are copied as they are, everything else is left out. */
public class HeifContainerMetadataTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File fixture() throws IOException {
        File file = temp.newFile("tagged.heic");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("image/heic_tagged.heic")) {
            if (in == null) {
                throw new IOException("Missing test resource");
            }
            Files.write(file.toPath(), in.readAllBytes());
        }
        return file;
    }

    private static boolean contains(byte[] hay, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + n.length <= hay.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (hay[i + j] != n[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    public void isHeif_recognisesHeicButNotOtherImages() throws Exception {
        assertTrue(HeifContainerMetadata.isHeif(fixture()));
        File jpeg = temp.newFile("a.jpg");
        Files.write(jpeg.toPath(), new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16,
                'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0});
        assertFalse(HeifContainerMetadata.isHeif(jpeg));
    }

    @Test
    public void sanitize_removesExifAndXmp_andKeepsThePictureAndColourProfile() throws Exception {
        File source = fixture();
        byte[] before = Files.readAllBytes(source.toPath());
        assertTrue(contains(before, "SecretMaker"));
        assertTrue(contains(before, "SecretPerson"));

        File target = new File(temp.getRoot(), "clean.heic");
        HeifContainerMetadata.sanitize(source, target);
        byte[] after = Files.readAllBytes(target.toPath());

        assertFalse(contains(after, "SecretMaker"));
        assertFalse(contains(after, "SecretPerson"));
        assertFalse(contains(after, "Exif"));
        assertTrue(HeifContainerMetadata.isHeif(target));
        assertTrue("the coded picture is kept", contains(after, "hvcC"));
        assertTrue("the colour profile is kept", contains(after, "colr"));
        assertTrue(after.length < before.length);
    }

    /** A little-endian TIFF block with one ASCII tag (Make), standing in for kept EXIF. */
    private static byte[] tiff(String make) {
        byte[] text = (make + "\0").getBytes(StandardCharsets.US_ASCII);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 + 2 + 12 + 4 + text.length)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        b.putShort((short) 1);
        b.putShort((short) 0x010F).putShort((short) 2).putInt(text.length).putInt(26);
        b.putInt(0);
        b.put(text);
        return b.array();
    }

    @Test
    public void addExif_describesThePrimaryPicture_andACleanRemovesItAgain() throws Exception {
        File clean = new File(temp.getRoot(), "clean.heic");
        HeifContainerMetadata.sanitize(fixture(), clean);
        byte[] before = Files.readAllBytes(clean.toPath());

        HeifContainerMetadata.addExif(clean, tiff("KeptMake"));

        byte[] withExif = Files.readAllBytes(clean.toPath());
        assertTrue(contains(withExif, "KeptMake"));
        assertTrue("an Exif item", contains(withExif, "Exif"));
        assertTrue("that describes the primary picture", contains(withExif, "cdsc"));
        assertTrue("the picture is still there", contains(withExif, "hvcC"));
        assertFalse("nothing from the source comes back", contains(withExif, "SecretMaker"));

        File again = new File(temp.getRoot(), "again.heic");
        HeifContainerMetadata.sanitize(clean, again);
        assertArrayEquals(before, Files.readAllBytes(again.toPath()));
    }

    @Test
    public void sanitize_isStableOnItsOwnOutput() throws Exception {
        File once = new File(temp.getRoot(), "once.heic");
        File twice = new File(temp.getRoot(), "twice.heic");
        HeifContainerMetadata.sanitize(fixture(), once);
        HeifContainerMetadata.sanitize(once, twice);
        assertArrayEquals(Files.readAllBytes(once.toPath()), Files.readAllBytes(twice.toPath()));
    }

    @Test
    public void sanitize_refusesWhatIsNotHeif() throws Exception {
        File text = temp.newFile("not.heic");
        Files.write(text.toPath(), "not an image at all".getBytes(StandardCharsets.US_ASCII));
        try {
            HeifContainerMetadata.sanitize(text, new File(temp.getRoot(), "out.heic"));
            fail("expected an IOException so the caller re-encodes");
        } catch (IOException expected) {
            // the caller falls back to re-encoding
        }
    }

    /** One length-prefixed HEVC NAL unit. */
    private static void nal(ByteArrayOutputStream out, int type, byte[] payload) {
        int length = payload.length + 2;
        out.write(length >>> 24);
        out.write(length >>> 16);
        out.write(length >>> 8);
        out.write(length);
        out.write(type << 1);
        out.write(1);
        out.write(payload, 0, payload.length);
    }

    @Test
    public void filterHevcNalUnits_dropsUserDataSei_andKeepsPicturesAndHdrSei() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        nal(stream, 32, new byte[] {1, 2, 3}); // VPS
        byte[] userData = new byte[20];
        userData[0] = 5; // user_data_unregistered
        userData[1] = 17;
        System.arraycopy("SecretEncoder".getBytes(StandardCharsets.US_ASCII), 0, userData, 2, 13);
        userData[19] = (byte) 0x80;
        nal(stream, 39, userData);
        byte[] light = {(byte) 144, 4, 0, 100, 0, 50, (byte) 0x80}; // content light level
        nal(stream, 39, light);
        nal(stream, 1, new byte[] {9, 9, 9, 9}); // slice

        byte[] filtered = HeifContainerMetadata.filterHevcNalUnits(stream.toByteArray(), 4);

        assertFalse(contains(filtered, "SecretEncoder"));
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        nal(expected, 32, new byte[] {1, 2, 3});
        nal(expected, 39, light);
        nal(expected, 1, new byte[] {9, 9, 9, 9});
        assertArrayEquals(expected.toByteArray(), filtered);
    }

    @Test
    public void filterAv1Obus_dropsT35Metadata_andKeepsHdrMetadata() throws Exception {
        byte[] sequenceHeader = {0x0A, 0x02, 0x11, 0x22};
        byte[] t35 = {0x2A, 0x05, 0x04, 'S', 'e', 'c', 'r'};
        byte[] cll = {0x2A, 0x05, 0x01, 0, 100, 0, 50};
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        in.write(sequenceHeader, 0, sequenceHeader.length);
        in.write(t35, 0, t35.length);
        in.write(cll, 0, cll.length);

        byte[] filtered = HeifContainerMetadata.filterAv1Obus(in.toByteArray());

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(sequenceHeader, 0, sequenceHeader.length);
        expected.write(cll, 0, cll.length);
        assertArrayEquals(expected.toByteArray(), filtered);
    }

    private static byte[] ftyp(String major, String compatible) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(major.getBytes(StandardCharsets.US_ASCII));
        body.write(new byte[4]);
        body.write(compatible.getBytes(StandardCharsets.US_ASCII));
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream box = new ByteArrayOutputStream();
        int size = payload.length + 8;
        box.write(new byte[] {0, 0, 0, (byte) size});
        box.write("ftyp".getBytes(StandardCharsets.US_ASCII));
        box.write(payload);
        return box.toByteArray();
    }

    @Test
    public void stillImageFtyp_dropsSequenceBrands() throws Exception {
        byte[] still = HeifContainerMetadata.stillImageFtyp(ftyp("msf1", "msf1heicisom"));

        assertEquals("mif1", new String(still, 8, 4, StandardCharsets.US_ASCII));
        assertFalse(contains(still, "msf1"));
        assertFalse(contains(still, "isom"));
        assertTrue(contains(still, "heic"));
    }

    @Test
    public void stillImageFtyp_turnsAnAnimatedAvifIntoAStillOne() throws Exception {
        byte[] still = HeifContainerMetadata.stillImageFtyp(ftyp("avis", "avismsf1miafMA1B"));

        assertEquals("avif", new String(still, 8, 4, StandardCharsets.US_ASCII));
        assertFalse(contains(still, "avis"));
        assertTrue(contains(still, "mif1"));
        assertTrue(contains(still, "miaf"));
    }

    private static boolean onlyPictures(File file) {
        return ImageContainerMetadata.scan(file).isClean();
    }

    @Test
    public void scan_isCleanOnlyForWhatACleanLeaves() throws Exception {
        File source = fixture();
        assertFalse("the source has an XMP item", onlyPictures(source));
        File clean = new File(temp.getRoot(), "clean.heic");
        HeifContainerMetadata.sanitize(source, clean);
        assertTrue(onlyPictures(clean));

        byte[] cleanBytes = Files.readAllBytes(clean.toPath());
        // A Motion Photo's video, a vendor trailer, or anything else after the picture.
        java.io.ByteArrayOutputStream withVideo = new java.io.ByteArrayOutputStream();
        withVideo.write(cleanBytes);
        withVideo.write(new byte[] {0, 0, 0, 16, 'm', 'p', 'v', 'd', 1, 2, 3, 4, 5, 6, 7, 8});
        File motion = temp.newFile("motion.heic");
        Files.write(motion.toPath(), withVideo.toByteArray());
        assertFalse(onlyPictures(motion));

        File trailer = temp.newFile("trailer.heic");
        byte[] withTrailer = java.util.Arrays.copyOf(cleanBytes, cleanBytes.length + 5);
        Files.write(trailer.toPath(), withTrailer);
        assertFalse(onlyPictures(trailer));
    }

    private static java.util.List<String> keys(java.util.List<ImageContainerMetadata.Finding> findings) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        for (ImageContainerMetadata.Finding f : findings) {
            keys.add(f.key);
        }
        return keys;
    }

    @Test
    public void scan_showsTheXmpItem_andFindsNothingInTheCleanCopy() throws Exception {
        File source = fixture();
        ImageContainerMetadata.Report tagged = ImageContainerMetadata.scan(source);
        assertTrue(tagged.recognized);
        assertEquals(ImageContainerMetadata.Format.HEIF, tagged.format);
        assertTrue(keys(tagged.findings).contains("XMP"));
        assertFalse(tagged.isClean());

        File clean = temp.newFile("clean.heic");
        HeifContainerMetadata.sanitize(source, clean);
        ImageContainerMetadata.Report after = ImageContainerMetadata.scan(clean);
        assertTrue(after.recognized);
        assertTrue(keys(after.findings).toString(), after.isClean());
    }

    @Test
    public void scan_showsBoxesAClean_dropsAndDataAfterTheLastBox() throws Exception {
        File clean = temp.newFile("clean.heic");
        HeifContainerMetadata.sanitize(fixture(), clean);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Files.readAllBytes(clean.toPath()));
        byte[] uuid = "0123456789abcdefCamera serial SN-42".getBytes(StandardCharsets.ISO_8859_1);
        int size = 8 + uuid.length;
        out.write(new byte[] {0, 0, 0, (byte) size});
        out.write("uuid".getBytes(StandardCharsets.ISO_8859_1));
        out.write(uuid);
        out.write("appended".getBytes(StandardCharsets.ISO_8859_1));
        File tampered = temp.newFile("tampered.heic");
        Files.write(tampered.toPath(), out.toByteArray());

        ImageContainerMetadata.Report r = ImageContainerMetadata.scan(tampered);

        assertTrue("the picture can still be followed", r.recognized);
        assertTrue(keys(r.findings).contains("HEIF_BOX_UUID"));
        assertTrue(keys(r.findings).contains("DATA_AFTER_IMAGE"));
        boolean serialShown = false;
        for (ImageContainerMetadata.Finding f : r.findings) {
            serialShown |= f.text != null && f.text.contains("SN-42");
        }
        assertTrue(serialShown);
    }
}
