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
}
