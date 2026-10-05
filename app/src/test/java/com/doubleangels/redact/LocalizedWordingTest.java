package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Wording fixes found in review of the translations. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalizedWordingTest {

    private static Context context() {
        return RuntimeEnvironment.getApplication();
    }

    @Test
    @Config(qualifiers = "de")
    public void german_serialNumberLabelIsSpelledCorrectly() {
        assertEquals("Geräteseriennummer", context().getString(R.string.scan_risk_serial));
    }

    /**
     * The "Open in Maps" strings were translated with Apple Maps' localized product names
     * ("Plans", "Mappe", "Karten", ...). The action opens the device's default map app, which on
     * Android is not that product, so each language now says "map app".
     */
    @Test
    @Config(qualifiers = "fr")
    public void french_refersToTheMapApp() {
        assertEquals("Ouvrir dans l’appli de cartes", context().getString(R.string.scan_open_coordinates_in_maps));
        assertEquals("Ouvrir la position dans l’appli de cartes ?", context().getString(R.string.scan_maps_consent_title));
        assertEquals("Ouvrir l’appli de cartes", context().getString(R.string.scan_maps_consent_continue));
    }

    @Test
    @Config(qualifiers = "de")
    public void german_refersToTheMapApp() {
        assertEquals("In Karten-App öffnen", context().getString(R.string.scan_open_coordinates_in_maps));
        assertEquals("Karten-App öffnen", context().getString(R.string.scan_maps_consent_continue));
    }

    @Test
    @Config(qualifiers = "pt")
    public void portuguese_isGrammaticalAndRefersToTheMapApp() {
        assertEquals("Abrir no app de mapas", context().getString(R.string.scan_open_coordinates_in_maps));
        assertFalse(context().getString(R.string.scan_open_coordinates_in_maps).contains("no Mapas"));
    }

    @Test
    @Config(qualifiers = "it")
    public void italian_refersToTheMapApp() {
        assertEquals("Apri nell’app di mappe", context().getString(R.string.scan_open_coordinates_in_maps));
        assertEquals("Aprire la posizione nell’app di mappe?", context().getString(R.string.scan_maps_consent_title));
    }

    @Test
    @Config(qualifiers = "ru")
    public void russian_refersToTheMapApp() {
        assertEquals("Открыть в приложении карт", context().getString(R.string.scan_open_coordinates_in_maps));
        assertEquals("Открыть приложение карт", context().getString(R.string.scan_maps_consent_continue));
    }

    @Test
    @Config(qualifiers = "es")
    public void spanish_refersToTheMapApp() {
        assertEquals("Abrir en app de mapas", context().getString(R.string.scan_open_coordinates_in_maps));
        assertEquals("¿Abrir la ubicación en la app de mapas?", context().getString(R.string.scan_maps_consent_title));
    }
}
