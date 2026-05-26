package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
public class XmlLikeMetadataFormatterTest {

    @Test
    public void looksLikeRdfOrXmp() {
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(null));
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("short"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<?xpacket begin=\"\"?>"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<root><rdf:RDF xmlns:rdf=\"x\"></rdf:RDF></root>"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("prefix xmlns:rdf suffix padding"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<rdf:Description rdf:about=\"\"/>"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<?xml version=\"1.0\"?><rdf:RDF/>"));
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<?xml version=\"1.0\"?><note/>"));
    }

    @Test
    public void formatForDisplay_nullAndEmpty() {
        assertEquals(null, XmlLikeMetadataFormatter.formatForDisplay(null));
        assertEquals("", XmlLikeMetadataFormatter.formatForDisplay(""));
    }

    @Test
    public void formatForDisplay_validXml() {
        String raw = "<?xml version=\"1.0\"?><note id=\"1\">text</note>";
        String formatted = XmlLikeMetadataFormatter.formatForDisplay(raw);
        assertTrue(formatted.contains("<note"));
        assertTrue(formatted.contains("text"));
    }

    @Test
    public void formatForDisplay_invalidFallsBackToNaiveBreaks() {
        String raw = "<a><b>value</b></a>";
        String broken = "<unclosed";
        String formatted = XmlLikeMetadataFormatter.formatForDisplay(broken);
        assertTrue(formatted.contains(">\n<") || formatted.contains(broken));
    }

    @Test
    public void formatForDisplay_escapesAttributesInValidXml() {
        String raw = "<tag attr=\"a&amp;b&quot;c\"/>";
        String formatted = XmlLikeMetadataFormatter.formatForDisplay(raw);
        assertTrue(formatted.contains("tag") || formatted.contains("attr"));
    }

    @Test
    public void formatForDisplay_handlesNamespacesProcessingInstructionsAndCdata() {
        String raw =
                "<?xml version=\"1.0\"?>"
                        + "<?xpacket begin=\"id\"?>"
                        + "<rdf:RDF xmlns:rdf=\"urn:rdf\" xmlns:ex=\"urn:ex\">"
                        + "<rdf:Description ex:name=\"a&amp;b&quot;c\" xml:lang=\"en\" xmlns:xml=\"http://www.w3.org/XML/1998/namespace\"><![CDATA[text]]></rdf:Description>"
                        + "<empty/>"
                        + "</rdf:RDF>";

        String formatted = XmlLikeMetadataFormatter.formatForDisplay(raw);

        assertTrue(formatted.contains("<rdf:RDF"));
        assertTrue(formatted.contains("ex:name=\"a&amp;amp;b&amp;quot;c\"")
                || formatted.contains("ex:name=\"a&amp;b&quot;c\""));
        assertTrue(formatted.contains("xml:lang=\"en\"") || formatted.contains("lang=\"en\""));
        assertTrue(formatted.contains("text"));
        assertTrue(formatted.contains("<empty/>"));
        assertTrue(formatted.contains("</rdf:RDF>"));
    }

    @Test
    public void privateEscapeAttr_returnsEmptyStringForNull() throws Exception {
        Method method = XmlLikeMetadataFormatter.class.getDeclaredMethod("escapeAttr", String.class);
        method.setAccessible(true);

        assertEquals("", method.invoke(null, new Object[]{null}));
        assertEquals("a&amp;&quot;b", method.invoke(null, "a&\"b"));
    }
}
