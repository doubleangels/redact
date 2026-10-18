package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.MainActivity;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.AppProcessingScope;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ReviewPromptTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    /** Keeps the SDK from initialising against the debug build's real DSN. */
    private static void overrideSentryDsn(String dsn) {
        try {
            java.lang.reflect.Field f = Class.forName("com.doubleangels.redact.sentry.SentryInitializer")
                    .getDeclaredField("testDsnOverride");
            f.setAccessible(true);
            f.set(null, dsn);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Before
    public void setUp() {
        overrideSentryDsn("");
        AppProcessingScope.resetForTests();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        prefs().edit().clear().putBoolean("initial_permissions_prompted", true).commit();
        ShadowDialog.reset();
        idle();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        overrideSentryDsn(null);
        AppProcessingScope.resetForTests();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
    }

    private Fragment clean() {
        return activity.getSupportFragmentManager().findFragmentByTag("clean");
    }

    /** Runs {@code n} successful jobs and returns the rating dialog left showing, if any. */
    private AlertDialog succeed(int n) {
        for (int i = 0; i < n; i++) {
            ReviewPrompt.onJobSucceeded(clean());
            idle();
        }
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        return d != null && d.isShowing() ? d : null;
    }

    /** The next activity started with ACTION_VIEW, skipping the first-launch permission requests. */
    private Intent nextViewIntent() {
        Intent intent;
        while ((intent = shadowOf(activity).getNextStartedActivity()) != null) {
            if (Intent.ACTION_VIEW.equals(intent.getAction())) {
                return intent;
            }
        }
        return null;
    }

    private void press(AlertDialog dialog, int which) {
        dialog.getButton(which).performClick();
        idle();
        ShadowDialog.reset();
    }

    @Test
    public void asksOnlyAfterThreeSuccessfulJobs_withJustYesAndNo() {
        assertNull(succeed(ReviewPrompt.ASK_AFTER - 1));
        AlertDialog dialog = succeed(1);
        assertNotNull(dialog);
        assertEquals(activity.getString(R.string.review_prompt_yes),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(activity.getString(R.string.review_prompt_no),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
    }

    @Test
    public void yes_opensThePlayListing_andNeverAsksAgain() {
        press(succeed(ReviewPrompt.ASK_AFTER), AlertDialog.BUTTON_POSITIVE);

        Intent opened = nextViewIntent();
        assertNotNull(opened);
        assertEquals("market://details?id=" + activity.getPackageName(), opened.getDataString());
        assertNull(succeed(50));
    }

    @Test
    public void no_neverAsksAgain() {
        press(succeed(ReviewPrompt.ASK_AFTER), AlertDialog.BUTTON_NEGATIVE);
        assertEquals(ReviewPrompt.STATE_DONE, prefs().getInt(ReviewPrompt.KEY_STATE, -1));
        assertNull(nextViewIntent());
        assertNull(succeed(50));
    }

    @Test
    public void theAsk_cannotBeDismissedWithoutAnAnswer_andCountsAsAskedOnceShown() {
        AlertDialog dialog = succeed(ReviewPrompt.ASK_AFTER);
        assertFalse(shadowOf(dialog).isCancelable());
        // Already final while open, so a rotation or process death cannot bring it back.
        assertFalse(ReviewPrompt.isDue(activity));
    }

    @Test
    public void hiddenScreen_countsTheJobButDoesNotAsk() {
        activity.selectTab(R.id.navigation_settings);
        idle();
        assertNull(succeed(ReviewPrompt.ASK_AFTER));
        assertTrue(ReviewPrompt.isDue(activity));
    }

    @Test
    public void resetAllSettings_keepsTheDecision() {
        press(succeed(ReviewPrompt.ASK_AFTER), AlertDialog.BUTTON_NEGATIVE);
        AppPreferences.resetAll(activity);
        assertEquals(ReviewPrompt.STATE_DONE, prefs().getInt(ReviewPrompt.KEY_STATE, -1));
        assertNull(succeed(50));
    }
}
