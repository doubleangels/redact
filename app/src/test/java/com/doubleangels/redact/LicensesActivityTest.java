package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.doubleangels.redact.ui.Notice;
import com.doubleangels.redact.ui.OpenSourceLicenses;
import com.google.android.material.appbar.MaterialToolbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

/** Settings › Open-Source Licenses lists every library and shows its license text offline. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class LicensesActivityTest {

    private ActivityController<LicensesActivity> controller;
    private LicensesActivity activity;

    @Before
    public void setUp() {
        Notice.resetForTests();
        controller = Robolectric.buildActivity(LicensesActivity.class).setup();
        activity = controller.get();
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
    }

    private ViewGroup list() {
        return activity.findViewById(R.id.licensesList);
    }

    private AlertDialog openFirstLicense() {
        list().getChildAt(0).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    @Test
    public void listsOneRowPerLibrary_withItsName_andLicense() {
        assertEquals(OpenSourceLicenses.ENTRIES.size(), list().getChildCount());
        OpenSourceLicenses.Entry first = OpenSourceLicenses.ENTRIES.get(0);
        View row = list().getChildAt(0);
        assertEquals(first.name(), ((TextView) row.findViewById(R.id.licenseName)).getText().toString());
        assertEquals(first.licenseName(), ((TextView) row.findViewById(R.id.licenseType)).getText().toString());
    }

    @Test
    public void tappingARow_showsTheBundledLicenseText() {
        AlertDialog dialog = openFirstLicense();

        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        TextView text = dialog.findViewById(R.id.licenseText);
        assertNotNull(text);
        assertTrue(text.getText().toString().contains("Apache License"));
    }

    @Test
    public void projectPage_opensTheLibrarysUrl() {
        openFirstLicense().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_VIEW, opened.getAction());
        assertEquals(OpenSourceLicenses.ENTRIES.get(0).url(), String.valueOf(opened.getData()));
    }

    @Test
    public void projectPage_withoutABrowser_saysTheLinkIsUnavailable() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);

        openFirstLicense().getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(activity.getString(R.string.settings_link_unavailable), Notice.lastShownForTests());
    }

    @Test
    public void readLicense_returnsTheWholeFile() {
        String text = activity.readLicense(R.raw.license_apache_2_0);
        assertTrue(text.contains("Apache License"));
        // The file is longer than one 8 KB read, so this proves the read loop keeps going.
        assertTrue(text.trim().endsWith("END OF TERMS AND CONDITIONS"));
    }

    @Test
    public void toolbarBack_closesTheScreen() {
        MaterialToolbar toolbar = activity.findViewById(R.id.licensesToolbar);
        View up = null;
        for (int i = 0; i < toolbar.getChildCount(); i++) {
            if (toolbar.getChildAt(i).getContentDescription() != null) {
                up = toolbar.getChildAt(i);
            }
        }
        assertNotNull(up);
        up.performClick();

        assertTrue(activity.isFinishing());
    }

    @Test
    public void systemBars_padTheScreen_andTheListBottom() {
        View root = activity.findViewById(R.id.licensesRoot);
        View content = activity.findViewById(R.id.licensesContent);
        int contentBottom = content.getPaddingBottom();
        WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(4, 40, 6, 30))
                .build();

        ViewCompat.dispatchApplyWindowInsets(root, insets);

        assertEquals(40, root.getPaddingTop());
        assertEquals(4, root.getPaddingLeft());
        assertEquals(0, root.getPaddingBottom());
        assertEquals(contentBottom + 30, content.getPaddingBottom());
    }
}
