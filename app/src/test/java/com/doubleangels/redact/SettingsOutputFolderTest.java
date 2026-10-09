package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.HashSet;
import java.util.Set;

/** Choosing, replacing and resetting output folders, and the folder grants that go with them. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class SettingsOutputFolderTest {

    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeDocumentsProvider.install(app);
        AppPreferences.resetOutputTree(app, false);
        AppPreferences.resetOutputTree(app, true);
    }

    private Set<Uri> grants() {
        Set<Uri> uris = new HashSet<>();
        for (UriPermission permission : app.getContentResolver().getPersistedUriPermissions()) {
            uris.add(permission.getUri());
        }
        return uris;
    }

    @Test
    public void pickingAFolder_savesIt_andKeepsAccess() {
        Uri tree = FakeDocumentsProvider.folder("primary:Pictures/Private");

        SettingsFragment.onOutputFolderPicked(app, false, tree);

        assertEquals(tree, AppPreferences.getOutputTree(app, false));
        assertNull(AppPreferences.getOutputTree(app, true));
        assertEquals(Set.of(tree), grants());
    }

    @Test
    public void replacingAFolder_releasesTheOldOne_unlessTheOtherTypeStillUsesIt() {
        Uri shared = FakeDocumentsProvider.folder("primary:Pictures/Shared");
        Uri images = FakeDocumentsProvider.folder("primary:Pictures/Images");
        Uri later = FakeDocumentsProvider.folder("primary:Pictures/Later");

        SettingsFragment.onOutputFolderPicked(app, false, shared);
        SettingsFragment.onOutputFolderPicked(app, true, shared);
        SettingsFragment.onOutputFolderPicked(app, false, images);
        // Videos still save to the shared folder, so its grant stays.
        assertEquals(Set.of(shared, images), grants());

        SettingsFragment.onOutputFolderPicked(app, false, later);
        assertEquals(Set.of(shared, later), grants());
    }

    @Test
    public void pickingTheDefaultFolder_resetsToTheDefault() {
        Uri custom = FakeDocumentsProvider.folder("primary:Pictures/Private");
        SettingsFragment.onOutputFolderPicked(app, false, custom);

        SettingsFragment.onOutputFolderPicked(app, false,
                FakeDocumentsProvider.folder("primary:Pictures/Redact"));

        assertNull(AppPreferences.getOutputTree(app, false));
        assertTrue(grants().isEmpty());
    }

    @Test
    public void aFolderThatRefusesNewFiles_isNotSaved() {
        Uri before = FakeDocumentsProvider.folder("primary:Pictures/Private");
        SettingsFragment.onOutputFolderPicked(app, false, before);
        FakeDocumentsProvider.readOnly = true;

        SettingsFragment.onOutputFolderPicked(app, false,
                FakeDocumentsProvider.folder("primary:Pictures/ReadOnly"));

        assertEquals(before, AppPreferences.getOutputTree(app, false));
        assertEquals(Set.of(before), grants());
        assertEquals(app.getString(R.string.settings_output_error), ShadowToast.getTextOfLatestToast());
    }
}
