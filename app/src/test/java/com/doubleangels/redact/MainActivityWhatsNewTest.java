package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.whatsnew.WhatsNewActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** When MainActivity opens What's New by itself: once after an update, on a plain launch only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MainActivityWhatsNewTest {

    private Application app;
    private ActivityController<MainActivity> controller;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppProcessingScope.resetForTests();
        com.doubleangels.redact.ui.Notice.resetForTests();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
            controller = null;
        }
        AppProcessingScope.resetForTests();
    }

    /** An install updated from an older version: introduction and first prompts long done. */
    private void updatedInstall() {
        AppPreferences.setOnboardingSeen(app);
        AppPreferences.setInitialPermissionsPromptCompleted(app);
        AppPreferences.setWhatsNewSeenVersion(app, BuildConfig.VERSION_CODE - 1);
    }

    private Intent launcher() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(app, MainActivity.class);
    }

    private boolean startedWhatsNew(Intent intent) {
        controller = Robolectric.buildActivity(MainActivity.class, intent).setup();
        boolean found = false;
        Intent started;
        while ((started = shadowOf(controller.get()).getNextStartedActivity()) != null) {
            if (started.getComponent() != null
                    && WhatsNewActivity.class.getName().equals(started.getComponent().getClassName())) {
                found = true;
            }
        }
        controller.pause().stop().destroy();
        controller = null;
        return found;
    }

    @Test
    public void afterAnUpdate_aPlainLaunchOpensIt() {
        updatedInstall();
        assertTrue(startedWhatsNew(launcher()));
    }

    @Test
    public void onceSeen_itDoesNotOpenAgain() {
        updatedInstall();
        AppPreferences.setWhatsNewSeenVersion(app, BuildConfig.VERSION_CODE);
        assertFalse(startedWhatsNew(launcher()));
    }

    @Test
    public void aFreshInstall_neverSeesIt() {
        assertFalse(startedWhatsNew(launcher()));
    }

    @Test
    public void tilesAndShortcuts_neverOpenIt() {
        updatedInstall();
        assertFalse(startedWhatsNew(launcher().putExtra(MainActivity.EXTRA_TAB, "scan")));
        assertFalse(startedWhatsNew(new Intent(Intent.ACTION_MAIN).setClass(app, MainActivity.class)
                .putExtra(MainActivity.EXTRA_TAB, "clean")));
    }

    @Test
    public void sharesThroughAnAlias_neverOpenIt() {
        updatedInstall();
        Intent share = new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                .setComponent(new ComponentName(app, app.getPackageName() + ".ScanShareTarget"));
        assertFalse(MainActivity.isPlainLaunch(share));
        Intent alias = launcher().setComponent(
                new ComponentName(app, app.getPackageName() + ".ConvertShareTarget"));
        assertFalse(MainActivity.isPlainLaunch(alias));
        assertTrue(MainActivity.isPlainLaunch(launcher()));
        assertFalse(MainActivity.isPlainLaunch(null));
    }

    @Test
    public void aRotation_doesNotOpenItAgain() {
        updatedInstall();
        controller = Robolectric.buildActivity(MainActivity.class, launcher()).setup(new Bundle());
        Intent started;
        while ((started = shadowOf(controller.get()).getNextStartedActivity()) != null) {
            assertFalse(started.getComponent() != null && WhatsNewActivity.class.getName()
                    .equals(started.getComponent().getClassName()));
        }
    }

    @Test
    public void skipIntro_marksTheNotesSeen() {
        Intent intent = launcher().putExtra(MainActivity.EXTRA_SKIP_INTRO, true);
        assertFalse(startedWhatsNew(intent));
        assertEquals(BuildConfig.VERSION_CODE, AppPreferences.getWhatsNewSeenVersion(app));
    }

    @Test
    public void showWhatsNewExtra_opensItInDebugBuilds() {
        updatedInstall();
        AppPreferences.setWhatsNewSeenVersion(app, BuildConfig.VERSION_CODE);
        Intent intent = new Intent(app, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SKIP_INTRO, true)
                .putExtra(MainActivity.EXTRA_SHOW_WHATS_NEW, true);
        assertEquals(BuildConfig.DEBUG, startedWhatsNew(intent));
    }
}
