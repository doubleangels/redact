package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A clean keeps an Ultra HDR photo's gain map, cleaned, and nothing else after the picture. */
@RunWith(RobolectricTestRunner.class)
public class UltraHdrTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String GAIN_MAP_XMP = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"XMP Core 5.5.0\">"
            + "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
            + " hdrgm:Version=\"1.0\" hdrgm:GainMapMin=\"0.000000\" hdrgm:GainMapMax=\"2.656715\""
            + " hdrgm:HDRCapacityMin=\"0.000000\" hdrgm:HDRCapacityMax=\"2.656715\"/>"
            + "</rdf:RDF></x:xmpmeta>   <?xpacket end=\"w\"?>";

    private File fixture() throws IOException {
        File file = temp.newFile("ultra_hdr.jpg");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("image/ultra_hdr_tagged.jpg")) {
            if (in == null) {
                throw new IOException("Missing test resource");
            }
            Files.write(file.toPath(), in.readAllBytes());
        }
        return file;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static boolean contains(byte[] hay, String needle) {
        return indexOf(hay, needle.getBytes(StandardCharsets.ISO_8859_1), 0) >= 0;
    }

    private static List<String> keys(List<ImageContainerMetadata.Finding> findings) {
        List<String> keys = new ArrayList<>();
        for (ImageContainerMetadata.Finding f : findings) {
            keys.add(f.key);
        }
        return keys;
    }

    @Test
    public void gainMapOnlyXmp_acceptsTheNumbersAndDirectory() {
        assertTrue(UltraHdr.isGainMapOnlyXmp(GAIN_MAP_XMP));
        assertTrue(UltraHdr.isGainMapOnlyXmp(UltraHdr.primaryXmp("1.0", 62570)));
        Map<String, List<String>> fields = UltraHdr.gainMapFields(GAIN_MAP_XMP);
        assertTrue(UltraHdr.isGainMapOnlyXmp(UltraHdr.gainMapXmp(fields)));
    }

    @Test
    public void gainMapOnlyXmp_refusesAnythingElse() {
        // Another namespace, even next to the gain map's.
        assertFalse(UltraHdr.isGainMapOnlyXmp(GAIN_MAP_XMP.replace("hdrgm:Version=\"1.0\"",
                "hdrgm:Version=\"1.0\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" dc:creator=\"Someone\"")));
        // Text where a number belongs.
        assertFalse(UltraHdr.isGainMapOnlyXmp(GAIN_MAP_XMP.replace("2.656715\" hdrgm:HDRCapacityMin",
                "Someone\" hdrgm:HDRCapacityMin")));
        // A field the format does not have.
        assertFalse(UltraHdr.isGainMapOnlyXmp(GAIN_MAP_XMP.replace("hdrgm:Version",
                "hdrgm:Owner=\"1\" hdrgm:Version")));
        // Text between tags.
        assertFalse(UltraHdr.isGainMapOnlyXmp(GAIN_MAP_XMP.replace("</rdf:RDF>", "Someone</rdf:RDF>")));
        // No gain map at all.
        assertFalse(UltraHdr.isGainMapOnlyXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>"));
        // A directory naming something other than the primary picture and gain map.
        assertFalse(UltraHdr.isGainMapOnlyXmp(UltraHdr.primaryXmp("1.0", 1).replace("Primary", "Depth")));
    }

    @Test
    public void gainMapFields_keepsNumbersPerChannel_andDropsText() {
        String xmp = "<rdf:Description xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
                + " hdrgm:Version=\"1.0\" hdrgm:Gamma=\"Someone\">"
                + "<hdrgm:GainMapMax><rdf:Seq><rdf:li>2.5</rdf:li><rdf:li>2.4</rdf:li><rdf:li>2.3</rdf:li>"
                + "</rdf:Seq></hdrgm:GainMapMax></rdf:Description>";

        Map<String, List<String>> fields = UltraHdr.gainMapFields(xmp);

        assertEquals(List.of("1.0"), fields.get("Version"));
        assertEquals(List.of("2.5", "2.4", "2.3"), fields.get("GainMapMax"));
        assertFalse(fields.containsKey("Gamma"));
        String rebuilt = UltraHdr.gainMapXmp(fields);
        assertTrue(rebuilt.contains("<rdf:li>2.4</rdf:li>"));
        assertTrue(UltraHdr.isGainMapOnlyXmp(rebuilt));
    }

    @Test
    public void scan_flagsAGainMapThatCarriesItsOwnExif() throws Exception {
        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(fixture());

        List<String> found = keys(report.findings);
        assertTrue(found.contains("XMP")); // the camera's own field next to the gain map's
        assertTrue(found.contains("MULTI_PICTURE_INDEX"));
        assertTrue(found.contains("EMBEDDED_IMAGES"));
        assertFalse(report.isClean());
    }

    @Test
    public void sanitize_keepsTheGainMap_cleaned_withAnIndexThatFindsIt() throws Exception {
        File source = fixture();
        byte[] before = Files.readAllBytes(source.toPath());
        assertTrue(contains(before, "SecretGainMapMake"));
        assertTrue(contains(before, "SecretCameraValue"));

        File target = new File(temp.getRoot(), "clean.jpg");
        ImageContainerMetadata.sanitize(source, target);
        byte[] after = Files.readAllBytes(target.toPath());

        assertFalse(contains(after, "SecretPrimaryMake"));
        assertFalse(contains(after, "SecretGainMapMake"));
        assertFalse(contains(after, "SecretCameraValue"));
        assertFalse(contains(after, "GCamera"));
        assertTrue(contains(after, "hdrgm:GainMapMax=\"2.5\""));

        // The directory's length, the index's entry and the bytes after the picture all agree.
        Matcher length = Pattern.compile("Item:Length=\"(\\d+)\"")
                .matcher(new String(after, StandardCharsets.ISO_8859_1));
        assertTrue(length.find());
        int gainMapLength = Integer.parseInt(length.group(1));
        int gainMapStart = after.length - gainMapLength;
        assertEquals((byte) 0xFF, after[gainMapStart]);
        assertEquals((byte) 0xD8, after[gainMapStart + 1]);
        int mpf = indexOf(after, "MPF\0".getBytes(StandardCharsets.ISO_8859_1), 0);
        int tiff = mpf + 4;
        int entries = tiff + 50;
        assertEquals(gainMapLength, u32(after, entries + 16 + 4));
        assertEquals(gainMapStart - tiff, u32(after, entries + 16 + 8));
        assertEquals(gainMapStart, u32(after, entries + 4)); // the primary's size

        ImageContainerMetadata.Report report = ImageContainerMetadata.scan(target);
        assertTrue(keys(report.findings).toString(), report.isClean());
        assertTrue(keys(report.kept).contains("ULTRA_HDR_GAIN_MAP"));
    }

    @Test
    public void sanitize_ofAPlainJpegWithAnImageAfterIt_stillDropsIt() throws Exception {
        File source = fixture();
        byte[] bytes = Files.readAllBytes(source.toPath());
        // Without the gain map XMP, what follows the picture is just another image.
        String text = new String(bytes, StandardCharsets.ISO_8859_1).replace("hdr-gain-map", "hdr-gain-mXp");
        Files.write(source.toPath(), text.getBytes(StandardCharsets.ISO_8859_1));

        File target = new File(temp.getRoot(), "clean.jpg");
        ImageContainerMetadata.sanitize(source, target);
        byte[] after = Files.readAllBytes(target.toPath());

        assertEquals(-1, indexOf(after, new byte[] {(byte) 0xFF, (byte) 0xD8}, 2));
        assertFalse(contains(after, "MPF"));
    }

    private static long u32(byte[] data, int pos) {
        return ((long) (data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
    }
}
