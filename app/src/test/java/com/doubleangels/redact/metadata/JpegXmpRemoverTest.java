package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class JpegXmpRemoverTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] app1(String identifier, String payload) {
        byte[] data = (identifier + "\0" + payload).getBytes(StandardCharsets.ISO_8859_1);
        int length = data.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF);
        out.write(0xE1);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    private static byte[] plainJpeg() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, out);
        return out.toByteArray();
    }

    /** A JPEG laid out like a Google Camera photo: main XMP, then Extended XMP with the makernote. */
    private File pixelStyleJpeg(byte[] plain) throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(plain, 0, 2);
        jpeg.write(app1("http://ns.adobe.com/xap/1.0/",
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF/></x:xmpmeta>"));
        jpeg.write(app1("http://ns.adobe.com/xmp/extension/",
                "0123456789ABCDEF0123456789ABCDEF" + "\0\0\0@\0\0\0\0"
                        + "<x:xmpmeta GCamera:HdrPlusMakernote=\"SECRET\"/>"));
        jpeg.write(plain, 2, plain.length - 2);
        File file = folder.newFile("pixel.jpg");
        Files.write(file.toPath(), jpeg.toByteArray());
        return file;
    }

    @Test
    public void removeAllXmp_dropsMainAndExtendedXmp_andKeepsTheImage() throws Exception {
        byte[] plain = plainJpeg();
        File pixel = pixelStyleJpeg(plain);
        assertTrue(MetadataStripper.JpegXmpRemover.hasXmpSegment(pixel));

        File cleaned = folder.newFile("cleaned.jpg");
        try (FileOutputStream out = new FileOutputStream(cleaned)) {
            new MetadataStripper.JpegXmpRemover().removeAllXmp(pixel, out);
        }

        byte[] result = Files.readAllBytes(cleaned.toPath());
        String text = new String(result, StandardCharsets.ISO_8859_1);
        assertFalse(text.contains("HdrPlusMakernote"));
        assertFalse(text.contains("ns.adobe.com"));
        assertFalse(MetadataStripper.JpegXmpRemover.hasXmpSegment(cleaned));
        assertArrayEquals(plain, result);
    }

    @Test
    public void hasXmpSegment_findsExtendedXmpOnItsOwn() throws Exception {
        byte[] plain = plainJpeg();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(plain, 0, 2);
        jpeg.write(app1("http://ns.adobe.com/xmp/extension/", "0123456789ABCDEF0123456789ABCDEF"));
        jpeg.write(plain, 2, plain.length - 2);
        File file = folder.newFile("extended.jpg");
        Files.write(file.toPath(), jpeg.toByteArray());

        assertTrue(MetadataStripper.JpegXmpRemover.hasXmpSegment(file));
    }

    @Test
    public void hasXmpSegment_isFalseForAPlainJpeg() throws Exception {
        File file = folder.newFile("plain.jpg");
        Files.write(file.toPath(), plainJpeg());
        assertFalse(MetadataStripper.JpegXmpRemover.hasXmpSegment(file));
    }
}
