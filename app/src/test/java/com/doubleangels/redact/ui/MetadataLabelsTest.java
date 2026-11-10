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

    @Test
    public void unlistedAcronymsAreCaughtByTheFailOver() {
        assertEquals("QRS Data", MetadataLabels.format("QRS_DATA"));
        assertEquals("APP7 Data", MetadataLabels.format("APP7_DATA"));
        assertEquals("HEIF Item Name", MetadataLabels.format("HEIF_ITEM_NAME"));
        assertEquals("Ultra HDR Gain Map", MetadataLabels.format("ULTRA_HDR_GAIN_MAP"));
        assertEquals("Samsung Trailer", MetadataLabels.format("SAMSUNG_TRAILER"));
    }

    @Test
    public void otherSeparatorsAndCamelCaseSplitIntoWords() {
        assertEquals("PNG Text Author", MetadataLabels.format("PNG_TEXT_Author"));
        assertEquals("HDR Plus Makernote", MetadataLabels.format("HdrPlusMakernote"));
        assertEquals("XMP Tool", MetadataLabels.format("xmp:tool"));
        assertEquals("YCBCR Positioning", MetadataLabels.format("YCBCR_POSITIONING"));
        assertEquals("Gcamera", MetadataLabels.format("GCamera"));
        assertEquals("__", MetadataLabels.format("__"));
    }

    @Test
    public void exifTagNamesThatConvertAwkwardly() {
        assertEquals("YCbCr Positioning", MetadataLabels.format("Y_CB_CR_POSITIONING"));
        assertEquals("YCbCr Sub Sampling", MetadataLabels.format("Y_CB_CR_SUB_SAMPLING"));
        assertEquals("GPS DOP", MetadataLabels.format("GPSDOP"));
        assertEquals("CFA Pattern", MetadataLabels.format("CFA_PATTERN"));
        assertEquals("GPS Version ID", MetadataLabels.format("GPS_VERSION_ID"));
        assertEquals("EXIF IFD Pointer", MetadataLabels.format("EXIF_IFD_POINTER"));
    }
}
