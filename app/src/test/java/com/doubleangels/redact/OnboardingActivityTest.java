package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingActivityTest {

    private ActivityController<OnboardingActivity> controller;
    private Application app;

    /** Keeps the SDK from initialising against the debug build's real DSN. */
    private static void overrideSentryDsn(String dsn) {
        try {
            java.lang.reflect.Field f = com.doubleangels.redact.sentry.SentryInitializer.class
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
        app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
        com.doubleangels.redact.sentry.SentryInitializer.shutdown();
        overrideSentryDsn(null);
    }

    private OnboardingActivity launch(boolean replay) {
        Intent intent = new Intent(app, OnboardingActivity.class)
                .putExtra(OnboardingActivity.EXTRA_REPLAY, replay);
        controller = Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        settle();
        return controller.get();
    }

    /** Lets page animations and the short pause after a grant finish. */
    private static void settle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
    }

    private static void tap(Activity activity, int id) {
        activity.findViewById(id).performClick();
        settle();
    }

    private static String text(Activity activity, int id) {
        return ((Button) activity.findViewById(id)).getText().toString();
    }

    private static boolean visible(Activity activity, int id) {
        return activity.findViewById(id).getVisibility() == View.VISIBLE;
    }

    private static void walkTo(OnboardingActivity activity, OnboardingActivity.Step step) {
        while (activity.currentStep() != step) {
            tap(activity, R.id.onboardingPrimary);
        }
    }

    @Test
    public void introPages_leadToTheFirstChoice_andSkipJumpsThere() {
        OnboardingActivity activity = launch(false);
        assertEquals(OnboardingActivity.Step.WELCOME, activity.currentStep());
        assertEquals(activity.getString(R.string.onboarding_get_started), text(activity, R.id.onboardingPrimary));
        assertFalse(visible(activity, R.id.onboardingBack));
        assertTrue(visible(activity, R.id.onboardingSkip));

        tap(activity, R.id.onboardingPrimary);
        assertEquals(OnboardingActivity.Step.HIDDEN_DETAILS, activity.currentStep());
        assertTrue(visible(activity, R.id.onboardingBack));

        tap(activity, R.id.onboardingSkip);
        assertEquals(OnboardingActivity.Step.CHOICES, activity.currentStep());
        assertFalse(visible(activity, R.id.onboardingSkip));
    }

    @Test
    public void backOnTheFirstPage_cancelsOnFirstStart_butFinishesAReplay() {
        OnboardingActivity first = launch(false);
        first.getOnBackPressedDispatcher().onBackPressed();
        assertTrue(first.isFinishing());
        assertEquals(Activity.RESULT_CANCELED, shadowOf(first).getResultCode());
        controller.destroy();

        OnboardingActivity replay = launch(true);
        replay.getOnBackPressedDispatcher().onBackPressed();
        assertTrue(replay.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(replay).getResultCode());
    }

    @Test
    public void mediaPage_asksForAccess_whenAllowIsTapped() {
        OnboardingActivity activity = launch(false);
        walkTo(activity, OnboardingActivity.Step.MEDIA);
        assertEquals(activity.getString(R.string.onboarding_media_allow), text(activity, R.id.onboardingPrimary));
        assertEquals(activity.getString(R.string.onboarding_not_now), text(activity, R.id.onboardingSecondary));

        activity.findViewById(R.id.onboardingPrimary).performClick();

        assertNotNull(shadowOf(activity).getLastRequestedPermission());
        assertTrue(Arrays.asList(shadowOf(activity).getLastRequestedPermission().requestedPermissions)
                .contains(Manifest.permission.READ_MEDIA_IMAGES));
    }

    @Test
    public void notNow_movesOn_andLocationWaitsForPhotoAccess() {
        OnboardingActivity activity = launch(false);
        walkTo(activity, OnboardingActivity.Step.MEDIA);

        tap(activity, R.id.onboardingSecondary);

        assertEquals(OnboardingActivity.Step.LOCATION, activity.currentStep());
        assertEquals(activity.getString(R.string.onboarding_continue), text(activity, R.id.onboardingPrimary));
        assertFalse(visible(activity, R.id.onboardingSecondary));
        TextView status = currentPage(activity).findViewById(R.id.onboardingStatusText);
        assertEquals(activity.getString(R.string.onboarding_status_blocked), status.getText().toString());
    }

    @Test
    public void alreadyAllowedMedia_showsAllowed_andOffersContinue() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO);
        OnboardingActivity activity = launch(false);
        walkTo(activity, OnboardingActivity.Step.CHOICES);
        tap(activity, R.id.onboardingPrimary);

        assertEquals(OnboardingActivity.Step.MEDIA, activity.currentStep());
        assertEquals(activity.getString(R.string.onboarding_continue), text(activity, R.id.onboardingPrimary));
        TextView status = currentPage(activity).findViewById(R.id.onboardingStatusText);
        assertEquals(activity.getString(R.string.onboarding_status_granted), status.getText().toString());
    }

    @Test
    public void turningOnNotifications_alsoTurnsOnRedactsCleanAndConvertAlerts() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        OnboardingActivity activity = launch(false);
        walkTo(activity, OnboardingActivity.Step.NOTIFICATIONS);
        assertEquals(activity.getString(R.string.onboarding_notifications_allow),
                text(activity, R.id.onboardingPrimary));

        tap(activity, R.id.onboardingPrimary);

        assertTrue(AppPreferences.areNotificationsEnabled(activity));
        assertTrue(AppPreferences.areCleanNotificationsEnabled(activity));
        assertTrue(AppPreferences.areConvertNotificationsEnabled(activity));
        assertEquals(OnboardingActivity.Step.CRASH_REPORTS, activity.currentStep());
    }

    @Test
    public void crashReportsPage_turnsThemOn_andTheSummarySaysSo() {
        OnboardingActivity activity = launch(false);
        walkTo(activity, OnboardingActivity.Step.MEDIA);
        while (activity.currentStep() != OnboardingActivity.Step.CRASH_REPORTS) {
            tap(activity, visible(activity, R.id.onboardingSecondary)
                    ? R.id.onboardingSecondary : R.id.onboardingPrimary);
        }
        assertEquals(activity.getString(R.string.onboarding_crash_allow), text(activity, R.id.onboardingPrimary));
        assertEquals(activity.getString(R.string.onboarding_not_now), text(activity, R.id.onboardingSecondary));

        tap(activity, R.id.onboardingPrimary);

        assertTrue(AppPreferences.isCrashReportingEnabled(activity));
        assertEquals(OnboardingActivity.Step.DONE, activity.currentStep());
        ViewGroup summary = currentPage(activity).findViewById(R.id.onboardingPoints);
        TextView last = summary.getChildAt(summary.getChildCount() - 1).findViewById(R.id.onboardingPointText);
        assertEquals(activity.getString(R.string.onboarding_done_crash_on), last.getText().toString());
    }

    @Test
    public void notNowOnCrashReports_leavesThemOff() {
        OnboardingActivity activity = launch(false);
        skipToDone(activity);
        assertFalse(AppPreferences.isCrashReportingEnabled(activity));
    }

    @Test
    public void lastPage_startsTheAppOnFirstStart_andSaysDoneOnAReplay() {
        OnboardingActivity first = launch(false);
        skipToDone(first);
        assertEquals(first.getString(R.string.onboarding_start), text(first, R.id.onboardingPrimary));
        tap(first, R.id.onboardingPrimary);
        assertTrue(first.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(first).getResultCode());
        controller.destroy();

        OnboardingActivity replay = launch(true);
        skipToDone(replay);
        assertEquals(replay.getString(R.string.onboarding_done), text(replay, R.id.onboardingPrimary));
    }

    @Test
    public void declinedPermissions_areNotRequestedByTheIntroduction() {
        OnboardingActivity activity = launch(false);
        skipToDone(activity);
        assertNull(shadowOf(activity).getLastRequestedPermission());
        assertFalse(activity.wasAsked(OnboardingActivity.Step.MEDIA));
    }

    /** Taps Not Now (or Continue) on every permission page. */
    private static void skipToDone(OnboardingActivity activity) {
        walkTo(activity, OnboardingActivity.Step.MEDIA);
        while (activity.currentStep() != OnboardingActivity.Step.DONE) {
            int id = visible(activity, R.id.onboardingSecondary)
                    ? R.id.onboardingSecondary : R.id.onboardingPrimary;
            tap(activity, id);
        }
    }

    private static View currentPage(OnboardingActivity activity) {
        androidx.recyclerview.widget.RecyclerView recycler = (androidx.recyclerview.widget.RecyclerView)
                ((androidx.viewpager2.widget.ViewPager2) activity.findViewById(R.id.onboardingPager)).getChildAt(0);
        return recycler.findViewHolderForAdapterPosition(activity.currentStep().ordinal()).itemView;
    }
}
