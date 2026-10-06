package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.WindowManager;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.ui.SecureWindow;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** "Hide Content in Screenshots and Recents" sets FLAG_SECURE on the app's windows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class SecureWindowTest {

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

    private static boolean isSecure(MainActivity activity) {
        return (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    @Test
    public void isOffByDefault() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();

        assertEquals(false, AppPreferences.isSecureWindow(RuntimeEnvironment.getApplication()));
        assertEquals(false, isSecure(controller.get()));
    }

    @Test
    public void whenOn_theWindowIsSecureFromCreation() {
        AppPreferences.setSecureWindow(RuntimeEnvironment.getApplication(), true);

        controller = Robolectric.buildActivity(MainActivity.class).setup();

        assertTrue(isSecure(controller.get()));
    }

    @Test
    public void apply_followsThePreferenceOnALiveWindow() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();

        AppPreferences.setSecureWindow(activity, true);
        SecureWindow.apply(activity);
        assertTrue(isSecure(activity));

        AppPreferences.setSecureWindow(activity, false);
        SecureWindow.apply(activity);
        assertEquals(false, isSecure(activity));
    }
}
