package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MetadataLabelsTest {

    @Test
    public void underscoresBecomeSpacesAndWordsAreTitleCased() {
        assertEquals("Aperture Value", MetadataLabels.format("APERTURE_VALUE"));
        assertEquals("Exposure Bias Value", MetadataLabels.format("EXPOSURE_BIAS_VALUE"));
        assertEquals("Display Name", MetadataLabels.format("DISPLAY_NAME"));
    }

    @Test
    public void knownAcronymsStayUpperCase() {
        assertEquals("GPS Latitude", MetadataLabels.format("GPS_LATITUDE"));
        assertEquals("XMP Extended", MetadataLabels.format("XMP_EXTENDED"));
        assertEquals("MIME Type", MetadataLabels.format("MIME_TYPE"));
        assertEquals("ICC Profile", MetadataLabels.format("ICC_PROFILE"));
    }

    @Test
    public void mixedCaseAndEmptyNames() {
        assertEquals("Model", MetadataLabels.format("Model"));
        assertEquals("Gcamera", MetadataLabels.format("GCamera"));
        assertEquals("", MetadataLabels.format(null));
        assertEquals("", MetadataLabels.format(""));
    }
}
