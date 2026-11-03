package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.util.Pair;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.List;

/** How Scan turns a metadata block into rows and names a section it has no title for. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ScanFragmentHelpersTest {

    /** Between a field's name and value, and between fields, as MetadataDisplayer writes them. */
    private static final char UNIT = (char) 0x1F;
    private static final char RECORD = (char) 0x1E;

    @SuppressWarnings("unchecked")
    private static List<Pair<String, String>> rows(String block) throws Exception {
        Method m = ScanFragment.class.getDeclaredMethod("parseMetadataBlockToRows", String.class);
        m.setAccessible(true);
        return (List<Pair<String, String>>) m.invoke(null, block);
    }

    @Test
    public void parsesSeparatedEntries_andPlainLines() throws Exception {
        String block = "MAKE" + UNIT + "Acme" + RECORD + RECORD + "no key here" + RECORD + UNIT + "value only";
        List<Pair<String, String>> separated = rows(block);
        assertEquals(3, separated.size());
        assertEquals("MAKE", separated.get(0).first);
        assertEquals("Acme", separated.get(0).second);
        assertNull(separated.get(1).first);
        assertEquals("no key here", separated.get(1).second);
        assertNull(separated.get(2).first);

        List<Pair<String, String>> lines = rows("Model: X1" + '\n' + '\n' + "  just text  " + '\n' + ": empty key");
        assertEquals(3, lines.size());
        assertEquals("Model", lines.get(0).first);
        assertEquals("X1", lines.get(0).second);
        assertNull(lines.get(1).first);
        assertEquals("just text", lines.get(1).second);
        assertNull(lines.get(2).first);
    }

    @Test
    public void namesAnUnknownSection_inTitleCase() throws Exception {
        Method m = ScanFragment.class.getDeclaredMethod("formatCustomSectionTitle", String.class);
        m.setAccessible(true);
        assertEquals("Hidden Vendor Data", m.invoke(new ScanFragment(), "HIDDEN_vendor  data"));
    }
}
