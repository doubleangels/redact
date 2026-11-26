package com.doubleangels.redact.whatsnew;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.doubleangels.redact.BuildConfig;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Holds every release's in-app notes to the rules in docs/roadmap/whats-new.md: written for this
 * versionCode, 1 to 8 items, short titles in Title Case and short bodies, and every locale with the
 * same items. A release whose notes are missing or for another version fails here, not on someone's
 * phone.
 */
public class WhatsNewContentTest {

    private static final File RES = new File("src/main/res");
    private static final int MAX_ITEMS = 8;
    private static final int MAX_TITLE = 50;
    private static final int MAX_BODY = 300;
    private static final Set<String> KINDS =
            Set.of(WhatsNew.KIND_NEW, WhatsNew.KIND_IMPROVED, WhatsNew.KIND_FIXED);

    /** Words Title Case leaves lowercase unless they start the title. */
    private static final Set<String> MINOR_WORDS = Set.of("a", "an", "the", "and", "but", "or",
            "nor", "for", "so", "yet", "as", "at", "by", "in", "of", "off", "on", "per", "to", "up",
            "via", "from", "into", "onto", "with");

    private static Document parse(File f) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
    }

    private static String named(Document doc, String tag, String name) {
        NodeList nodes = doc.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element e = (Element) nodes.item(i);
            if (name.equals(e.getAttribute("name"))) {
                return e.getTextContent().trim();
            }
        }
        return null;
    }

    private static List<String> array(Document doc, String name) {
        NodeList arrays = doc.getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            Element e = (Element) arrays.item(i);
            if (name.equals(e.getAttribute("name"))) {
                List<String> items = new ArrayList<>();
                NodeList nodes = e.getElementsByTagName("item");
                for (int j = 0; j < nodes.getLength(); j++) {
                    // As Android shows it: \' and \" are an apostrophe and a quote.
                    items.add(nodes.item(j).getTextContent().trim()
                            .replace("\\'", "'").replace("\\\"", "\""));
                }
                return items;
            }
        }
        return null;
    }

    private static Document english() throws Exception {
        return parse(new File(RES, "values/whats_new.xml"));
    }

    @Test
    public void notes_areForThisVersionCode() throws Exception {
        String code = named(english(), "integer", "whats_new_version_code");
        assertNotNull(code);
        assertEquals("Run /whatsnew: the notes are for another version",
                BuildConfig.VERSION_CODE, Integer.parseInt(code));
        String announce = named(english(), "bool", "whats_new_announce");
        assertTrue("true".equals(announce) || "false".equals(announce));
    }

    @Test
    public void english_hasOneToEightItems_withKnownKindsAndIcons() throws Exception {
        Document doc = english();
        List<String> kinds = array(doc, "whats_new_kinds");
        List<String> icons = array(doc, "whats_new_icons");
        List<String> titles = array(doc, "whats_new_titles");
        List<String> bodies = array(doc, "whats_new_bodies");
        assertNotNull(kinds);
        assertTrue(kinds.size() >= 1 && kinds.size() <= MAX_ITEMS);
        assertEquals(kinds.size(), icons.size());
        assertEquals(kinds.size(), titles.size());
        assertEquals(kinds.size(), bodies.size());
        for (String kind : kinds) {
            assertTrue("unknown kind " + kind, KINDS.contains(kind));
        }
        for (String icon : icons) {
            assertTrue("unknown icon " + icon, WhatsNewIcons.keys().contains(icon));
        }
    }

    @Test
    public void englishTitles_areInTitleCase() throws Exception {
        List<String> problems = new ArrayList<>();
        for (String title : array(english(), "whats_new_titles")) {
            String[] words = title.split("\\s+");
            for (int i = 0; i < words.length; i++) {
                String word = words[i];
                if (word.isEmpty() || !Character.isLowerCase(word.charAt(0))) {
                    continue;
                }
                if (i == 0 || !MINOR_WORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
                    problems.add("\"" + word + "\" in \"" + title + "\"");
                }
            }
        }
        assertTrue("Write What's New titles in Title Case: " + String.join(", ", problems),
                problems.isEmpty());
    }

    @Test
    public void everyLocale_hasTheSameItems_withinTheLengthLimits() throws Exception {
        int count = array(english(), "whats_new_titles").size();
        File[] dirs = RES.listFiles((d, n) -> (n.equals("values") || n.startsWith("values-"))
                && new File(d, n + "/strings.xml").exists());
        assertNotNull(dirs);
        List<String> problems = new ArrayList<>();
        for (File dir : dirs) {
            File notes = new File(dir, "whats_new.xml");
            if (!notes.exists()) {
                problems.add(dir.getName() + " has no whats_new.xml");
                continue;
            }
            Document doc = parse(notes);
            List<String> titles = array(doc, "whats_new_titles");
            List<String> bodies = array(doc, "whats_new_bodies");
            if (titles == null || titles.size() != count) {
                problems.add(dir.getName() + " titles: " + (titles == null ? "missing" : titles.size()));
            }
            if (bodies == null || bodies.size() != count) {
                problems.add(dir.getName() + " bodies: " + (bodies == null ? "missing" : bodies.size()));
            }
            for (String t : titles == null ? List.<String>of() : titles) {
                if (t.isEmpty() || t.length() > MAX_TITLE) {
                    problems.add(dir.getName() + " title of " + t.length() + ": " + t);
                }
            }
            for (String b : bodies == null ? List.<String>of() : bodies) {
                if (b.isEmpty() || b.length() > MAX_BODY) {
                    problems.add(dir.getName() + " body of " + b.length() + ": " + b);
                }
            }
            if (!dir.getName().equals("values") && (named(doc, "integer", "whats_new_version_code") != null
                    || array(doc, "whats_new_kinds") != null || array(doc, "whats_new_icons") != null)) {
                problems.add(dir.getName() + " repeats an untranslated part; it belongs in values/ only");
            }
        }
        assertEquals(13, dirs.length);
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }
}
