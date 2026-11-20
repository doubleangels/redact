package com.doubleangels.redact.whatsnew;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.BuildConfig;
import com.doubleangels.redact.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class WhatsNewActivityTest {

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.resetAll(app);
        AppPreferences.setWhatsNewSeenVersion(app, 0);
    }

    private ActivityController<WhatsNewActivity> open(Intent intent) {
        return Robolectric.buildActivity(WhatsNewActivity.class, intent).setup();
    }

    private static List<String> texts(ViewGroup group) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView t) {
                out.add(t.getText().toString());
            } else if (child instanceof ViewGroup g) {
                out.addAll(texts(g));
            }
        }
        return out;
    }

    @Test
    public void showsTheVersion_andEachItemUnderItsHeader_hidingEmptySections() {
        WhatsNewActivity activity = open(WhatsNewActivity.fromSettings(app)).get();
        assertFalse(activity.isFinishing());
        TextView version = activity.findViewById(R.id.whatsNewVersion);
        assertEquals(app.getString(R.string.whats_new_version, BuildConfig.VERSION_NAME),
                version.getText().toString());

        List<WhatsNew.Item> items = WhatsNew.load(app);
        List<String> shown = texts(activity.findViewById(R.id.whatsNewItems));
        for (WhatsNew.Item item : items) {
            assertTrue(shown.contains(item.title()));
            assertTrue(shown.contains(item.body()));
        }
        String[][] sections = {
                {WhatsNew.KIND_NEW, app.getString(R.string.whats_new_section_new)},
                {WhatsNew.KIND_IMPROVED, app.getString(R.string.whats_new_section_improved)},
                {WhatsNew.KIND_FIXED, app.getString(R.string.whats_new_section_fixed)}};
        for (String[] section : sections) {
            assertEquals(section[1], !WhatsNew.ofKind(items, section[0]).isEmpty(),
                    shown.contains(section[1]));
        }
    }

    @Test
    public void opening_storesThisVersionAsSeen() {
        open(new Intent(app, WhatsNewActivity.class));
        assertEquals(BuildConfig.VERSION_CODE, AppPreferences.getWhatsNewSeenVersion(app));
    }

    @Test
    public void gotIt_andBack_closeIt() {
        WhatsNewActivity activity = open(new Intent(app, WhatsNewActivity.class)).get();
        activity.findViewById(R.id.whatsNewDone).performClick();
        assertTrue(activity.isFinishing());

        WhatsNewActivity again = open(new Intent(app, WhatsNewActivity.class)).get();
        again.getOnBackPressedDispatcher().onBackPressed();
        assertTrue(again.isFinishing());
    }

    @Test
    public void icons_haveAGenericFallback() {
        for (String key : WhatsNewIcons.keys()) {
            assertNotNull(app.getDrawable(WhatsNewIcons.forKey(key)));
        }
        assertEquals(R.drawable.ic_update, WhatsNewIcons.forKey("something_new"));
    }

    @Test
    public void ofKind_keepsTheOrderWritten() {
        List<WhatsNew.Item> items = List.of(
                new WhatsNew.Item("fixed", "fixes", "a", "x"),
                new WhatsNew.Item("new", "clean", "b", "y"),
                new WhatsNew.Item("new", "scan", "c", "z"));
        List<WhatsNew.Item> fresh = WhatsNew.ofKind(items, WhatsNew.KIND_NEW);
        assertEquals(2, fresh.size());
        assertEquals("b", fresh.get(0).title());
        assertEquals("c", fresh.get(1).title());
    }
}
