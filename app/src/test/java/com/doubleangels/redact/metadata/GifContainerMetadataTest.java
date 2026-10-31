package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** A GIF keeps every frame through a clean, and nothing else. */
public class GifContainerMetadataTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static void write(ByteArrayOutputStream out, int... bytes) {
        for (int b : bytes) {
            out.write(b);
        }
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }

    /** The header, a 2x1 screen and a two-colour global table. */
    private static void header(ByteArrayOutputStream out) {
        write(out, "GIF89a");
        write(out, 2, 0, 1, 0, 0x80, 0, 0);
        write(out, 0, 0, 0, 255, 255, 255);
    }

    /** A loop count, then two frames with their timing; what a clean must keep, in order. */
    private static void picture(ByteArrayOutputStream out) {
        write(out, 0x21, 0xFF, 11);
        write(out, "NETSCAPE2.0");
        write(out, 3, 1, 0, 0, 0);
        for (int frame = 0; frame < 2; frame++) {
            write(out, 0x21, 0xF9, 4, 0x04, 10, 0, 0, 0); // 100 ms
            write(out, 0x2C, 0, 0, 0, 0, 2, 0, 1, 0, 0);
            write(out, 2, 2, 0x4C, 0x01, 0); // LZW code size 2, one sub-block, end
        }
    }

    private File file(byte[] bytes) throws IOException {
        File f = temp.newFile();
        Files.write(f.toPath(), bytes);
        return f;
    }

    @Test
    public void sanitize_keepsFramesTimingAndLoop_andDropsCommentsXmpAndTrailingData() throws Exception {
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        header(tagged);
        write(tagged, 0x21, 0xFE, 13);
        write(tagged, "SecretComment");
        write(tagged, 0);
        write(tagged, 0x21, 0xFF, 11);
        write(tagged, "XMP DataXMP");
        write(tagged, 12);
        write(tagged, "SecretPerson");
        write(tagged, 0);
        write(tagged, 0x21, 0x01, 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10);
        write(tagged, "SecretText");
        write(tagged, 0);
        picture(tagged);
        write(tagged, 0x3B);
        write(tagged, "SecretAfterTrailer");

        File out = new File(temp.getRoot(), "clean.gif");
        GifContainerMetadata.sanitize(file(tagged.toByteArray()), out);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        header(expected);
        picture(expected);
        write(expected, 0x3B);
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(out.toPath()));
        assertTrue(GifContainerMetadata.isGif(out));
    }

    @Test
    public void sanitize_endsAGifWhoseTrailerIsMissing() throws Exception {
        ByteArrayOutputStream truncated = new ByteArrayOutputStream();
        header(truncated);
        picture(truncated);

        File out = new File(temp.getRoot(), "clean.gif");
        GifContainerMetadata.sanitize(file(truncated.toByteArray()), out);

        byte[] written = Files.readAllBytes(out.toPath());
        assertTrue(written[written.length - 1] == 0x3B);
    }

    @Test
    public void sanitize_refusesWhatIsNotAGif() throws Exception {
        assertFalse(GifContainerMetadata.isGif(file("not a gif at all".getBytes(StandardCharsets.US_ASCII))));
        try {
            GifContainerMetadata.sanitize(file("not a gif at all".getBytes(StandardCharsets.US_ASCII)),
                    new File(temp.getRoot(), "out.gif"));
            fail("expected an IOException so the caller re-encodes");
        } catch (IOException expected) {
            // the caller falls back to re-encoding
        }
    }
}
