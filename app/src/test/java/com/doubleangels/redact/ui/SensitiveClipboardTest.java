package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;

import com.doubleangels.redact.AppPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;

/** Copied metadata is cleared from the clipboard after the chosen delay, but only while it is still ours. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class SensitiveClipboardTest {

    private Application app;
    private ClipboardManager clipboard;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
        // Drop any timer a previous test left behind, and start from an empty clipboard.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(10));
        clipboard.clearPrimaryClip();
    }

    private static void passTime(long seconds) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds));
    }

    private String clipText() {
        ClipData clip = clipboard.getPrimaryClip();
        return clip == null || clip.getItemCount() == 0 ? null : String.valueOf(clip.getItemAt(0).getText());
    }

    @Test
    public void defaultIsOff() {
        assertEquals(0, AppPreferences.getClipboardClearSeconds(app));
    }

    @Test
    public void unknownStoredValue_fallsBackToOff() {
        AppPreferences.setClipboardClearSeconds(app, 7);
        assertEquals(0, AppPreferences.getClipboardClearSeconds(app));
    }

    @Test
    public void withTheDefault_copiedTextIsNeverCleared() {
        SensitiveClipboard.copy(app, "label", "secret");

        passTime(600);

        assertEquals("secret", clipText());
    }

    @Test
    public void copy_placesTheTextOnTheClipboard() {
        assertTrue(SensitiveClipboard.copy(app, "label", "40.7,-74.0"));
        assertEquals("40.7,-74.0", clipText());
    }

    @Test
    public void copy_isClearedAfterTheDelay_butNotBefore() {
        AppPreferences.setClipboardClearSeconds(app, 30);
        SensitiveClipboard.copy(app, "label", "secret");

        passTime(29);
        assertEquals("secret", clipText());

        passTime(2);
        assertNull(clipText());
    }

    @Test
    public void copy_isNotClearedWhenTheUserCopiedSomethingElse() {
        AppPreferences.setClipboardClearSeconds(app, 15);
        SensitiveClipboard.copy(app, "label", "secret");
        clipboard.setPrimaryClip(ClipData.newPlainText("other", "my own text"));

        passTime(60);

        assertEquals("my own text", clipText());
    }

    @Test
    public void aNewCopy_restartsTheTimer() {
        AppPreferences.setClipboardClearSeconds(app, 30);
        SensitiveClipboard.copy(app, "label", "first");
        passTime(20);
        SensitiveClipboard.copy(app, "label", "second");

        passTime(20); // 40s after the first copy, only 20s after the second
        assertEquals("second", clipText());

        passTime(11);
        assertNull(clipText());
    }

    @Test
    public void whenOff_theClipboardIsLeftAlone() {
        AppPreferences.setClipboardClearSeconds(app, 0);
        SensitiveClipboard.copy(app, "label", "secret");

        passTime(600);

        assertEquals("secret", clipText());
    }

    @Test
    public void copy_tagsTheClipSoItCanBeRecognizedLater() {
        SensitiveClipboard.copy(app, "label", "secret");

        assertNotNull(clipboard.getPrimaryClipDescription());
        assertNotNull(clipboard.getPrimaryClipDescription().getExtras());
        assertFalse(clipboard.getPrimaryClipDescription().getExtras().isEmpty());
    }
}
