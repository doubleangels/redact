package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** The count strings must use the right plural form, and formerly English-only text must be localized. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalizedCountsTest {

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    private static String files(int n) {
        return context().getResources().getQuantityString(R.plurals.convert_hero_files_count, n, n);
    }

    /** Maps Arabic-Indic digits to ASCII: which digits %d prints for "ar" depends on the JDK's CLDR data. */
    private static String asciiDigits(String s) {
        StringBuilder out = new StringBuilder();
        for (char c : s.toCharArray()) {
            out.append(c >= '٠' && c <= '٩' ? (char) ('0' + (c - '٠')) : c);
        }
        return out.toString();
    }

    private static String fields(int n) {
        return context().getResources().getQuantityString(R.plurals.scan_hero_fields_count, n, n);
    }

    @Test
    public void english_usesSingularForOne() {
        assertEquals("1 file", files(1));
        assertEquals("2 files", files(2));
        assertEquals("0 files", files(0));
        assertEquals("1 field", fields(1));
        assertEquals("8 fields", fields(8));
    }

    @Test
    @Config(qualifiers = "ru")
    public void russian_followsItsFourPluralForms() {
        assertEquals("1 файл", files(1));
        assertEquals("2 файла", files(2));
        assertEquals("5 файлов", files(5));
        assertEquals("21 файл", files(21));
        assertEquals("11 файлов", files(11));
        assertEquals("1 поле", fields(1));
        assertEquals("3 поля", fields(3));
        assertEquals("12 полей", fields(12));
    }

    @Test
    @Config(qualifiers = "ar")
    public void arabic_followsItsSixPluralForms() {
        assertEquals("ملف واحد", asciiDigits(files(1)));
        assertEquals("ملفان", asciiDigits(files(2)));
        assertEquals("3 ملفات", asciiDigits(files(3)));
        assertEquals("11 ملفًا", asciiDigits(files(11)));
        assertEquals("100 ملف", asciiDigits(files(100)));
        assertEquals("0 ملف", asciiDigits(files(0)));
    }

    @Test
    @Config(qualifiers = "de")
    public void german_hasDistinctSingularAndPlural() {
        assertEquals("1 Datei", files(1));
        assertEquals("4 Dateien", files(4));
        assertEquals("1 Feld", fields(1));
        assertEquals("5 Felder", fields(5));
    }

    @Test
    @Config(qualifiers = "ja")
    public void japanese_hasASingleForm() {
        assertEquals("1 個のファイル", files(1));
        assertEquals("7 個のファイル", files(7));
    }

    @Test
    @Config(qualifiers = "de")
    public void emptyStateAndNotificationText_areNoLongerEnglishOnly() {
        assertEquals("Metadaten Bereinigen und Entfernen", context().getString(R.string.clean_empty_state_title));
        assertEquals("Medienformate Konvertieren", context().getString(R.string.convert_empty_state_title));
        assertEquals("Berechtigung Erteilen", context().getString(R.string.scan_grant_location_button));
        assertEquals("Diesmal konnten keine Dateien bereinigt werden.", context().getString(R.string.notification_clean_failed));
        assertNotEquals("Image", context().getString(R.string.convert_item_type_image));
    }
}
