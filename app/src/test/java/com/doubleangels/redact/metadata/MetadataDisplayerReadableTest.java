package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MetadataDisplayerReadableTest {

    @Test
    public void plainAndMultilineTextIsReadable() {
        assertTrue(MetadataDisplayer.isReadableText("Pixel 10 Pro"));
        assertTrue(MetadataDisplayer.isReadableText("<x:xmpmeta>\n  <rdf/>\r\n\t</x:xmpmeta>"));
        assertTrue(MetadataDisplayer.isReadableText("Café ©"));
    }

    @Test
    public void binaryLookingTextIsNotReadable() {
        assertFalse(MetadataDisplayer.isReadableText("\u0002\u0002\u0000\u0000"));
        assertFalse(MetadataDisplayer.isReadableText("abc�def"));
        assertFalse(MetadataDisplayer.isReadableText("a\u0085b"));
    }

    @Test
    public void questionMarksExifInterfaceMadeFromBytes_areNotText() {
        // A Pixel photo's Components Configuration (01 02 03 00) and Scene Type (01) read "???" and "?".
        assertTrue(MetadataDisplayer.replacedUnprintableBytes("???", new byte[] {1, 2, 3, 0}));
        assertTrue(MetadataDisplayer.replacedUnprintableBytes("?", new byte[] {1}));
    }

    @Test
    public void realQuestionMarks_andValuesWithoutRawBytes_stayText() {
        assertFalse(MetadataDisplayer.replacedUnprintableBytes("Why?", "Why?".getBytes()));
        assertFalse(MetadataDisplayer.replacedUnprintableBytes("Pixel 10 Pro", new byte[] {1, 2}));
        assertFalse(MetadataDisplayer.replacedUnprintableBytes("???", null));
    }
}
