package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.service.quicksettings.Tile;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowTileService;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CleanTileServiceTest {

    private static CleanTileService service() {
        return Robolectric.setupService(CleanTileService.class);
    }

    private static void assertOpensClean() {
        Intent started = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(MainActivity.class.getName(), started.getComponent().getClassName());
        assertEquals(Intent.ACTION_MAIN, started.getAction());
        assertEquals(MainActivity.TAG_CLEAN, started.getStringExtra(MainActivity.EXTRA_TAB));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test
    public void startListening_showsTheTileAsAShortcut() {
        CleanTileService service = service();
        service.onStartListening();
        Tile tile = service.getQsTile();
        assertNotNull(tile);
        assertEquals(Tile.STATE_INACTIVE, tile.getState());
    }

    @Test
    public void click_opensTheCleanTab() {
        service().onClick();
        assertOpensClean();
    }

    @Test
    public void click_onTheLockScreen_unlocksFirst() {
        CleanTileService service = service();
        ShadowTileService shadow = org.robolectric.shadow.api.Shadow.extract(service);
        shadow.setLocked(true);
        service.onClick();
        assertOpensClean();
    }
}
