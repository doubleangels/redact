package com.doubleangels.redact;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class SettingsFragmentSmokeTest {

    @Rule
    public ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void settingsScreen_showsPermissionStatusLabels() {
        onView(withId(R.id.navigation_settings)).perform(click());
        onView(withId(R.id.textPermissionMediaStatus)).check(matches(isDisplayed()));
        onView(withId(R.id.textPermissionNotificationsStatus)).check(matches(isDisplayed()));
        onView(withText(R.string.settings_permissions_media)).check(matches(isDisplayed()));
    }
}
