package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.view.View;

import com.doubleangels.redact.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/** The Open button after a job: when it shows, and what it does with no viewer installed. */
@RunWith(RobolectricTestRunner.class)
public class OpenResultTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Notice.resetForTests();
    }

    @Test
    public void theButton_showsOnlyAfterAJobThatSavedOneFile() {
        View button = new View(app);
        Uri one = Uri.parse("content://media/external/images/media/1");
        Uri two = Uri.parse("content://media/external/images/media/2");

        OpenResult.updateButton(button, true, List.of(one));
        assertEquals(View.VISIBLE, button.getVisibility());
        OpenResult.updateButton(button, true, List.of(one, two));
        assertEquals(View.GONE, button.getVisibility());
        OpenResult.updateButton(button, false, List.of(one));
        assertEquals(View.GONE, button.getVisibility());
        OpenResult.updateButton(button, true, List.of());
        assertEquals(View.GONE, button.getVisibility());
    }

    @Test
    public void withNoViewerInstalled_itSaysSo() {
        android.content.Context noViewer = new android.content.ContextWrapper(app) {
            @Override
            public void startActivity(Intent intent) {
                throw new android.content.ActivityNotFoundException();
            }
        };

        OpenResult.open(noViewer, Uri.parse("content://media/external/images/media/1"));

        assertEquals(app.getString(R.string.open_result_no_app), Notice.lastShownForTests());
    }

    @Test
    public void nothingToOpen_doesNothing() {
        OpenResult.open(app, null);
        Intent started = shadowOf(app).getNextStartedActivity();
        assertNull(started);
        assertNull(Notice.lastShownForTests());
    }

    private static ResolveInfo activity(String pkg, String name) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = pkg;
        info.activityInfo.name = name;
        return info;
    }

    @Test
    public void aDefaultViewerThatIsNotRedact_opensDirectly() {
        ResolveInfo photos = activity("com.example.photos", "com.example.photos.Viewer");
        assertTrue(OpenResult.isOtherDefaultViewer(photos,
                List.of(activity("com.doubleangels.redact", "com.doubleangels.redact.MainActivity"), photos),
                "com.doubleangels.redact"));
    }

    @Test
    public void withNoDefault_androidNamesItsPicker_soTheListShows() {
        ResolveInfo picker = activity("android", "com.android.internal.app.ResolverActivity");
        assertFalse(OpenResult.isOtherDefaultViewer(picker,
                List.of(activity("com.example.photos", "com.example.photos.Viewer"),
                        activity("com.doubleangels.redact", "com.doubleangels.redact.MainActivity")),
                "com.doubleangels.redact"));
    }

    @Test
    public void withRedactAsTheDefault_theListShows_soOpenNeverLandsBackInRedact() {
        ResolveInfo redact = activity("com.doubleangels.redact", "com.doubleangels.redact.MainActivity");
        assertFalse(OpenResult.isOtherDefaultViewer(redact, List.of(redact), "com.doubleangels.redact"));
        assertFalse(OpenResult.isOtherDefaultViewer(null, List.of(), "com.doubleangels.redact"));
    }

    @Test
    public void withOnlyRedactAbleToView_theListLeavesRedactOut() {
        OpenResult.open(app, Uri.parse("content://media/external/images/media/1"));

        Intent started = shadowOf(app).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, started.getAction());
        Intent view = started.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_VIEW, view.getAction());
        assertEquals(Uri.parse("content://media/external/images/media/1"), view.getData());
        assertTrue((view.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        android.os.Parcelable[] excluded = started.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS);
        assertEquals(new android.content.ComponentName(app, "com.doubleangels.redact.MainActivity"), excluded[0]);
    }
}
