package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

/** The zoom chip's number: whole numbers plain, others to one decimal place, in the user's locale. */
public class CensorZoomLabelTest {

    private Locale saved;

    @Before
    public void setUp() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.US);
    }

    @After
    public void tearDown() {
        Locale.setDefault(saved);
    }

    @Test
    public void wholeNumbers_haveNoDecimal() {
        assertEquals("2", CensorFragment.zoomLabel(2f));
        assertEquals("16", CensorFragment.zoomLabel(16f));
        assertEquals("2", CensorFragment.zoomLabel(1.98f));
    }

    @Test
    public void others_showOnePlace_inTheLocaleFormat() {
        assertEquals("2.5", CensorFragment.zoomLabel(2.5f));
        assertEquals("3.3", CensorFragment.zoomLabel(3.27f));
        Locale.setDefault(Locale.GERMANY);
        assertEquals("2,5", CensorFragment.zoomLabel(2.5f));
    }
}
