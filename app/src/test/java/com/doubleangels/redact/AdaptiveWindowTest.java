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
import com.google.android.material.navigation.NavigationBarView;
import com.google.android.material.navigationrail.NavigationRailView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

import java.util.Arrays;
import java.util.List;

/**
 * Foldables, tablets and rotation: content keeps a readable width on wide windows, the empty states fit
 * short windows, and the app keeps its tab and selection when the window changes while it is open.
 */
@RunWith(RobolectricTestRunner.class)
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
    public void tabs_moveToASideRail_onWideWindowsAndStayABottomBarOnPhones() {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        assertTrue(controller.get().findViewById(R.id.bottomNavigation) instanceof BottomNavigationView);
        controller.pause().stop().destroy();

        for (String size : new String[] {"w600dp-h900dp", "w840dp-h700dp", "w1280dp-h800dp", "w800dp-h360dp"}) {
            RuntimeEnvironment.setQualifiers(size);
            controller = Robolectric.buildActivity(MainActivity.class).setup();
            assertTrue(size, controller.get().findViewById(R.id.bottomNavigation) instanceof NavigationRailView);
            controller.pause().stop().destroy();
            controller = null;
        }
    }

    @Test
    public void landscapePhone_dropsTheRailLabelsSoAllFiveTabsFit() {
        RuntimeEnvironment.setQualifiers("w800dp-h360dp");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        NavigationBarView nav = controller.get().findViewById(R.id.bottomNavigation);
        assertEquals(NavigationBarView.LABEL_VISIBILITY_UNLABELED, nav.getLabelVisibilityMode());
        controller.pause().stop().destroy();

        RuntimeEnvironment.setQualifiers("w1280dp-h800dp");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        nav = controller.get().findViewById(R.id.bottomNavigation);
        assertEquals(NavigationBarView.LABEL_VISIBILITY_LABELED, nav.getLabelVisibilityMode());
    }

    @Test
    public void selectedFilesGrid_getsMoreColumnsOnWiderWindows() {
        int[][] expected = {{360, 3}, {640, 4}, {880, 4}, {1200, 5}};
        for (int[] e : expected) {
            RuntimeEnvironment.setQualifiers("w" + e[0] + "dp-h900dp");
            assertEquals("w" + e[0], e[1],
                    RuntimeEnvironment.getApplication().getResources().getInteger(R.integer.selected_grid_columns));
        }
    }

    @Test
    public void actionButtons_lineUpWithTheContentColumnOnWideWindows() {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp");
        assertEquals(16f, dp(R.dimen.fab_margin_end), 0.5f);
        RuntimeEnvironment.setQualifiers("w1200dp-h800dp");
        assertEquals(dp(R.dimen.screen_edge_padding), dp(R.dimen.fab_margin_end), 0.5f);
    }

    @Test
    public void foldedLikeABook_theAppMovesToTheWiderHalfAndTheTabsFitIt() {
        RuntimeEnvironment.setQualifiers("w840dp-h700dp");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        activity.selectTab(R.id.navigation_clean);
        shadowOf(Looper.getMainLooper()).idle();
        android.view.View column = activity.findViewById(R.id.cleanScrollView);
        int flatPadding = column.getPaddingLeft();
        assertEquals(160f, flatPadding / activity.getResources().getDisplayMetrics().density, 0.5f);

        // Left half 500 wide, right half 332 wide: the app takes the left one.
        activity.onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.book(
                new android.graphics.Rect(500, 0, 508, 700), 840, 700));
        shadowOf(Looper.getMainLooper()).idle();
        // The root is padded, not just the content, so snackbars stay in the half too.
        android.view.View content = activity.findViewById(R.id.root_layout);
        assertEquals(0, content.getPaddingLeft());
        assertEquals(340, content.getPaddingRight());
        assertEquals(0, content.getPaddingBottom());
        assertEquals(com.doubleangels.redact.ui.FoldPosture.Type.BOOK,
                new ViewModelProvider(activity).get(MainViewModel.class).getFoldPosture().getValue().type());
        // The tab's side padding is sized for the half, not for the 840dp window.
        assertTrue("padding " + column.getPaddingLeft() + " should shrink from " + flatPadding,
                column.getPaddingLeft() < flatPadding);
        // And the grid gets a phone's columns rather than the 840dp window's.
        androidx.recyclerview.widget.GridLayoutManager grid = (androidx.recyclerview.widget.GridLayoutManager)
                ((androidx.recyclerview.widget.RecyclerView) activity.findViewById(R.id.selectedItemsGrid))
                        .getLayoutManager();
        assertEquals(activity.getResources().getInteger(R.integer.selected_grid_columns_folded), grid.getSpanCount());

        // The action button follows the column's end edge instead of the window-sized margin.
        android.view.View button = activity.findViewById(R.id.stripButton);
        int bookMargin = ((android.view.ViewGroup.MarginLayoutParams) button.getLayoutParams()).getMarginEnd();
        assertEquals(column.getPaddingRight(), bookMargin);

        // Unfolded again: everything returns.
        activity.onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.NONE);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, content.getPaddingRight());
        assertEquals(flatPadding, column.getPaddingLeft());
        assertTrue(((android.view.ViewGroup.MarginLayoutParams) button.getLayoutParams()).getMarginEnd() > bookMargin);
        assertEquals(activity.getResources().getInteger(R.integer.selected_grid_columns), grid.getSpanCount());
    }

    @Test
    public void recreatedWhileFolded_aFlatReportStillUnfoldsTheTabs() {
        RuntimeEnvironment.setQualifiers("w840dp-h700dp");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        controller.get().onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.book(
                new android.graphics.Rect(500, 0, 508, 700), 840, 700));
        // A rotation or theme change recreates the activity, which starts out flat, while the
        // view model keeps the fold; the first report after that may well be flat.
        controller.recreate();
        controller.get().onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.NONE);
        assertEquals(com.doubleangels.redact.ui.FoldPosture.NONE, new ViewModelProvider(controller.get())
                .get(MainViewModel.class).getFoldPosture().getValue());
    }

    @Test
    public void dialogs_stayOffTheHinge() {
        RuntimeEnvironment.setQualifiers("w840dp-h700dp");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        activity.onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.book(
                new android.graphics.Rect(500, 0, 508, 700), 840, 700));
        androidx.appcompat.app.AlertDialog dialog = com.doubleangels.redact.ui.FoldDialogs.place(
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setMessage("m").show());
        android.view.WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
        // In the left half and as wide as it, not centred on the window.
        assertEquals(android.view.Gravity.LEFT, params.gravity & android.view.Gravity.HORIZONTAL_GRAVITY_MASK);
        assertEquals(activity.getWindow().getDecorView().getWidth() - 340, params.width);
        dialog.dismiss();

        activity.onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.tabletop(
                new android.graphics.Rect(0, 340, 840, 348), 840, 700));
        dialog = com.doubleangels.redact.ui.FoldDialogs.place(
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setMessage("m").show());
        assertEquals(android.view.Gravity.TOP, dialog.getWindow().getAttributes().gravity
                & android.view.Gravity.VERTICAL_GRAVITY_MASK);
        dialog.dismiss();

        // Flat: left as Material places it.
        activity.onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.NONE);
        dialog = com.doubleangels.redact.ui.FoldDialogs.place(
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                        .setMessage("m").show());
        assertTrue((dialog.getWindow().getAttributes().gravity & android.view.Gravity.TOP) != android.view.Gravity.TOP);
        dialog.dismiss();
    }

    @Test
    public void foldedOnATable_theAppStaysAboveTheHinge() {
        RuntimeEnvironment.setQualifiers("w840dp-h700dp");
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        NavigationRailView nav = activity.findViewById(R.id.bottomNavigation);
        int flatIndicator = nav.getItemActiveIndicatorHeight();
        int flatItemHeight = nav.getCollapsedItemMinimumHeight();
        activity.onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.tabletop(
                new android.graphics.Rect(0, 340, 840, 348), 840, 700));
        shadowOf(Looper.getMainLooper()).idle();
        android.view.View content = activity.findViewById(R.id.root_layout);
        assertEquals(360, content.getPaddingBottom());
        assertEquals(0, content.getPaddingLeft());
        assertEquals(0, content.getPaddingRight());
        // The top half is too short for five labelled tabs, so the rail drops the labels like a
        // landscape phone does, and gets them back when the screen is flat again.
        assertEquals(NavigationBarView.LABEL_VISIBILITY_UNLABELED, nav.getLabelVisibilityMode());
        // Five shorter tabs leave room to centre them in the half instead of filling it.
        float density = activity.getResources().getDisplayMetrics().density;
        assertEquals(48f, nav.getCollapsedItemMinimumHeight() / density, 0.5f);
        assertEquals(40f, nav.getItemActiveIndicatorHeight() / density, 0.5f);
        activity.onFoldPostureChanged(com.doubleangels.redact.ui.FoldPosture.NONE);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(NavigationBarView.LABEL_VISIBILITY_LABELED, nav.getLabelVisibilityMode());
        assertEquals(flatIndicator, nav.getItemActiveIndicatorHeight());
        assertEquals(flatItemHeight, nav.getCollapsedItemMinimumHeight());
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

        NavigationBarView nav = second.findViewById(R.id.bottomNavigation);
        assertEquals(R.id.navigation_settings, nav.getSelectedItemId());
        assertEquals(items, new ViewModelProvider(second).get(MainViewModel.class).getSelectedItems().getValue());

        // And back to the folded phone.
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-port");
        controller.configurationChange();
        shadowOf(Looper.getMainLooper()).idle();
        MainActivity third = controller.get();
        assertEquals(R.id.navigation_settings,
                ((NavigationBarView) third.findViewById(R.id.bottomNavigation)).getSelectedItemId());
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
