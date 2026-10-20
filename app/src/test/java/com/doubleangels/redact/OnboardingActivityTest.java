package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.Button;

import androidx.viewpager2.widget.ViewPager2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OnboardingActivityTest {

    private ActivityController<OnboardingActivity> controller;

    @Before
    public void setUp() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(AppPreferences.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.pause().stop().destroy();
        }
    }

    private OnboardingActivity launch(boolean replay) {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), OnboardingActivity.class)
                .putExtra(OnboardingActivity.EXTRA_REPLAY, replay);
        controller = Robolectric.buildActivity(OnboardingActivity.class, intent).setup();
        shadowOf(Looper.getMainLooper()).idle();
        return controller.get();
    }

    private static void tap(Activity activity, int id) {
        activity.findViewById(id).performClick();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static int page(Activity activity) {
        return ((ViewPager2) activity.findViewById(R.id.onboardingPager)).getCurrentItem();
    }

    private static CharSequence nextLabel(Activity activity) {
        return ((Button) activity.findViewById(R.id.onboardingNext)).getText();
    }

    @Test
    public void next_walksThePages_thenGetStartedFinishesWithOk() {
        OnboardingActivity activity = launch(false);
        assertEquals(0, page(activity));
        assertEquals(activity.getString(R.string.onboarding_next), nextLabel(activity));

        tap(activity, R.id.onboardingNext);
        assertEquals(1, page(activity));
        tap(activity, R.id.onboardingNext);
        assertEquals(2, page(activity));
        assertEquals(activity.getString(R.string.onboarding_get_started), nextLabel(activity));

        tap(activity, R.id.onboardingNext);
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
    }

    @Test
    public void skip_finishesFromAnyPage() {
        OnboardingActivity activity = launch(false);
        tap(activity, R.id.onboardingSkip);
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
    }

    @Test
    public void back_goesToThePreviousPage_andFinishesOnTheFirst() {
        OnboardingActivity activity = launch(false);
        tap(activity, R.id.onboardingNext);
        assertEquals(1, page(activity));

        activity.getOnBackPressedDispatcher().onBackPressed();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, page(activity));
        assertFalse(activity.isFinishing());

        activity.getOnBackPressedDispatcher().onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test
    public void replay_endsWithDone() {
        OnboardingActivity activity = launch(true);
        tap(activity, R.id.onboardingNext);
        tap(activity, R.id.onboardingNext);
        assertEquals(activity.getString(R.string.onboarding_done), nextLabel(activity));

        tap(activity, R.id.onboardingNext);
        assertTrue(activity.isFinishing());
        assertEquals(Activity.RESULT_OK, shadowOf(activity).getResultCode());
    }

    @Test
    public void notificationsPoint_onlyWhereAndroidAsksForIt() {
        assertEquals(3, OnboardingActivity.pages(false, true).get(2).points().size());
        assertEquals(2, OnboardingActivity.pages(false, false).get(2).points().size());
        assertEquals(R.string.onboarding_permissions_body_replay,
                OnboardingActivity.pages(true, true).get(2).body());
    }
}
