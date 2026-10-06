package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Looper;

import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.ui.MainViewModel;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * Foldables, tablets and rotation: content keeps a readable width on wide windows, the empty states fit
 * short windows, and the app keeps its tab and selection when the window changes while it is open.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AdaptiveWindowTest {

    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        AppProcessingScope.resetForTests();
    }

    private static float dp(int dimenRes) {
        Context c = RuntimeEnvironment.getApplication();
        return c.getResources().getDimension(dimenRes) / c.getResources().getDisplayMetrics().density;
    }

    @Test
    public void sidePadding_growsWithWindowWidth_soContentStaysReadable() {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp");
        assertEquals(20f, dp(R.dimen.screen_edge_padding), 0.5f);   // phone
        RuntimeEnvironment.setQualifiers("w640dp-h800dp");
        assertEquals(48f, dp(R.dimen.screen_edge_padding), 0.5f);   // large phone landscape / small tablet
        RuntimeEnvironment.setQualifiers("w740dp-h900dp");
        assertEquals(100f, dp(R.dimen.screen_edge_padding), 0.5f);
        RuntimeEnvironment.setQualifiers("w880dp-h900dp");
        assertEquals(160f, dp(R.dimen.screen_edge_padding), 0.5f);  // unfolded foldable
        RuntimeEnvironment.setQualifiers("w1200dp-h800dp");
        assertEquals(280f, dp(R.dimen.screen_edge_padding), 0.5f);  // desktop-sized window
    }

    @Test
    public void contentWidth_neverExceedsAComfortableColumnOnWideWindows() {
        int[][] widths = {{640, 640 - 96}, {740, 740 - 200}, {880, 880 - 320}, {1200, 1200 - 560}};
        for (int[] w : widths) {
            RuntimeEnvironment.setQualifiers("w" + w[0] + "dp-h900dp");
            float content = w[0] - 2 * dp(R.dimen.screen_edge_padding);
            assertEquals(w[1], content, 1f);
            assertTrue("content " + content + "dp is too wide at " + w[0] + "dp", content <= 700f);
        }
    }

    @Test
    public void emptyStatePadding_isCompactOnShortWindows() {
        RuntimeEnvironment.setQualifiers("w800dp-h400dp");   // phone in landscape
        assertEquals(8f, dp(R.dimen.empty_state_vertical_padding), 0.5f);
        RuntimeEnvironment.setQualifiers("w360dp-h800dp");
        assertEquals(56f, dp(R.dimen.empty_state_vertical_padding), 0.5f);
    }

    @Test
    public void rotatingOrFolding_keepsTheTabAndTheSelection() {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-port");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity first = controller.get();
        MainViewModel vm = new ViewModelProvider(first).get(MainViewModel.class);
        List<MediaItem> items = Arrays.asList(
                new MediaItem(Uri.parse("file:///nonexistent/a.jpg"), false, "a.jpg"),
                new MediaItem(Uri.parse("file:///nonexistent/b.jpg"), false, "b.jpg"));
        vm.setSelectedItems(items);
        first.selectTab(R.id.navigation_settings);
        shadowOf(Looper.getMainLooper()).idle();

        // Unfold (wider, shorter) and rotate while the app is open.
        RuntimeEnvironment.setQualifiers("w900dp-h700dp-land");
        controller.configurationChange();
        shadowOf(Looper.getMainLooper()).idle();
        MainActivity second = controller.get();

        BottomNavigationView nav = second.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_settings, nav.getSelectedItemId());
        assertEquals(items, new ViewModelProvider(second).get(MainViewModel.class).getSelectedItems().getValue());

        // And back to the folded phone.
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-port");
        controller.configurationChange();
        shadowOf(Looper.getMainLooper()).idle();
        MainActivity third = controller.get();
        assertEquals(R.id.navigation_settings,
                ((BottomNavigationView) third.findViewById(R.id.bottomNavigation)).getSelectedItemId());
        assertEquals(items, new ViewModelProvider(third).get(MainViewModel.class).getSelectedItems().getValue());
    }

    @Test
    public void shareWindow_absorbsFoldDensityAndFontChangesInsteadOfRecreating() throws Exception {
        ActivityInfo info = RuntimeEnvironment.getApplication().getPackageManager().getActivityInfo(
                new ComponentName(RuntimeEnvironment.getApplication(), ShareHandlerActivity.class), 0);
        assertNotNull(info);
        int wanted = ActivityInfo.CONFIG_ORIENTATION | ActivityInfo.CONFIG_SCREEN_SIZE
                | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE | ActivityInfo.CONFIG_SCREEN_LAYOUT
                | ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_FONT_SCALE
                | ActivityInfo.CONFIG_LAYOUT_DIRECTION | ActivityInfo.CONFIG_UI_MODE;
        assertEquals(wanted, info.configChanges & wanted);
    }
}
