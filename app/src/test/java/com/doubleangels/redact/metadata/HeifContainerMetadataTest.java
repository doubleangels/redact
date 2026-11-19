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
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 31)
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

    /** A cleaned copy of the fixture with an EXIF item holding {@code tiff}. */
    private File heicWithExif(byte[] tiff) throws Exception {
        File heic = new File(temp.getRoot(), "exif_" + System.nanoTime() + ".heic");
        HeifContainerMetadata.sanitize(fixture(), heic);
        HeifContainerMetadata.addExif(heic, tiff);
        return heic;
    }

    private static ImageContainerMetadata.Finding hiddenExif(File heic) {
        for (ImageContainerMetadata.Finding f : ImageContainerMetadata.scan(heic).findings) {
            if (f.key.equals("HEIF_EXIF_HIDDEN")) {
                return f;
            }
        }
        return null;
    }

    /** ExifInterface never reads Windows' author tag, so the scan reads the EXIF item itself. */
    @Test
    public void anExifItemWithAnXpAuthor_isFound_andTheFileIsNotClean() throws Exception {
        File heic = heicWithExif(ExifFixtures.tiffWith(ExifBlock.TAG_XP_AUTHOR, ExifFixtures.xpText("Jane Doe")));

        ImageContainerMetadata.Finding hidden = hiddenExif(heic);

        org.junit.Assert.assertNotNull(hidden);
        assertEquals("Jane Doe", hidden.text);
        assertFalse(onlyPictures(heic));
    }

    @Test
    public void anExifItemWithBytesNoTagPointsTo_isFound() throws Exception {
        byte[] named = tiff("KeptMake");
        byte[] withHidden = new byte[named.length + 120];
        System.arraycopy(named, 0, withHidden, 0, named.length);
        byte[] secret = "a name and address nobody points to".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(secret, 0, withHidden, named.length, secret.length);

        File heic = heicWithExif(withHidden);
        org.junit.Assert.assertNotNull(hiddenExif(heic));
        assertFalse(onlyPictures(heic));
    }

    @Test
    public void anExifItemOfNamedTagsOnly_isClean() throws Exception {
        File heic = heicWithExif(tiff("KeptMake"));
        assertEquals(null, hiddenExif(heic));
        assertTrue(onlyPictures(heic));
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

    /** The HEIC clean read SEI sizes like the video scan did, so a wrapped size looped forever. */
    @Test(timeout = 20_000)
    public void filterHevcNalUnits_dropsAnSeiWhoseSizeWouldWrapNegative() throws Exception {
        byte[] sei = VideoStreamMetadataTest.overflowingSei(new byte[0], 137); // a kept type
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        nal(stream, 39, sei);
        nal(stream, 1, new byte[] {9, 9, 9, 9}); // slice

        byte[] filtered = HeifContainerMetadata.filterHevcNalUnits(stream.toByteArray(), 4);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        nal(expected, 1, new byte[] {9, 9, 9, 9});
        assertArrayEquals(expected.toByteArray(), filtered);
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

    private File resource(String name) throws IOException {
        File file = temp.newFile(name);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("image/" + name)) {
            Files.write(file.toPath(), in.readAllBytes());
        }
        return file;
    }

    /** Top-level box types in order. */
    private static java.util.List<String> boxes(byte[] d) {
        java.util.List<String> types = new java.util.ArrayList<>();
        int p = 0;
        while (p + 8 <= d.length) {
            long size = ((d[p] & 0xFFL) << 24) | ((d[p + 1] & 0xFF) << 16) | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            types.add(new String(d, p + 4, 4, StandardCharsets.ISO_8859_1));
            if (size < 8) {
                break;
            }
            p += (int) size;
        }
        return types;
    }

    @Test
    public void animatedAvif_keepsItsFramesAndStillThroughAClean() throws Exception {
        File source = resource("animated.avif");
        File clean = new File(temp.getRoot(), "clean.avif");
        HeifContainerMetadata.sanitize(source, clean);

        byte[] out = Files.readAllBytes(clean.toPath());
        assertEquals(java.util.Arrays.asList("ftyp", "meta", "moov", "mdat"), boxes(out));
        assertTrue("still a sequence", contains(out, "avis"));
        assertTrue(HeifContainerMetadata.isHeif(clean));

        // The rebuilt tracks point at the right samples: a second clean reads them and gives the
        // same file back.
        File again = new File(temp.getRoot(), "again.avif");
        HeifContainerMetadata.sanitize(clean, again);
        assertArrayEquals(out, Files.readAllBytes(again.toPath()));

        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(clean);
        assertTrue(report.recognized);
        assertTrue(keys(report.findings).toString(), report.isClean());
        assertTrue(keys(report.kept).contains("IMAGE_SEQUENCE"));
    }

    @Test
    public void animatedAvif_withATrackThatCannotBeFollowed_keepsOnlyTheStill() throws Exception {
        byte[] source = Files.readAllBytes(resource("animated.avif").toPath());
        // A fragmented movie (mvex) is not followed: the frames go and ftyp stops promising them.
        int moov = new String(source, StandardCharsets.ISO_8859_1).indexOf("moov") - 4;
        int mvhd = new String(source, StandardCharsets.ISO_8859_1).indexOf("mvhd", moov);
        source[mvhd] = 'm';
        source[mvhd + 1] = 'v';
        source[mvhd + 2] = 'e';
        source[mvhd + 3] = 'x';
        File broken = temp.newFile("broken.avif");
        Files.write(broken.toPath(), source);
        File clean = new File(temp.getRoot(), "still.avif");
        HeifContainerMetadata.sanitize(broken, clean);

        byte[] out = Files.readAllBytes(clean.toPath());
        assertEquals(java.util.Arrays.asList("ftyp", "meta", "mdat"), boxes(out));
        assertFalse(contains(out, "avis"));
        assertTrue(contains(out, "avif"));
    }

    @Test(timeout = 5000)
    public void animatedAvif_withAChunkTableLongerThanItsBox_keepsOnlyTheStill_quickly() throws Exception {
        byte[] source = Files.readAllBytes(resource("animated.avif").toPath());
        String text = new String(source, StandardCharsets.ISO_8859_1);
        int table = text.indexOf("stco");
        if (table < 0) {
            table = text.indexOf("co64");
        }
        assertTrue("the fixture has a chunk table", table > 0);
        // The entry count, after the type and version/flags: 2^31 - 1 entries in a few bytes.
        java.nio.ByteBuffer.wrap(source, table + 8, 4).putInt(0x7FFFFFFF);
        File broken = temp.newFile("huge-table.avif");
        Files.write(broken.toPath(), source);
        File clean = new File(temp.getRoot(), "huge-table-clean.avif");
        HeifContainerMetadata.sanitize(broken, clean);

        byte[] out = Files.readAllBytes(clean.toPath());
        assertEquals(java.util.Arrays.asList("ftyp", "meta", "mdat"), boxes(out));
    }

    @Test
    public void unreferencedBytes_countsWhatNoExtentCovers() {
        java.util.List<long[]> mdats = java.util.List.of(new long[] {100, 200});
        assertEquals(0, HeifContainerMetadata.unreferencedBytes(mdats,
                java.util.List.of(new long[] {100, 60}, new long[] {160, 40})));
        assertEquals(40, HeifContainerMetadata.unreferencedBytes(mdats,
                java.util.List.of(new long[] {100, 30}, new long[] {150, 30})));
        assertEquals(100, HeifContainerMetadata.unreferencedBytes(mdats, java.util.List.of()));
        assertEquals("overlaps and extents elsewhere count once", 10, HeifContainerMetadata.unreferencedBytes(mdats,
                java.util.List.of(new long[] {100, 50}, new long[] {120, 70}, new long[] {500, 10})));
    }

    @Test
    public void scan_reportsBytesInMdatThatNoItemRefersTo() throws Exception {
        byte[] source = Files.readAllBytes(heifWithThumbnail("hvc1", -1).toPath());
        // Grow mdat by a hidden tail that no item points at.
        byte[] hidden = ascii("SecretHiddenInMdat");
        int mdat = new String(source, StandardCharsets.ISO_8859_1).lastIndexOf("mdat") - 4;
        java.nio.ByteBuffer.wrap(source, mdat, 4).putInt((int) (source.length - mdat + hidden.length));
        File padded = temp.newFile("padded.heic");
        Files.write(padded.toPath(), cat(source, hidden));

        ImageContainerMetadata.Report r = ImageContainerMetadata.scan(padded);
        assertTrue(keys(r.findings).toString(), keys(r.findings).contains("HEIF_UNREFERENCED_DATA"));
    }

    // ---- a HEIF built item by item, with every kind of extra a camera or app can add ----------

    private static byte[] cat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p);
        }
        return out.toByteArray();
    }

    private static byte[] u8(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) {
            b[i] = (byte) v[i];
        }
        return b;
    }

    private static byte[] u16(int v) {
        return u8(v >> 8, v);
    }

    private static byte[] u32(long v) {
        return u8((int) (v >> 24), (int) (v >> 16), (int) (v >> 8), (int) v);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] box(String type, byte[]... body) throws IOException {
        byte[] b = cat(body);
        return cat(u32(8 + b.length), ascii(type), b);
    }

    private static byte[] fullBox(String type, int version, int flags, byte[]... body) throws IOException {
        return box(type, cat(u8(version, flags >> 16, flags >> 8, flags)), cat(body));
    }

    private static byte[] infe(int id, String type, String name, String contentType) throws IOException {
        byte[] body = cat(u16(id), u16(0), ascii(type), ascii(name), u8(0));
        if (contentType != null) {
            body = cat(body, ascii(contentType), u8(0));
        }
        return fullBox("infe", 2, 0, body);
    }

    /** An HEVC picture: an SEI with user data, then a slice. Length-prefixed NAL units. */
    private static byte[] hevcPicture() throws IOException {
        byte[] uuid = new byte[16];
        byte[] text = ascii("SecretSEI");
        byte[] sei = cat(u8(39 << 1, 1), u8(5, 16 + text.length), uuid, text, u8(0x80));
        byte[] slice = u8(1 << 1, 1, 0xAF, 0x12, 0x34);
        return cat(u32(sei.length), sei, u32(slice.length), slice);
    }

    /** A HEIF with item names, a depth map, XMP in idat and in an xml box, unknown items, properties and boxes. */
    private File syntheticHeif() throws IOException {
        byte[] picture = hevcPicture();
        byte[] depth = hevcPicture();
        byte[] exif = cat(u32(0), ascii("MM"), u8(0, 42), u32(8), u16(0), u32(0));
        byte[] note = ascii("SecretNote in a text item");
        byte[] unknown = ascii("SecretUnknownItem");
        byte[] xmp = ascii("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><dc:creator>SecretCreator</dc:creator></x:xmpmeta>");

        byte[] hvcC = box("hvcC", u8(1), new byte[20], u8(0xFF & (0xFC | 3)), u8(0));
        byte[] ispe = fullBox("ispe", 0, 0, u32(64), u32(48));
        byte[] auxC = fullBox("auxC", 0, 0, ascii("urn:mpeg:hevc:2015:auxid:2"), u8(0));
        byte[] udes = box("udes", ascii("SecretDescription"));
        byte[] ipco = box("ipco", hvcC, ispe, auxC, udes);
        byte[] ipma = fullBox("ipma", 0, 0, u32(2),
                u16(1), u8(3, 0x80 | 1, 2, 4),
                u16(4), u8(3, 0x80 | 1, 2, 3));

        byte[] iinf = fullBox("iinf", 0, 0, u16(6),
                infe(1, "hvc1", "SecretName", null),
                infe(2, "Exif", "", null),
                infe(3, "mime", "", "application/rdf+xml"),
                infe(4, "hvc1", "", null),
                infe(5, "mime", "", "text/plain"),
                infe(6, "abcd", "", null));
        byte[] iref = fullBox("iref", 0, 0,
                box("auxl", u16(4), u16(1), u16(1)),
                box("cdsc", u16(2), u16(1), u16(1)),
                box("cdsc", u16(3), u16(1), u16(1)));
        byte[] grpl = box("grpl", fullBox("altr", 0, 0, u32(100), u32(2), u32(1), u32(4)));
        byte[] dinf = box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1)));
        byte[] hdlr = fullBox("hdlr", 0, 0, u32(0), ascii("pict"), new byte[12], ascii("SecretHandler"), u8(0));
        byte[] xmlBox = fullBox("xml ", 0, 0, ascii("<x:xmpmeta><dc:title>SecretXmlBox</dc:title></x:xmpmeta>"));
        byte[] idat = box("idat", xmp);

        byte[][] mdatItems = {picture, exif, depth, note, unknown};
        int[] mdatIds = {1, 2, 4, 5, 6};
        byte[] ftyp = box("ftyp", ascii("heic"), u32(0), ascii("mif1"), ascii("heic"));
        byte[] meta = null;
        long mdatData = 0;
        for (int pass = 0; pass < 2; pass++) {
            ByteArrayOutputStream iloc = new ByteArrayOutputStream();
            iloc.write(u8(0x44, 0x00));
            iloc.write(u16(6));
            long at = mdatData;
            for (int i = 0; i < mdatItems.length; i++) {
                iloc.write(cat(u16(mdatIds[i]), u16(0), u16(0), u16(1), u32(at), u32(mdatItems[i].length)));
                at += mdatItems[i].length;
            }
            iloc.write(cat(u16(3), u16(1), u16(0), u16(1), u32(0), u32(xmp.length)));
            meta = fullBox("meta", 0, 0, hdlr, dinf, fullBox("pitm", 0, 0, u16(1)),
                    fullBox("iloc", 1, 0, iloc.toByteArray()), iinf, iref,
                    box("iprp", ipco, ipma), idat, grpl, xmlBox);
            mdatData = ftyp.length + meta.length + 8;
        }
        byte[] mdat = box("mdat", cat(mdatItems));
        byte[] uuid = box("uuid", new byte[16], ascii("SecretUuid"));
        File f = temp.newFile("synthetic.heic");
        Files.write(f.toPath(), cat(ftyp, meta, mdat, uuid, ascii("SecretTail")));
        return f;
    }

    @Test
    public void scan_findsEveryExtraOfASyntheticHeif() throws Exception {
        ImageContainerMetadata.Report r = ImageContainerMetadata.scan(syntheticHeif());

        assertTrue(r.recognized);
        java.util.List<String> keys = keys(r.findings);
        for (String key : new String[] {"HEIF_HANDLER_NAME", "HEIF_ITEM_NAME", "HEIF_DEPTH_OR_MATTE", "XMP",
                "HEIF_ITEM_TEXT_PLAIN", "HEIF_ITEM_ABCD", "HEIF_PROPERTY_UDES", "HEIF_PICTURE_USER_DATA",
                "HEIF_BOX_UUID", "DATA_AFTER_IMAGE"}) {
            assertTrue(key + " in " + keys, keys.contains(key));
        }
    }

    @Test
    public void sanitize_ofASyntheticHeif_keepsOnlyThePicture() throws Exception {
        File clean = new File(temp.getRoot(), "synthetic-clean.heic");
        HeifContainerMetadata.sanitize(syntheticHeif(), clean);

        byte[] out = Files.readAllBytes(clean.toPath());
        assertFalse(contains(out, "Secret"));
        assertTrue(contains(out, "hvcC"));
        ImageContainerMetadata.Report r = ImageContainerMetadata.scan(clean);
        assertTrue(keys(r.findings).toString(), r.isClean());
    }

    /**
     * A HEIF whose primary picture is HEVC and whose thumbnail is a {@code jpeg} item of type
     * {@code thumbType}, its bytes carrying an APP1 EXIF segment with a marker. When
     * {@code declaredThumbLength} is not negative, the thumbnail's iloc length says that instead.
     */
    private File heifWithThumbnail(String thumbType, long declaredThumbLength) throws IOException {
        byte[] picture = u8(0, 0, 0, 5, 1 << 1, 1, 0xAF, 0x12, 0x34);
        byte[] app1 = cat(ascii("Exif"), u8(0, 0), ascii("SecretGpsInThumbnail"));
        byte[] thumb = "jpeg".equals(thumbType) || "avc1".equals(thumbType)
                ? cat(u8(0xFF, 0xD8, 0xFF, 0xE1), u16(app1.length + 2), app1,
                        u8(0xFF, 0xDA, 0, 2, 0x11, 0x22, 0xFF, 0xD9))
                : picture; // an HEVC thumbnail is coded like the picture
        byte[] hvcC = box("hvcC", u8(1), new byte[20], u8(0xFF & (0xFC | 3)), u8(0));
        byte[] ispe = fullBox("ispe", 0, 0, u32(64), u32(48));
        byte[] ipco = box("ipco", hvcC, ispe);
        byte[] ipma = fullBox("ipma", 0, 0, u32(2), u16(1), u8(2, 0x80 | 1, 2), u16(2), u8(2, 0x80 | 1, 2));
        byte[] iinf = fullBox("iinf", 0, 0, u16(2), infe(1, "hvc1", "", null), infe(2, thumbType, "", null));
        byte[] iref = fullBox("iref", 0, 0, box("thmb", u16(2), u16(1), u16(1)));
        byte[] hdlr = fullBox("hdlr", 0, 0, u32(0), ascii("pict"), new byte[12], u8(0));
        byte[] ftyp = box("ftyp", ascii("heic"), u32(0), ascii("mif1"), ascii("heic"));
        byte[] meta = null;
        long mdatData = 0;
        for (int pass = 0; pass < 2; pass++) {
            byte[] iloc = cat(u8(0x44, 0x00), u16(2),
                    u16(1), u16(0), u16(0), u16(1), u32(mdatData), u32(picture.length),
                    u16(2), u16(0), u16(0), u16(1), u32(mdatData + picture.length),
                    u32(declaredThumbLength >= 0 ? declaredThumbLength : thumb.length));
            meta = fullBox("meta", 0, 0, hdlr, fullBox("pitm", 0, 0, u16(1)),
                    fullBox("iloc", 1, 0, iloc), iinf, iref, box("iprp", ipco, ipma));
            mdatData = ftyp.length + meta.length + 8;
        }
        File f = temp.newFile("thumb-" + thumbType + declaredThumbLength + ".heic");
        Files.write(f.toPath(), cat(ftyp, meta, box("mdat", picture, thumb)));
        return f;
    }

    @Test
    public void sanitize_refusesAJpegItemItCannotFilter_soTheCleanEncodesAgain() throws Exception {
        File source = heifWithThumbnail("jpeg", -1);
        File clean = new File(temp.getRoot(), "thumb-clean.heic");
        try {
            HeifContainerMetadata.sanitize(source, clean);
            fail("A jpeg item with EXIF inside must not be copied as it is");
        } catch (IOException expected) {
            // The caller encodes the picture again.
        }
        assertTrue(!clean.exists() || !contains(Files.readAllBytes(clean.toPath()), "SecretGps"));
        ImageContainerMetadata.Report r = ImageContainerMetadata.scan(source);
        assertFalse(r.isClean());
        assertTrue(keys(r.findings).toString(), keys(r.findings).contains("HEIF_UNFILTERED_PICTURE"));
    }

    @Test
    public void sanitize_refusesAvcItemsToo() throws Exception {
        try {
            HeifContainerMetadata.sanitize(heifWithThumbnail("avc1", -1), new File(temp.getRoot(), "avc-clean.heic"));
            fail("An avc1 item is not filtered, so it must not be copied");
        } catch (IOException expected) {
            // The caller encodes the picture again.
        }
    }

    @Test(timeout = 5000)
    public void anItemLongerThanTheFile_failsQuicklyWithoutAllocatingIt() throws Exception {
        File source = heifWithThumbnail("hvc1", 0x1FFFFFFFL);
        try {
            HeifContainerMetadata.sanitize(source, new File(temp.getRoot(), "huge-clean.heic"));
            fail("An item that runs past the end of the file must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("outside the file"));
        }
        assertFalse(ImageContainerMetadata.scan(source).recognized);
    }

    /**
     * An auxiliary track is kept only when its auxi box says it is transparency. This one (built
     * from animated.avif: an auxv track with a tref to track 1, co64 offsets and stz2 sizes) has
     * no auxi at all, so it is an unknown kind and goes, like a depth map; the picture track is
     * rebuilt on its own and still points at the right samples.
     */
    @Test
    public void animatedAvif_withAnAuxiliaryTrackOfNoKnownKind_keepsOnlyThePictureTrack() throws Exception {
        File clean = new File(temp.getRoot(), "two-clean.avif");
        HeifContainerMetadata.sanitize(resource("animated_two_tracks.avif"), clean);

        byte[] out = Files.readAllBytes(clean.toPath());
        String text = new String(out, StandardCharsets.ISO_8859_1);
        assertEquals("only the picture track", 1, text.split("trak", -1).length - 1);
        assertFalse(contains(out, "auxv"));
        assertFalse(contains(out, "auxl"));

        File again = new File(temp.getRoot(), "two-again.avif");
        HeifContainerMetadata.sanitize(clean, again);
        assertArrayEquals("the rebuilt tracks point at the right samples", out, Files.readAllBytes(again.toPath()));
        assertTrue(ImageContainerMetadata.scan(clean).isClean());
    }

    /** Only the NAL types a picture needs are kept; filler, reserved and unspecified ones go. */
    @Test
    public void filterHevcNalUnits_keepsOnlyTheTypesAPictureNeeds() throws Exception {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        nal(stream, 32, new byte[] {1, 2, 3}); // VPS
        nal(stream, 19, new byte[] {7, 7}); // IDR slice
        nal(stream, 38, new byte[] {(byte) 0xFF, (byte) 0xFF}); // filler
        nal(stream, 48, "SecretPrivate".getBytes(StandardCharsets.US_ASCII)); // unspecified
        nal(stream, 45, new byte[] {1}); // reserved

        byte[] filtered = HeifContainerMetadata.filterHevcNalUnits(stream.toByteArray(), 4);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        nal(expected, 32, new byte[] {1, 2, 3});
        nal(expected, 19, new byte[] {7, 7});
        assertArrayEquals(expected.toByteArray(), filtered);
    }

    /** A metadata type is compared in full: 2^32 + 1 is not type 1. Padding and reserved OBUs go. */
    @Test
    public void filterAv1Obus_dropsPaddingReservedAndOverlongMetadataTypes() throws Exception {
        byte[] sequenceHeader = {(byte) (1 << 3 | 0x02), 2, 9, 9};
        byte[] padding = {(byte) (15 << 3 | 0x02), 2, 'h', 'i'};
        byte[] reserved = {(byte) (9 << 3 | 0x02), 1, 'x'};
        // metadata OBU whose type is the leb128 of 2^32 + 1
        byte[] overlong = {(byte) (5 << 3 | 0x02), 7, (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10, 'z', 'z'};
        overlong[1] = 7;
        byte[] data = cat(sequenceHeader, padding, reserved, overlong);

        byte[] filtered = HeifContainerMetadata.filterAv1Obus(data);

        assertArrayEquals(sequenceHeader, filtered);
    }

    /** A v2 colour profile whose description is a person's own display name, and a white point. */
    private static byte[] calibratedIccProfile() throws IOException {
        byte[] name = ascii("Jordan's monitor\0");
        byte[] desc = cat(ascii("desc"), u32(0), u32(name.length), name, u32(0), u32(0), u8(0, 0, 0), new byte[67]);
        byte[] wtpt = cat(ascii("XYZ "), u32(0), u32(0xF6D6), u32(0x10000), u32(0xD32D));
        int table = 128 + 4 + 2 * 12;
        int descAt = table;
        int wtptAt = descAt + ((desc.length + 3) & ~3);
        byte[] descPadded = java.util.Arrays.copyOf(desc, (desc.length + 3) & ~3);
        int size = wtptAt + wtpt.length;
        byte[] header = new byte[128];
        java.nio.ByteBuffer.wrap(header).putInt(0, size);
        header[8] = 2;
        System.arraycopy(ascii("mntr"), 0, header, 12, 4);
        System.arraycopy(ascii("RGB "), 0, header, 16, 4);
        System.arraycopy(ascii("XYZ "), 0, header, 20, 4);
        System.arraycopy(ascii("acsp"), 0, header, 36, 4);
        return cat(header, u32(2), ascii("desc"), u32(descAt), u32(desc.length),
                ascii("wtpt"), u32(wtptAt), u32(wtpt.length), descPadded, wtpt);
    }

    /** A one-picture HEIC whose colour property holds {@code profile}. */
    private File heifWithProfile(byte[] profile) throws IOException {
        byte[] picture = u8(0, 0, 0, 5, 1 << 1, 1, 0xAF, 0x12, 0x34);
        byte[] hvcC = box("hvcC", u8(1), new byte[20], u8(0xFF & (0xFC | 3)), u8(0));
        byte[] ispe = fullBox("ispe", 0, 0, u32(64), u32(48));
        byte[] colr = box("colr", ascii("prof"), profile);
        byte[] ipco = box("ipco", hvcC, ispe, colr);
        byte[] ipma = fullBox("ipma", 0, 0, u32(1), u16(1), u8(3, 0x80 | 1, 2, 3));
        byte[] iinf = fullBox("iinf", 0, 0, u16(1), infe(1, "hvc1", "", null));
        byte[] hdlr = fullBox("hdlr", 0, 0, u32(0), ascii("pict"), new byte[12], u8(0));
        byte[] ftyp = box("ftyp", ascii("heic"), u32(0), ascii("mif1"), ascii("heic"));
        byte[] meta = null;
        long mdatData = 0;
        for (int pass = 0; pass < 2; pass++) {
            byte[] iloc = cat(u8(0x44, 0x00), u16(1), u16(1), u16(0), u16(0), u16(1), u32(mdatData), u32(picture.length));
            meta = fullBox("meta", 0, 0, hdlr, fullBox("pitm", 0, 0, u16(1)),
                    fullBox("iloc", 0, 0, iloc), iinf, box("iprp", ipco, ipma));
            mdatData = ftyp.length + meta.length + 8;
        }
        File f = new File(temp.getRoot(), "profile-" + System.nanoTime() + ".heic");
        Files.write(f.toPath(), cat(ftyp, meta, box("mdat", picture)));
        return f;
    }

    /** Strict Clean and shares give a HEIC's colour profile a neutral name, as for a JPEG. */
    @Test
    public void strictClean_neutralizesAHeicColourProfile_andANormalCleanKeepsItsColours() throws Exception {
        File source = heifWithProfile(calibratedIccProfile());
        File strict = new File(temp.getRoot(), "strict.heic");
        File normal = new File(temp.getRoot(), "normal.heic");

        HeifContainerMetadata.sanitize(source, strict, null, temp.getRoot(), true);
        HeifContainerMetadata.sanitize(source, normal, null, temp.getRoot(), false);

        byte[] strictOut = Files.readAllBytes(strict.toPath());
        assertFalse(contains(strictOut, "Jordan"));
        assertTrue(contains(strictOut, IccProfiles.NEUTRAL_NAME));
        assertTrue("the colours stay", contains(strictOut, "wtpt"));
        assertTrue("a normal clean keeps the profile as it was, header cleared",
                contains(Files.readAllBytes(normal.toPath()), "Jordan"));
    }

    /** A profile Strict Clean can't follow is left out; the property's place stays. */
    @Test
    public void strictClean_leavesOutAHeicProfileItCantFollow() throws Exception {
        byte[] broken = calibratedIccProfile();
        java.nio.ByteBuffer.wrap(broken).putInt(128, 5000);
        File strict = new File(temp.getRoot(), "strict-broken.heic");

        HeifContainerMetadata.sanitize(heifWithProfile(broken), strict, null, temp.getRoot(), true);

        byte[] out = Files.readAllBytes(strict.toPath());
        assertFalse(contains(out, "Jordan"));
        assertFalse(contains(out, "colr"));
        assertTrue(ImageContainerMetadata.scan(strict).recognized);
    }
}
