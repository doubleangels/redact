package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Collections;

@RunWith(RobolectricTestRunner.class)
public class StoreLinksTest {

    @Test
    public void playListingUrl_isBuiltFromThePackageOnly() {
        Application app = RuntimeEnvironment.getApplication();
        Uri url = Uri.parse(StoreLinks.playListingUrl(app));
        assertEquals("https", url.getScheme());
        assertEquals("play.google.com", url.getHost());
        assertEquals("/store/apps/details", url.getPath());
        assertEquals(Collections.singleton("id"), url.getQueryParameterNames());
        assertEquals(app.getPackageName(), url.getQueryParameter("id"));
    }

    @Test
    public void reviewPrompt_stillOpensTheSameListing() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ReviewPrompt.openStoreListing(activity);
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals("market://details?id=" + activity.getPackageName(), opened.getDataString());
    }
}
