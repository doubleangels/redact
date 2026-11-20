package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.CleanPreset;
import com.doubleangels.redact.CleanPresets;
import com.doubleangels.redact.R;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.media.MediaProcessor;
import com.doubleangels.redact.metadata.CleanStats;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class NewCleanAndScanFeaturesTest {

    private Application app;
    private Context themed;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        themed = new ContextThemeWrapper(app, R.style.Theme_Redact);
        com.doubleangels.redact.TestShareProvider.register(app, "com.example.gallery.provider");
    }

    @After
    public void tearDown() {
        AppPreferences.resetAll(app);
    }

    private static MediaItem item(String name) {
        return new MediaItem(Uri.parse("content://com.example.gallery.provider/" + name), false, name);
    }

    // ---- batch scan summary -------------------------------------------------------------------

    @Test
    public void tally_countsEachKindOfDetail_andNamesTheFilesWithALocation() {
        BatchScanSummary.Tally tally = new BatchScanSummary.Tally();
        tally.add("beach.jpg", new CleanStats(12, true, true, true, false));
        tally.add("receipt.jpg", new CleanStats(3, false, true, false, true));
        tally.add("clean.png", new CleanStats(0, false));
        tally.add("broken.heic", null);

        String text = tally.describe(app.getResources());

        assertEquals(4, tally.files);
        assertEquals(1, tally.unreadable);
        assertEquals(1, tally.withLocation);
        assertEquals(2, tally.withDate);
        assertEquals(1, tally.withDevice);
        assertEquals(1, tally.withName);
        assertEquals(1, tally.clean);
        assertTrue(text, text.startsWith("4 files checked:"));
        assertTrue(text, text.contains("1 has a location"));
        assertTrue(text, text.contains("2 have a date"));
        assertTrue(text, text.contains("1 couldn't be read"));
        assertTrue(text, text.contains("• beach.jpg"));
        assertFalse(text, text.contains("receipt.jpg"));
    }

    @Test
    public void tally_namesTenFilesAtMost() {
        BatchScanSummary.Tally tally = new BatchScanSummary.Tally();
        for (int i = 0; i < 13; i++) {
            tally.add("photo" + i + ".jpg", new CleanStats(1, true));
        }
        String text = tally.describe(app.getResources());
        assertTrue(text.contains("photo9.jpg"));
        assertFalse(text.contains("photo10.jpg"));
        assertTrue(text, text.contains("and 3 more"));
    }

    // ---- failed files -------------------------------------------------------------------------

    @Test
    public void failedFiles_buttonSaysHowMany_andTheDialogNamesEachWithItsReason() {
        MaterialButton button = new MaterialButton(themed);
        List<MediaProcessor.Failed> failed = List.of(
                new MediaProcessor.Failed(item("a.jpg"), MediaProcessor.FailReason.NOT_CLEANED),
                new MediaProcessor.Failed(item("b.svg"), MediaProcessor.FailReason.UNSUPPORTED),
                new MediaProcessor.Failed(item("c.jpg"), MediaProcessor.FailReason.OUT_OF_MEMORY));
        List<List<MediaItem>> retried = new ArrayList<>();

        FailedFiles.bind(button, failed, retried::add);

        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals("Failed Files (3)", button.getText().toString());
        String text = FailedFiles.describe(app, failed);
        assertTrue(text.contains("a.jpg\n" + app.getString(R.string.clean_failed_reason_not_cleaned)));
        assertTrue(text.contains(app.getString(R.string.clean_failed_reason_unsupported)));
        assertTrue(text.contains(app.getString(R.string.clean_failed_reason_memory)));

        button.performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("a format the phone can't open isn't retried", 1, retried.size());
        assertEquals(List.of(item("a.jpg"), item("c.jpg")), retried.get(0));

        FailedFiles.bind(button, List.of(), retried::add);
        assertEquals(View.GONE, button.getVisibility());
        button.setVisibility(View.VISIBLE);
        FailedFiles.hide(button);
        assertEquals(View.GONE, button.getVisibility());
        FailedFiles.bind(null, failed, retried::add);
        FailedFiles.hide(null);
    }

    @Test
    public void failedFiles_onlyUnsupported_offersNoRetry() {
        MaterialButton button = new MaterialButton(themed);
        FailedFiles.bind(button, List.of(new MediaProcessor.Failed(item("b.svg"),
                MediaProcessor.FailReason.UNSUPPORTED)), items -> { });
        button.performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
    }

    // ---- dropped and pasted media ------------------------------------------------------------

    @Test
    public void droppedMedia_takesOnlyOtherAppsContent_upToABatch() {
        ClipData clip = new ClipData("media", new String[] {"image/jpeg"},
                new ClipData.Item(Uri.parse("content://com.example.gallery.provider/1")));
        clip.addItem(new ClipData.Item(Uri.parse("file:///sdcard/secret.jpg")));
        clip.addItem(new ClipData.Item(Uri.parse("content://" + app.getPackageName() + ".fileprovider/x")));
        clip.addItem(new ClipData.Item("just text"));

        List<Uri> uris = DroppedMedia.acceptedUris(app, clip);

        assertEquals(List.of(Uri.parse("content://com.example.gallery.provider/1")), uris);
        assertTrue(DroppedMedia.acceptedUris(app, null).isEmpty());
        assertTrue(DroppedMedia.offersMedia(new ClipDescription("m", new String[] {"video/mp4"})));
        assertFalse(DroppedMedia.offersMedia(new ClipDescription("t", new String[] {"text/plain"})));
        assertFalse(DroppedMedia.offersMedia(null));
    }

    @Test
    public void dropping_takesMediaOnly_andFadesTheTabWhileHeldOverIt() {
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        View target = new View(activity);
        activity.setContentView(target);
        ShadowLooper.idleMainLooper();
        List<List<Uri>> delivered = new ArrayList<>();
        DroppedMedia.acceptDrops(activity, target, delivered::add);
        ClipDescription media = new ClipDescription("m", new String[] {"image/jpeg"});
        ClipData clip = new ClipData(media, new ClipData.Item(Uri.parse("content://com.example.gallery.provider/photo/2")));

        assertTrue(drag(target, android.view.DragEvent.ACTION_DRAG_STARTED, media, null));
        assertFalse("text isn't taken", drag(target, android.view.DragEvent.ACTION_DRAG_STARTED,
                new ClipDescription("t", new String[] {"text/plain"}), null));
        drag(target, android.view.DragEvent.ACTION_DRAG_ENTERED, media, null);
        assertTrue(target.getAlpha() < 1f);
        drag(target, android.view.DragEvent.ACTION_DRAG_EXITED, media, null);
        assertEquals(1f, target.getAlpha(), 0f);
        drag(target, android.view.DragEvent.ACTION_DRAG_LOCATION, media, null);

        boolean taken = drag(target, android.view.DragEvent.ACTION_DROP, media, clip);
        assertEquals(1f, target.getAlpha(), 0f);
        if (taken) {
            assertEquals(List.of(Uri.parse("content://com.example.gallery.provider/photo/2")), delivered.get(0));
        } else {
            assertTrue("refused without the drop's read permission", delivered.isEmpty());
        }
        drag(target, android.view.DragEvent.ACTION_DRAG_ENDED, media, null);
    }

    private static boolean drag(View target, int action, ClipDescription description, ClipData data) {
        return target.dispatchDragEvent(org.robolectric.shadows.DragEventBuilder.newBuilder()
                .setAction(action).setClipDescription(description).setClipData(data).build());
    }

    @Test
    public void pasting_readsTheClipboardOnlyWhenItOffersMedia() {
        ClipboardManager clipboard = app.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("t", "hello"));
        assertFalse(DroppedMedia.clipboardOffersMedia(app));
        assertTrue(DroppedMedia.fromClipboard(app).isEmpty());

        clipboard.setPrimaryClip(new ClipData("m", new String[] {"image/png"},
                new ClipData.Item(Uri.parse("content://com.example.gallery.provider/7"))));
        assertTrue(DroppedMedia.clipboardOffersMedia(app));
        assertEquals(List.of(Uri.parse("content://com.example.gallery.provider/7")), DroppedMedia.fromClipboard(app));
    }

    // ---- quick toggles ------------------------------------------------------------------------

    @Test
    public void quickToggles_followAndChangeTheSettings_withTheSameRulesAsSettings() {
        View root = LayoutInflater.from(themed).inflate(R.layout.fragment_clean, null);
        AppPreferences.setStrictClean(app, true);
        CleanQuickToggles toggles = new CleanQuickToggles(root);
        Chip strict = root.findViewById(R.id.chipStrictClean);
        Chip location = root.findViewById(R.id.chipKeepLocation);
        Chip date = root.findViewById(R.id.chipKeepDate);
        Chip camera = root.findViewById(R.id.chipKeepCamera);
        Chip audio = root.findViewById(R.id.chipRemoveAudio);

        assertTrue(strict.isChecked());
        assertFalse("Strict Clean keeps nothing", date.isEnabled());

        strict.setChecked(false);
        assertFalse(AppPreferences.isStrictClean(app));
        assertTrue(date.isEnabled());
        date.setChecked(true);
        camera.setChecked(true);
        audio.setChecked(true);
        assertTrue(AppPreferences.isPreserveDateTaken(app));
        assertTrue(AppPreferences.isPreserveCameraSettings(app));
        assertTrue(AppPreferences.isRemoveAudio(app));

        // Keep Location asks first and stays off until confirmed.
        location.setChecked(true);
        assertFalse(AppPreferences.isPreserveLocation(app));
        assertFalse(location.isChecked());
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(AppPreferences.isPreserveLocation(app));
        assertTrue(location.isChecked());
        location.setChecked(false);
        assertFalse(AppPreferences.isPreserveLocation(app));

        // Strict Clean turns the Keep options off, as Settings does.
        strict.setChecked(true);
        assertFalse(AppPreferences.isPreserveDateTaken(app));
        assertFalse(date.isChecked());

        toggles.setLocked(true);
        assertFalse(strict.isEnabled());
        assertFalse(audio.isEnabled());
        toggles.setLocked(false);
        assertTrue(audio.isEnabled());

        // Changed elsewhere (Settings), picked up on the next sync.
        AppPreferences.setRemoveAudio(app, false);
        toggles.sync();
        assertFalse(audio.isChecked());
    }

    @Test
    public void presetChip_showsAndPicksThePreset_andLocksWithTheOthers() {
        View root = LayoutInflater.from(themed).inflate(R.layout.fragment_clean, null);
        AppPreferences.resetAll(app);
        CleanQuickToggles toggles = new CleanQuickToggles(root);
        Chip preset = root.findViewById(R.id.chipCleanPreset);
        Chip strict = root.findViewById(R.id.chipStrictClean);
        Chip date = root.findViewById(R.id.chipKeepDate);
        assertEquals(app.getString(R.string.clean_preset_standard), preset.getText().toString());
        assertEquals(app.getString(R.string.clean_preset_chip_description,
                app.getString(R.string.clean_preset_standard)), preset.getContentDescription().toString());

        // Another chip changes the options: the preset follows.
        strict.setChecked(false);
        assertEquals(app.getString(R.string.clean_preset_custom), preset.getText().toString());

        // Picking from the menu sets the options and the other chips.
        preset.performClick();
        AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
        menu.getListView().performItemClick(null, 1, 1);
        ShadowLooper.idleMainLooper();
        assertEquals(CleanPreset.SENDING_TO_PEOPLE, CleanPresets.current(app));
        assertEquals(app.getString(R.string.clean_preset_sending), preset.getText().toString());
        assertTrue(date.isChecked());

        // Keeping for Myself asks first; declining changes nothing.
        preset.performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 2, 2);
        ShadowLooper.idleMainLooper();
        AlertDialog confirm = (AlertDialog) ShadowDialog.getLatestDialog();
        confirm.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(CleanPreset.SENDING_TO_PEOPLE, CleanPresets.current(app));
        preset.performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 2, 2);
        ShadowLooper.idleMainLooper();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(CleanPreset.KEEPING_FOR_MYSELF, CleanPresets.current(app));
        assertTrue(((Chip) root.findViewById(R.id.chipKeepLocation)).isChecked());

        toggles.setLocked(true);
        assertFalse(preset.isEnabled());
        int dialogs = ShadowDialog.getShownDialogs().size();
        preset.performClick();
        assertEquals("a locked chip opens nothing", dialogs, ShadowDialog.getShownDialogs().size());
        toggles.setLocked(false);
        assertTrue(preset.isEnabled());
        AppPreferences.resetAll(app);
    }

    @Test
    public void quickToggles_doNotWriteRestoredStateBackOverAReset() {
        // Strict Clean off and Remove Audio on, then Reset All Settings, which recreates the
        // activity: the new Clean tab's chips get the old view state restored after they synced.
        View before = LayoutInflater.from(themed).inflate(R.layout.fragment_clean, null);
        AppPreferences.setStrictClean(app, false);
        AppPreferences.setRemoveAudio(app, true);
        new CleanQuickToggles(before);
        android.util.SparseArray<android.os.Parcelable> saved = new android.util.SparseArray<>();
        before.saveHierarchyState(saved);

        AppPreferences.resetAll(app);
        View after = LayoutInflater.from(themed).inflate(R.layout.fragment_clean, null);
        new CleanQuickToggles(after);
        after.restoreHierarchyState(saved);

        assertTrue("the reset's Strict Clean stays on", AppPreferences.isStrictClean(app));
        assertFalse(AppPreferences.isRemoveAudio(app));
        assertTrue(((Chip) after.findViewById(R.id.chipStrictClean)).isChecked());
        assertFalse(((Chip) after.findViewById(R.id.chipRemoveAudio)).isChecked());
    }
}
