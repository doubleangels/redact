package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.net.Uri;
import android.os.Looper;
import android.view.View;

import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.MainActivity;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.media.MediaItem;
import com.google.android.material.snackbar.Snackbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NoticeTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        Notice.resetForTests();
        AppProcessingScope.resetForTests();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        AppProcessingScope.resetForTests();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private View root() {
        return activity.getWindow().getDecorView();
    }

    @Test
    public void withNoFloatingButton_itSitsAboveTheBottomNavigation() {
        activity.selectTab(R.id.navigation_settings);
        idle();
        assertEquals(activity.findViewById(R.id.bottomNavigation), Notice.anchorFor(root()));
    }

    @Test
    public void aboveTheFloatingButton_whenOneShows() {
        activity.selectTab(R.id.navigation_clean);
        new ViewModelProvider(activity).get(MainViewModel.class).setSelectedItems(
                List.of(new MediaItem(Uri.parse("file:///nonexistent/a.jpg"), false, "a.jpg")));
        idle();
        View clean = activity.findViewById(R.id.stripButton);
        assertEquals(View.VISIBLE, clean.getVisibility());
        assertEquals(clean, Notice.anchorFor(root()));
    }

    @Test
    public void aboveTheHighestOfAStack_ofFloatingButtons() {
        activity.selectTab(R.id.navigation_clean);
        new ViewModelProvider(activity).get(MainViewModel.class).setSelectedItems(
                List.of(new MediaItem(Uri.parse("file:///nonexistent/a.jpg"), false, "a.jpg")));
        idle();
        // Share shows above Clean after a batch; the message must clear both.
        View share = activity.findViewById(R.id.shareResultsButton);
        share.setVisibility(View.VISIBLE);
        idle();
        assertEquals(share, Notice.anchorFor(root()));
    }

    @Test
    public void onAScreen_itIsASnackbar() {
        activity.selectTab(R.id.navigation_settings);
        idle();
        Notice.show(activity, R.string.settings_reset_all_done);
        idle();
        assertEquals(activity.getString(R.string.settings_reset_all_done), Notice.lastShownForTests());
        assertNotNull(root().findViewById(com.google.android.material.R.id.snackbar_text));
        assertEquals(null, ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void withNoScreen_itFallsBackToAToast() {
        Notice.show(RuntimeEnvironment.getApplication(), R.string.settings_reset_all_done);
        assertEquals(activity.getString(R.string.settings_reset_all_done), ShadowToast.getTextOfLatestToast());
        assertEquals(activity.getString(R.string.settings_reset_all_done), Notice.lastShownForTests());
    }
}
