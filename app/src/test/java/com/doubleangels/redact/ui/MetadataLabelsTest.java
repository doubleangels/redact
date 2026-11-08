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
    public void acronymsAreTitleCasedToo() {
        assertEquals("Gps Latitude", MetadataLabels.format("GPS_LATITUDE"));
        assertEquals("Xmp Extended", MetadataLabels.format("XMP_EXTENDED"));
        assertEquals("Mime Type", MetadataLabels.format("MIME_TYPE"));
        assertEquals("Icc Profile", MetadataLabels.format("ICC_PROFILE"));
    }

    @Test
    public void mixedCaseAndEmptyNames() {
        assertEquals("Model", MetadataLabels.format("Model"));
        assertEquals("Gcamera", MetadataLabels.format("GCamera"));
        assertEquals("", MetadataLabels.format(null));
        assertEquals("", MetadataLabels.format(""));
    }
}
