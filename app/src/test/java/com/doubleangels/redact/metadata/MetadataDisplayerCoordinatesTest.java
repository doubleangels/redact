package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MetadataDisplayerCoordinatesTest {

    private static final char RECORD_SEP = '\u001e';
    private static final char UNIT_SEP = '\u001f';
    private static final double DELTA = 1e-4;

    private static String record(String key, String value) {
        return key + UNIT_SEP + value + RECORD_SEP;
    }

    @Test
    public void isLocationMetadataKey_matchesGpsPrefixAndLocation() {
        assertTrue(MetadataDisplayer.isLocationMetadataKey("GPS_LATITUDE"));
        assertTrue(MetadataDisplayer.isLocationMetadataKey("gpsTimestamp"));
        assertTrue(MetadataDisplayer.isLocationMetadataKey("location"));
        assertFalse(MetadataDisplayer.isLocationMetadataKey("MAKE"));
        assertFalse(MetadataDisplayer.isLocationMetadataKey("LOCATION_NAME"));
        assertFalse(MetadataDisplayer.isLocationMetadataKey(null));
    }

    @Test
    public void isUsableMapCoordinate_rejectsNullIslandAndOutOfRange() {
        assertTrue(MetadataDisplayer.isUsableMapCoordinate(39.6594, -104.962));
        assertTrue(MetadataDisplayer.isUsableMapCoordinate(-90, 180));
        assertFalse(MetadataDisplayer.isUsableMapCoordinate(0, 0));
        assertFalse(MetadataDisplayer.isUsableMapCoordinate(90.1, 10));
        assertFalse(MetadataDisplayer.isUsableMapCoordinate(10, -180.1));
    }

    @Test
    public void resolveMapCoordinates_returnsNullForMissingSections() {
        assertNull(MetadataDisplayer.resolveMapCoordinates(null));
        assertNull(MetadataDisplayer.resolveMapCoordinates(new HashMap<>()));
    }

    @Test
    public void resolveMapCoordinates_readsSeparatedLocationSection() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION,
                record("GPS_LATITUDE", "39.6594000") + record("GPS_LONGITUDE", "-104.9620000"));

        double[] coords = MetadataDisplayer.resolveMapCoordinates(sections);

        assertNotNull(coords);
        assertArrayEquals(new double[]{39.6594, -104.962}, coords, DELTA);
    }

    @Test
    public void resolveMapCoordinates_readsColonLinesCaseInsensitivelyWithCommaDecimals() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION,
                "gps_latitude: 48,8584\nGPS_Longitude: 2,2945\n");

        double[] coords = MetadataDisplayer.resolveMapCoordinates(sections);

        assertNotNull(coords);
        assertArrayEquals(new double[]{48.8584, 2.2945}, coords, DELTA);
    }

    @Test
    public void resolveMapCoordinates_fallsBackToBasicInfoSection() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_BASIC_INFO,
                record("FILE_NAME", "a.jpg") + record("GPS_LATITUDE", "-33.8568")
                        + record("GPS_LONGITUDE", "151.2153"));

        double[] coords = MetadataDisplayer.resolveMapCoordinates(sections);

        assertNotNull(coords);
        assertArrayEquals(new double[]{-33.8568, 151.2153}, coords, DELTA);
    }

    @Test
    public void resolveMapCoordinates_swapsLatLonWhenLatitudeOutOfRange() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION,
                record("GPS_LATITUDE", "-104.962") + record("GPS_LONGITUDE", "39.6594"));

        double[] coords = MetadataDisplayer.resolveMapCoordinates(sections);

        assertNotNull(coords);
        assertArrayEquals(new double[]{39.6594, -104.962}, coords, DELTA);
    }

    @Test
    public void resolveMapCoordinates_rejectsZeroCoordinates() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION,
                record("GPS_LATITUDE", "0.0") + record("GPS_LONGITUDE", "0.0"));

        assertNull(MetadataDisplayer.resolveMapCoordinates(sections));
    }

    @Test
    public void resolveMapCoordinates_rejectsUnparseableValues() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION,
                record("GPS_LATITUDE", "north") + record("GPS_LONGITUDE", "east"));

        assertNull(MetadataDisplayer.resolveMapCoordinates(sections));
    }

    @Test
    public void resolveMapCoordinates_parsesIso6709VideoLocation() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION, record("LOCATION", "+39.6594-104.9620/"));

        double[] coords = MetadataDisplayer.resolveMapCoordinates(sections);

        assertNotNull(coords);
        assertArrayEquals(new double[]{39.6594, -104.962}, coords, DELTA);
    }

    @Test
    public void resolveMapCoordinates_parsesVideoLocationFromBasicInfoColonLine() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_BASIC_INFO, "LOCATION: -33.8568+151.2153");

        double[] coords = MetadataDisplayer.resolveMapCoordinates(sections);

        assertNotNull(coords);
        assertArrayEquals(new double[]{-33.8568, 151.2153}, coords, DELTA);
    }

    @Test
    public void resolveMapCoordinates_rejectsMalformedVideoLocation() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION, record("LOCATION", "+39.6594"));
        assertNull(MetadataDisplayer.resolveMapCoordinates(sections));

        sections.put(MetadataDisplayer.SECTION_LOCATION, record("LOCATION", "+0.0+0.0"));
        assertNull(MetadataDisplayer.resolveMapCoordinates(sections));
    }

    @Test
    public void resolveMapCoordinates_prefersGpsKeysOverLocationString() {
        Map<String, String> sections = new HashMap<>();
        sections.put(MetadataDisplayer.SECTION_LOCATION,
                record("GPS_LATITUDE", "10.5") + record("GPS_LONGITUDE", "20.5")
                        + record("LOCATION", "+39.6594-104.9620"));

        double[] coords = MetadataDisplayer.resolveMapCoordinates(sections);

        assertNotNull(coords);
        assertArrayEquals(new double[]{10.5, 20.5}, coords, DELTA);
    }
}
