package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeDocumentsProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/** Output folder handling when providers are missing, URIs are not what they seem, or IDs are odd. */
@RunWith(RobolectricTestRunner.class)
public class OutputDestinationEdgeCasesTest {

    private static final Uri NOBODY = Uri.parse("content://com.example.nobody/files/1");

    private Application app;
    private ContentResolver resolver;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        resolver = app.getContentResolver();
        FakeDocumentsProvider.install(app);
        AppPreferences.resetOutputTree(app, false);
        AppPreferences.resetOutputTree(app, true);
    }

    @After
    public void tearDown() {
        AppPreferences.resetOutputTree(app, false);
        AppPreferences.resetOutputTree(app, true);
    }

    @Test
    public void discard_nothingOrAMissingProvider_isQuiet() {
        OutputDestination.discard(resolver, null);
        OutputDestination.discard(resolver, NOBODY);
    }

    @Test
    public void displayName_withAMissingProvider_isNull() {
        assertNull(OutputDestination.displayName(resolver, NOBODY));
    }

    @Test
    public void rename_aDocumentTheProviderCannotRename_givesNull() {
        Uri document = DocumentsContract.buildDocumentUri("com.example.nobody", "primary:Pictures/a.jpg");
        assertNull(OutputDestination.rename(resolver, document, "b.jpg"));
    }

    @Test
    public void isDefaultFolder_otherProvidersAndNonTreeUris_areNot() {
        assertFalse(OutputDestination.isDefaultFolder(
                DocumentsContract.buildTreeDocumentUri("com.example.other", "primary:Pictures/Redact"), false));
        assertFalse(OutputDestination.isDefaultFolder(
                DocumentsContract.buildDocumentUri(FakeDocumentsProvider.AUTHORITY, "primary:Pictures/Redact"), false));
    }

    @Test
    public void isInChosenFolder_skipsUrisAndTreesItCannotMatch() {
        assertFalse(OutputDestination.isInChosenFolder(app, Uri.parse("content://media/external/images/media/1")));

        AppPreferences.setOutputTree(app, false,
                DocumentsContract.buildTreeDocumentUri("com.example.other", "primary:Pictures/Private"));
        AppPreferences.setOutputTree(app, true, Uri.parse("content://" + FakeDocumentsProvider.AUTHORITY + "/notatree"));
        assertFalse(OutputDestination.isInChosenFolder(app, DocumentsContract.buildDocumentUri(
                FakeDocumentsProvider.AUTHORITY, "primary:Pictures/Private/a.jpg")));
        assertTrue(OutputDestination.chosenRelativePaths(app).isEmpty()
                || !OutputDestination.chosenRelativePaths(app).contains("Pictures/Private"));
    }

    @Test
    public void isUsable_withoutAGrant_isFalse() {
        Uri tree = FakeDocumentsProvider.folder("primary:Pictures/Private");
        assertFalse(OutputDestination.isUsable(app, tree));
        resolver.takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertTrue(OutputDestination.isUsable(app, tree));
        assertFalse(OutputDestination.isUsable(app, FakeDocumentsProvider.tree("primary:Pictures/Other")));
    }

    @Test
    public void acceptsNewFiles_aMissingProvider_isFalse() {
        assertFalse(OutputDestination.acceptsNewFiles(app,
                DocumentsContract.buildTreeDocumentUri("com.example.nobody", "primary:Pictures")));
    }

    @Test
    public void describe_oddTreesStillGetALabel() {
        Uri notATree = Uri.parse("content://" + FakeDocumentsProvider.AUTHORITY + "/document/primary%3AX");
        assertEquals(notATree.toString(), OutputDestination.describe(app, notATree));

        Uri rawElsewhere = DocumentsContract.buildTreeDocumentUri(
                "com.android.providers.downloads.documents", "raw:/mnt/usb/Exports");
        assertEquals("/mnt/usb/Exports", OutputDestination.describe(app, rawElsewhere));
    }
}
