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
}
