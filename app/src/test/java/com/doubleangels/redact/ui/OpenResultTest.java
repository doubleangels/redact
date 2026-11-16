package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
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
}
