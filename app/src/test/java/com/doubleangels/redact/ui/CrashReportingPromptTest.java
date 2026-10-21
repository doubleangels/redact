package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;

import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class CrashReportingPromptTest {

    private static AlertDialog show(AtomicBoolean allowed, AtomicBoolean denied) {
        Activity activity = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class)
                .setup().get();
        activity.setTheme(R.style.Theme_Redact);
        AlertDialog dialog = CrashReportingPrompt.consentDialog(activity,
                () -> allowed.set(true), () -> denied.set(true)).show();
        shadowOf(Looper.getMainLooper()).idle();
        return dialog;
    }

    @Test
    public void consentDialog_hasEnableAndNotNow() {
        AlertDialog dialog = show(new AtomicBoolean(), new AtomicBoolean());
        assertEquals(dialog.getContext().getString(R.string.settings_sentry_consent_allow),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(dialog.getContext().getString(R.string.settings_sentry_consent_cancel),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
    }

    @Test
    public void consentDialog_runsTheMatchingAnswer() {
        AtomicBoolean allowed = new AtomicBoolean();
        AtomicBoolean denied = new AtomicBoolean();
        show(allowed, denied).getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue(allowed.get());
        assertFalse(denied.get());

        AtomicBoolean allowed2 = new AtomicBoolean();
        AtomicBoolean denied2 = new AtomicBoolean();
        show(allowed2, denied2).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(allowed2.get());
        assertTrue(denied2.get());
    }
}
