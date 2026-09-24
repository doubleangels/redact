package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class XmlLikeMetadataFormatterTest {

    @Test
    public void looksLikeRdfOrXmp_detectsXmpPacketMarker() {
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(
                "<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>"));
    }

    @Test
    public void looksLikeRdfOrXmp_detectsXmpMetaRoot() {
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"></x:xmpmeta>"));
    }

    @Test
    public void looksLikeRdfOrXmp_detectsRdfNamespaceMarkers() {
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<rdf:RDF>content</rdf:RDF>"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(
                "plain text with xmlns:rdf inside"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("something <rdf:Description/>"));
        assertTrue(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(
                "<?xml version=\"1.0\"?><root xmlns:rdf=\"x\"/>"));
    }

    @Test
    public void looksLikeRdfOrXmp_ignoresShortSnippets() {
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<rdf:RDF/>"));
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("<x:xmpmeta>"));
    }

    @Test
    public void looksLikeRdfOrXmp_rejectsNullShortAndPlainXml() {
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(null));
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(""));
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp("short"));
        assertFalse(XmlLikeMetadataFormatter.looksLikeRdfOrXmp(
                "<root><child>value</child></root>"));
    }

    @Test
    public void formatForDisplay_passesThroughNullAndEmpty() {
        assertNull(XmlLikeMetadataFormatter.formatForDisplay(null));
        assertEquals("", XmlLikeMetadataFormatter.formatForDisplay(""));
    }

    @Test
    public void formatForDisplay_prettifiesWellFormedXml() {
        String formatted = XmlLikeMetadataFormatter.formatForDisplay(
                "<root><child>value</child></root>");
        assertEquals("<root>\n  <child>\n    value\n  </child>\n</root>", formatted);
    }

    @Test
    public void formatForDisplay_handlesSelfClosingElements() {
        String formatted = XmlLikeMetadataFormatter.formatForDisplay(
                "<root><item a=\"1\"/></root>");
        assertTrue(formatted.contains("<item a=\"1\"/>"));
        assertTrue(formatted.startsWith("<root>"));
        assertTrue(formatted.endsWith("</root>"));
    }

    @Test
    public void formatForDisplay_escapesAttributeQuotesAndAmpersands() {
        String formatted = XmlLikeMetadataFormatter.formatForDisplay(
                "<img alt=\"a&amp;b\"/>");
        assertTrue(formatted.contains("alt=\"a&amp;b\""));
    }

    @Test
    public void formatForDisplay_malformedInput_neverThrowsAndKeepsTags() {
        String formatted = XmlLikeMetadataFormatter.formatForDisplay("<a><b></a>");
        assertTrue(formatted != null);
        assertTrue(formatted.contains("<a") && formatted.contains("</a>"));
        assertTrue(formatted.contains("\n"));
    }
}