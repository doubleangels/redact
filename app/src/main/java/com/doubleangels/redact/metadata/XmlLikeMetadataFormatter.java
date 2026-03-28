package com.doubleangels.redact.metadata;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;

/**
 * Detects embedded XMP / RDF / generic XML metadata blobs and formats them for on-screen reading.
 */
final class XmlLikeMetadataFormatter {

    private XmlLikeMetadataFormatter() {
    }

    static boolean looksLikeRdfOrXmp(String s) {
        if (s == null) {
            return false;
        }
        String t = s.trim();
        if (t.length() < 12) {
            return false;
        }
        return t.startsWith("<?xpacket")
                || t.startsWith("<x:xmpmeta")
                || t.contains("<rdf:RDF")
                || t.contains("xmlns:rdf")
                || t.contains("<rdf:")
                || (t.startsWith("<?xml") && t.contains("rdf:"));
    }

    /**
     * Returns a readable multi-line representation, or the original string if parsing fails.
     */
    static String formatForDisplay(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            return prettifyWithXmlPullParser(raw.trim());
        } catch (XmlPullParserException | IOException e) {
            return naiveInsertLineBreaks(raw);
        }
    }

    private static String naiveInsertLineBreaks(String raw) {
        return raw.replace("><", ">\n<").trim();
    }

    private static String prettifyWithXmlPullParser(String raw) throws XmlPullParserException, IOException {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        p.setInput(new StringReader(raw));
        StringBuilder sb = new StringBuilder();
        final String indent = "  ";
        int depth = 0;
        int event = p.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            switch (event) {
                case XmlPullParser.START_DOCUMENT:
                    break;
                case XmlPullParser.PROCESSING_INSTRUCTION:
                    sb.append('\n').append(indent.repeat(depth)).append("<?").append(p.getText()).append("?>");
                    break;
                case XmlPullParser.START_TAG: {
                    sb.append('\n').append(indent.repeat(depth)).append('<');
                    String prefix = p.getPrefix();
                    if (prefix != null && !prefix.isEmpty()) {
                        sb.append(prefix).append(':');
                    }
                    sb.append(p.getName());
                    for (int i = 0; i < p.getAttributeCount(); i++) {
                        sb.append(' ');
                        String name = p.getAttributeName(i);
                        if (name != null && name.indexOf(':') >= 0) {
                            sb.append(name);
                        } else {
                            String ap = p.getAttributePrefix(i);
                            if (ap != null && !ap.isEmpty()) {
                                sb.append(ap).append(':');
                            }
                            sb.append(name != null ? name : "");
                        }
                        sb.append("=\"").append(escapeAttr(p.getAttributeValue(i))).append('"');
                    }
                    if (p.isEmptyElementTag()) {
                        sb.append("/>");
                    } else {
                        sb.append('>');
                        depth++;
                    }
                    break;
                }
                case XmlPullParser.END_TAG:
                    depth = Math.max(0, depth - 1);
                    sb.append('\n').append(indent.repeat(depth)).append("</");
                    String ep = p.getPrefix();
                    if (ep != null && !ep.isEmpty()) {
                        sb.append(ep).append(':');
                    }
                    sb.append(p.getName()).append('>');
                    break;
                case XmlPullParser.TEXT:
                case XmlPullParser.CDSECT: {
                    String text = p.getText();
                    if (text != null) {
                        String trimmed = text.trim();
                        if (!trimmed.isEmpty()) {
                            sb.append('\n').append(indent.repeat(depth)).append(trimmed);
                        }
                    }
                    break;
                }
                default:
                    break;
            }
            event = p.next();
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? naiveInsertLineBreaks(raw) : out;
    }

    private static String escapeAttr(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("&", "&amp;").replace("\"", "&quot;");
    }
}
