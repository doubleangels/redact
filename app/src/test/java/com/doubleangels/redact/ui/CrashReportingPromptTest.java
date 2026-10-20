package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.MainActivity;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.sentry.SentryInitializer;

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
public class CrashReportingPromptTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    /** Keeps the SDK from initialising against the debug build's real DSN. */
    private static void overrideSentryDsn(String dsn) {
        try {
            java.lang.reflect.Field f = SentryInitializer.class.getDeclaredField("testDsnOverride");
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
        SentryInitializer.shutdown();
        overrideSentryDsn(null);
        AppProcessingScope.resetForTests();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Runs the end of the first-launch permission prompts and returns the question, if shown. */
    private AlertDialog firstStart() {
        CrashReportingPrompt.onFirstStartPermissionsDone(activity);
        idle();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        return d != null && d.isShowing() ? d : null;
    }

    private void press(AlertDialog dialog, int which) {
        dialog.getButton(which).performClick();
        idle();
        ShadowDialog.reset();
    }

    @Test
    public void firstStart_asksWithEnableAndNotNow_andCannotBeDismissedWithoutAnAnswer() {
        AlertDialog dialog = firstStart();
        assertNotNull(dialog);
        // The same dialog the Send Crash Reports switch shows.
        android.widget.TextView title = dialog.findViewById(androidx.appcompat.R.id.alertTitle);
        assertEquals(activity.getString(R.string.settings_sentry_consent_title), title.getText().toString());
        assertEquals(activity.getString(R.string.settings_sentry_consent_allow),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(activity.getString(R.string.settings_sentry_consent_cancel),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getVisibility());
        assertFalse(shadowOf(dialog).isCancelable());
        // Already final while open, so a rotation or process death cannot bring it back.
        assertTrue(CrashReportingPrompt.wasAsked(activity));
    }

    @Test
    public void enable_turnsOnCrashReports_andNeverAsksAgain() {
        press(firstStart(), AlertDialog.BUTTON_POSITIVE);
        assertTrue(AppPreferences.isCrashReportingEnabled(activity));

        AppPreferences.setCrashReportingEnabled(activity, false);
        assertNull(firstStart());
    }

    @Test
    public void notNow_leavesCrashReportsOff_saysWhereTheSettingIs_andNeverAsksAgain() {
        press(firstStart(), AlertDialog.BUTTON_NEGATIVE);
        assertFalse(AppPreferences.isCrashReportingEnabled(activity));
        assertTrue(CrashReportingPrompt.wasAsked(activity));
        assertNull(firstStart());
    }

    @Test
    public void alreadyOn_isNeverAsked() {
        AppPreferences.setCrashReportingEnabled(activity, true);
        assertNull(firstStart());
    }

    @Test
    public void resetAllSettings_keepsTheQuestionAnswered() {
        press(firstStart(), AlertDialog.BUTTON_NEGATIVE);
        AppPreferences.resetAll(activity);
        assertTrue(CrashReportingPrompt.wasAsked(activity));
        assertNull(firstStart());
    }
}
