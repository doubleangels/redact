package com.doubleangels.redact;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fails the build if any translatable string is missing from, or malformed in, a locale. */
public class StringTranslationsTest {

    private static final File RES = new File("src/main/res");
    private static final Pattern FORMAT = Pattern.compile("%(\\d+\\$)?[sdf]");

    @Test
    public void everyLocaleTranslatesEveryString() throws Exception {
        Set<String> base = names(new File(RES, "values/strings.xml"), true);
        File[] dirs = RES.listFiles((d, n) -> n.startsWith("values-") && new File(d, n + "/strings.xml").exists());
        assertTrue("No translations found", dirs != null && dirs.length > 0);
        List<String> problems = new ArrayList<>();
        for (File dir : dirs) {
            Set<String> loc = names(new File(dir, "strings.xml"), false);
            for (String k : base) if (!loc.contains(k)) problems.add(dir.getName() + " missing " + k);
            for (String k : loc) if (!base.contains(k)) problems.add(dir.getName() + " extra " + k);
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void formatPlaceholdersMatchDefaultLocale() throws Exception {
        List<String> problems = new ArrayList<>();
        java.util.Map<String, Set<String>> base = placeholders(new File(RES, "values/strings.xml"));
        File[] dirs = RES.listFiles((d, n) -> n.startsWith("values-") && new File(d, n + "/strings.xml").exists());
        for (File dir : dirs) {
            for (java.util.Map.Entry<String, Set<String>> e : placeholders(new File(dir, "strings.xml")).entrySet()) {
                Set<String> want = base.get(e.getKey());
                if (want != null && !want.equals(e.getValue()))
                    problems.add(dir.getName() + " " + e.getKey() + " placeholders " + e.getValue() + " != " + want);
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    /**
     * "translatable=false" hides a string from the translation check above, so anything a user can
     * read must not use it. Only keys that are never shown to users are allowed.
     */
    @Test
    public void userVisibleStringsAreNeverMarkedUntranslatable() throws Exception {
        Set<String> notShownToUsers = new HashSet<>(java.util.Arrays.asList(
                "url_donate",                       // a link, identical in every language
                "scan_metadata_truncated_key",      // an internal row key, not display text
                "settings_section_developer",       // debug builds only
                "settings_wormaceptor_button",
                "settings_wormaceptor_subtitle"));
        NodeList nl = strings(new File(RES, "values/strings.xml"));
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) {
            if (!(nl.item(i) instanceof Element)) continue;
            Element e = (Element) nl.item(i);
            if ("false".equals(e.getAttribute("translatable")) && !notShownToUsers.contains(e.getAttribute("name"))) {
                problems.add(e.getAttribute("name") + " is shown to users but marked translatable=\"false\"");
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    /** Plural categories each language needs (CLDR, as Android lint's MissingQuantity applies them). */
    private static Set<String> quantitiesFor(String locale) {
        switch (locale) {
            case "ar":
                return new HashSet<>(java.util.Arrays.asList("zero", "one", "two", "few", "many", "other"));
            case "ru":
                return new HashSet<>(java.util.Arrays.asList("one", "few", "many", "other"));
            case "es":
            case "fr":
            case "it":
            case "pt":
                return new HashSet<>(java.util.Arrays.asList("one", "many", "other"));
            case "ja":
            case "ko":
            case "zh-rCN":
            case "zh-rTW":
                return new HashSet<>(java.util.Arrays.asList("other"));
            default:
                return new HashSet<>(java.util.Arrays.asList("one", "other"));
        }
    }

    @Test
    public void everyLocaleDefinesTheQuantitiesItsLanguageNeedsForEachPlural() throws Exception {
        List<String> problems = new ArrayList<>();
        Set<String> basePlurals = pluralNames(new File(RES, "values/strings.xml"));
        assertTrue("expected the count strings to be plurals", basePlurals.contains("convert_hero_files_count")
                && basePlurals.contains("scan_hero_fields_count"));
        File[] dirs = RES.listFiles((d, n) -> n.startsWith("values-") && new File(d, n + "/strings.xml").exists());
        for (File dir : dirs) {
            String locale = dir.getName().substring("values-".length());
            java.util.Map<String, Set<String>> have = pluralQuantities(new File(dir, "strings.xml"));
            for (String plural : basePlurals) {
                Set<String> got = have.get(plural);
                if (got == null) {
                    problems.add(dir.getName() + " missing plurals " + plural);
                } else if (!got.equals(quantitiesFor(locale))) {
                    problems.add(dir.getName() + " " + plural + " has " + got + ", needs " + quantitiesFor(locale));
                }
            }
        }
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    private static Set<String> pluralNames(File f) throws Exception {
        return pluralQuantities(f).keySet();
    }

    private static java.util.Map<String, Set<String>> pluralQuantities(File f) throws Exception {
        java.util.Map<String, Set<String>> out = new java.util.HashMap<>();
        NodeList nl = strings(f);
        for (int i = 0; i < nl.getLength(); i++) {
            if (!(nl.item(i) instanceof Element)) continue;
            Element e = (Element) nl.item(i);
            if (!"plurals".equals(e.getTagName())) continue;
            Set<String> quantities = new HashSet<>();
            NodeList items = e.getElementsByTagName("item");
            for (int j = 0; j < items.getLength(); j++) {
                quantities.add(((Element) items.item(j)).getAttribute("quantity"));
            }
            out.put(e.getAttribute("name"), quantities);
        }
        return out;
    }

    private static NodeList strings(File f) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
                .getDocumentElement().getChildNodes();
    }

    /** Names of resources; for the default locale only translatable ones. */
    private static Set<String> names(File f, boolean translatableOnly) throws Exception {
        Set<String> out = new HashSet<>();
        NodeList nl = strings(f);
        for (int i = 0; i < nl.getLength(); i++) {
            if (!(nl.item(i) instanceof Element)) continue;
            Element e = (Element) nl.item(i);
            if (translatableOnly && "false".equals(e.getAttribute("translatable"))) continue;
            out.add(e.getAttribute("name"));
        }
        return out;
    }

    private static java.util.Map<String, Set<String>> placeholders(File f) throws Exception {
        java.util.Map<String, Set<String>> out = new java.util.HashMap<>();
        NodeList nl = strings(f);
        for (int i = 0; i < nl.getLength(); i++) {
            if (!(nl.item(i) instanceof Element)) continue;
            Element e = (Element) nl.item(i);
            Set<String> s = new HashSet<>();
            Matcher m = FORMAT.matcher(e.getTextContent());
            while (m.find()) s.add(m.group().replaceFirst("^%1\\$", "%"));
            out.put(e.getAttribute("name"), s);
        }
        return out;
    }
}
