package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.FakeDocumentsProvider;
import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class OutputDestinationTest {

    private Context app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeDocumentsProvider.install(app);
        FakeMediaStoreProvider.install(app);
        OutputDestination.clearFallbackWarnings();
        AppPreferences.resetOutputTree(app, false);
        AppPreferences.resetOutputTree(app, true);
    }

    private Uri chosenImageFolder(String docId) {
        Uri tree = FakeDocumentsProvider.folder(docId);
        app.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        AppPreferences.setOutputTree(app, false, tree);
        return tree;
    }

    @Test
    public void create_withoutAChosenFolder_usesTheDefaultMediaStoreFolder() throws Exception {
        OutputDestination.create(app, false, "a.jpg", "image/jpeg");
        OutputDestination.create(app, true, "b.mp4", "video/mp4");

        assertEquals(2, FakeMediaStoreProvider.entries().size());
        for (FakeMediaStoreProvider.Entry entry : FakeMediaStoreProvider.entries().values()) {
            String expected = "a.jpg".equals(entry.displayName()) ? "Pictures/Redact" : "Movies/Redact";
            assertEquals(expected, entry.values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
            assertTrue(entry.isPending());
        }
    }

    @Test
    public void create_writesIntoTheChosenFolder() throws Exception {
        chosenImageFolder("primary:Pictures/Private");

        Uri out = OutputDestination.create(app, false, "a.jpg", "image/jpeg");

        assertEquals(FakeDocumentsProvider.AUTHORITY, out.getAuthority());
        assertTrue(FakeDocumentsProvider.fileFor("primary:Pictures/Private/a.jpg").exists());
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        assertTrue(OutputDestination.isInChosenFolder(app, out));
        // A chosen folder holds images only; videos still use the default.
        OutputDestination.create(app, true, "b.mp4", "video/mp4");
        assertEquals(1, FakeMediaStoreProvider.entries().size());
    }

    @Test
    public void create_whenTheChosenFolderIsGone_savesToTheDefault_andWarnsOnce() throws Exception {
        Uri tree = chosenImageFolder("primary:Pictures/Private");
        //noinspection ResultOfMethodCallIgnored
        FakeDocumentsProvider.fileFor("primary:Pictures/Private").delete();

        OutputDestination.create(app, false, "a.jpg", "image/jpeg");
        OutputDestination.create(app, false, "b.jpg", "image/jpeg");
        shadowOf(Looper.getMainLooper()).idle();

        assertEquals(2, FakeMediaStoreProvider.entries().size());
        assertEquals(1, ShadowToast.shownToastCount());
        assertEquals("Saving to Pictures/Redact instead: Pictures/Private is unavailable.",
                ShadowToast.getTextOfLatestToast());
        assertFalse(OutputDestination.isUsable(app, tree));

        // Choosing a folder again lets the next failure warn again.
        OutputDestination.clearFallbackWarnings();
        OutputDestination.create(app, false, "c.jpg", "image/jpeg");
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, ShadowToast.shownToastCount());
    }

    @Test
    public void discard_removesAPartialFileFromEitherKindOfDestination() throws Exception {
        chosenImageFolder("primary:Pictures/Private");
        Uri doc = OutputDestination.create(app, false, "a.jpg", "image/jpeg");
        OutputDestination.discard(app.getContentResolver(), doc);
        assertFalse(FakeDocumentsProvider.fileFor("primary:Pictures/Private/a.jpg").exists());

        Uri media = OutputDestination.create(app, true, "b.mp4", "video/mp4");
        OutputDestination.discard(app.getContentResolver(), media);
        assertTrue(FakeMediaStoreProvider.entries().isEmpty());
    }

    @Test
    public void describe_showsTheFolderAsTheUserKnowsIt() {
        assertEquals("Pictures/Private", OutputDestination.describe(app,
                FakeDocumentsProvider.tree("primary:Pictures/Private")));
        assertEquals("Pictures/Private", OutputDestination.describe(app,
                FakeDocumentsProvider.tree("primary:Pictures/Private/")));
        assertEquals("Pictures/My Photos", OutputDestination.describe(app,
                FakeDocumentsProvider.tree("primary:Pictures/My Photos")));
        // Another volume: the path, then the volume's name (its ID when it has no name).
        assertEquals("DCIM/Clips (1234-ABCD)", OutputDestination.describe(app,
                FakeDocumentsProvider.tree("1234-ABCD:DCIM/Clips")));
        assertEquals("1234-ABCD", OutputDestination.describe(app,
                FakeDocumentsProvider.tree("1234-ABCD:")));
    }

    @Test
    public void describe_downloadsFolder_isRelativeToStorage() {
        String path = Environment.getExternalStorageDirectory().getPath() + "/Download/Private";
        Uri tree = DocumentsContract.buildTreeDocumentUri(
                "com.android.providers.downloads.documents", "raw:" + path);
        assertEquals("Download/Private", OutputDestination.describe(app, tree));
    }

    @Test
    public void describe_otherProvider_fallsBackToTheFolderId() {
        Uri tree = DocumentsContract.buildTreeDocumentUri("com.example.cloud", "folder-42");
        assertEquals("folder-42", OutputDestination.describe(app, tree));
    }

    @Test
    public void pickerStartLocation_opensWhereTheFilesGo() {
        assertEquals(DocumentsContract.buildDocumentUri(FakeDocumentsProvider.AUTHORITY, "primary:Pictures"),
                OutputDestination.pickerStartLocation(app, false));
        assertEquals(DocumentsContract.buildDocumentUri(FakeDocumentsProvider.AUTHORITY, "primary:Movies"),
                OutputDestination.pickerStartLocation(app, true));

        Uri tree = chosenImageFolder("primary:Pictures/Private");
        Uri start = OutputDestination.pickerStartLocation(app, false);
        assertEquals("primary:Pictures/Private", DocumentsContract.getDocumentId(start));
        assertEquals(tree.getAuthority(), start.getAuthority());

        // A folder Redact can no longer use is not a useful place to start.
        //noinspection ResultOfMethodCallIgnored
        FakeDocumentsProvider.fileFor("primary:Pictures/Private").delete();
        assertEquals(DocumentsContract.buildDocumentUri(FakeDocumentsProvider.AUTHORITY, "primary:Pictures"),
                OutputDestination.pickerStartLocation(app, false));
    }

    @Test
    public void isDefaultFolder_recognizesRedactsOwnFolders() {
        assertTrue(OutputDestination.isDefaultFolder(FakeDocumentsProvider.tree("primary:Pictures/Redact"), false));
        assertTrue(OutputDestination.isDefaultFolder(FakeDocumentsProvider.tree("primary:pictures/redact"), false));
        assertTrue(OutputDestination.isDefaultFolder(FakeDocumentsProvider.tree("primary:Movies/Redact"), true));
        assertFalse(OutputDestination.isDefaultFolder(FakeDocumentsProvider.tree("primary:Movies/Redact"), false));
        assertFalse(OutputDestination.isDefaultFolder(FakeDocumentsProvider.tree("1234-ABCD:Pictures/Redact"), false));
        assertFalse(OutputDestination.isDefaultFolder(FakeDocumentsProvider.tree("primary:Pictures/Redact2"), false));
    }

    @Test
    public void isUsable_needsAWriteGrantAndAFolderThatTakesFiles() {
        Uri tree = FakeDocumentsProvider.folder("primary:Pictures/Private");
        assertFalse(OutputDestination.isUsable(app, tree));

        app.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        assertTrue(OutputDestination.isUsable(app, tree));

        FakeDocumentsProvider.readOnly = true;
        assertFalse(OutputDestination.isUsable(app, tree));
    }

    @Test
    public void chosenRelativePaths_listsStorageFoldersOnly() {
        AppPreferences.setOutputTree(app, false, FakeDocumentsProvider.tree("primary:Pictures/Private"));
        AppPreferences.setOutputTree(app, true,
                DocumentsContract.buildTreeDocumentUri("com.example.cloud", "folder-42"));
        assertEquals(List.of("Pictures/Private"), OutputDestination.chosenRelativePaths(app));
    }

    @Test
    public void trimSlashes_removesLeadingAndTrailingSlashes() {
        assertEquals("Pictures/Private", OutputDestination.trimSlashes("/Pictures/Private//"));
        assertEquals("", OutputDestination.trimSlashes("///"));
    }
}
