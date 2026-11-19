package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** Pictures carried in base64 inside XMP are described, not spelled out. */
@RunWith(RobolectricTestRunner.class)
public class EmbeddedDataTest {

    private final Context context = RuntimeEnvironment.getApplication();

    private static byte[] picture(Bitmap.CompressFormat format, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        // Noise, so the encoded picture is large enough to be a block worth describing.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int v = (x * 7919 + y * 104729) * 31;
                bitmap.setPixel(x, y, 0xFF000000 | (v & 0xFFFFFF));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(format, 95, out);
        return out.toByteArray();
    }

    private String shown(String xmp) {
        return EmbeddedData.render(context, EmbeddedData.summarize(xmp));
    }

    @Test
    public void aDepthMapInAnAttribute_isDescribedByFormatPixelsAndSize() {
        byte[] jpeg = picture(Bitmap.CompressFormat.JPEG, 64, 48);
        String data = Base64.getEncoder().encodeToString(jpeg);
        assertTrue("the test picture must be long enough", data.length() >= EmbeddedData.MIN_RUN);
        String xmp = "<rdf:Description GDepth:Format=\"RangeInverse\" GDepth:Near=\"0.5\" GDepth:Data=\""
                + data + "\"/>";

        String shown = shown(xmp);

        assertTrue(shown, shown.contains("GDepth:Format=\"RangeInverse\""));
        assertTrue(shown, shown.contains("GDepth:Near=\"0.5\""));
        assertTrue(shown, shown.contains("[JPEG · 64×48 · "));
        assertFalse("the base64 is gone", shown.contains(data.substring(0, 64)));
        assertTrue("much shorter", shown.length() < 200);
    }

    @Test
    public void aWrappedBlockInAnElement_isDescribedToo() {
        byte[] png = picture(Bitmap.CompressFormat.PNG, 40, 30);
        String wrapped = Base64.getMimeEncoder().encodeToString(png); // 76-character lines
        assertTrue(wrapped.contains("\r\n"));
        String xmp = "<GImage:Data>\n" + wrapped + "\n</GImage:Data><GImage:Mime>image/png</GImage:Mime>";

        String shown = shown(xmp);

        assertTrue(shown, shown.contains("<GImage:Data>\n[PNG · 40×30 · "));
        assertTrue(shown, shown.contains("<GImage:Mime>image/png</GImage:Mime>"));
    }

    @Test
    public void ordinaryTextAndShortValues_areLeftAsTheyAre() {
        StringBuilder words = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            words.append("photo taken at the beach by someone ");
        }
        String text = "<dc:description>" + words + "</dc:description>"
                + "<xmpMM:DocumentID>xmp.did:0123456789abcdef0123456789abcdef</xmpMM:DocumentID>";
        assertEquals(text, EmbeddedData.summarize(text));
        assertEquals(text, shown(text));
    }

    @Test
    public void aLongRunThatIsNotBase64_isLeftAsItIs() {
        // 513 letters: one past a multiple of four, a length no base64 block can have.
        String notBase64 = "x" + "A".repeat(EmbeddedData.MIN_RUN);
        String text = "<v>" + notBase64 + "</v>";
        assertEquals(text, EmbeddedData.summarize(text));
    }

    @Test
    public void describe_readsTheFormatAndSizeOfEachKindOfPicture() {
        assertEquals("JPEG", EmbeddedData.describe(picture(Bitmap.CompressFormat.JPEG, 16, 9))[0]);
        assertEquals("16×9", EmbeddedData.describe(picture(Bitmap.CompressFormat.JPEG, 16, 9))[1]);
        assertEquals("10×7", EmbeddedData.describe(picture(Bitmap.CompressFormat.PNG, 10, 7))[1]);
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 3, 0, 2, 0, 0, 0};
        assertEquals("GIF", EmbeddedData.describe(gif)[0]);
        assertEquals("3×2", EmbeddedData.describe(gif)[1]);
        byte[] unknown = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        assertEquals("", EmbeddedData.describe(unknown)[0]);
        assertEquals("", EmbeddedData.describe(unknown)[1]);
    }

    @Test
    public void dataThatIsNotAPicture_showsItsSizeAlone() {
        byte[] raw = new byte[2048];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) (i * 31);
        }
        String shown = shown("<GCamera:Data>" + Base64.getEncoder().encodeToString(raw) + "</GCamera:Data>");
        assertTrue(shown, shown.matches("<GCamera:Data>\\[[^·\\]]+\\]</GCamera:Data>"));
    }
}
