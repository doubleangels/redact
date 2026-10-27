package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.snackbar.Snackbar;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import io.sentry.protocol.SentryId;

/** After a reported failure, the user can add a note to the report, but only with crash reporting on. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ErrorFeedbackPromptTest {

    private Application app;
    private Activity activity;
    private FrameLayout root;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        AppPreferences.setCrashReportingEnabled(app, true);
        SentryManager.init(app);
        Notice.resetForTests();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(new android.view.ContextThemeWrapper(activity, R.style.Theme_Redact));
        activity.setContentView(root);
    }

    @After
    public void tearDown() {
        AppPreferences.setCrashReportingEnabled(app, false);
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** The snackbar's action button, or null when no snackbar was shown. */
    private Button snackbarAction() {
        idle();
        Snackbar.SnackbarLayout layout = findSnackbar(activity.findViewById(android.R.id.content));
        return layout == null ? null : layout.findViewById(com.google.android.material.R.id.snackbar_action);
    }

    private static Snackbar.SnackbarLayout findSnackbar(android.view.ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i) instanceof Snackbar.SnackbarLayout layout) {
                return layout;
            }
        }
        return null;
    }

    private AlertDialog openDialog() {
        ErrorFeedbackPrompt.offer(root, SentryId.EMPTY_ID);
        snackbarAction().performClick();
        idle();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    @Test
    public void withoutAReport_orWithReportingOff_nothingIsOffered() {
        ErrorFeedbackPrompt.offer(root, null);
        ErrorFeedbackPrompt.offer(null, SentryId.EMPTY_ID);
        AppPreferences.setCrashReportingEnabled(app, false);
        ErrorFeedbackPrompt.offer(root, SentryId.EMPTY_ID);

        assertNull(snackbarAction());
    }

    @Test
    public void afterAReport_aSnackbarOffersToAddANote() {
        ErrorFeedbackPrompt.offer(root, SentryId.EMPTY_ID);

        Button action = snackbarAction();
        assertNotNull(action);
        assertEquals(app.getString(R.string.error_feedback_action), action.getText().toString());
    }

    @Test
    public void send_staysOffUntilSomethingIsTyped() {
        AlertDialog dialog = openDialog();
        Button send = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        EditText input = dialog.findViewById(R.id.errorFeedbackInput);

        assertFalse(send.isEnabled());
        input.setText("   ");
        assertFalse(send.isEnabled());
        input.setText("It froze on the second video");
        assertTrue(send.isEnabled());
    }

    @Test
    public void sending_closesTheDialog() {
        AlertDialog dialog = openDialog();
        ((EditText) dialog.findViewById(R.id.errorFeedbackInput)).setText("It froze");

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        assertFalse(dialog.isShowing());
    }

    @Test
    public void theDialogExplainsWhatTheNoteIsFor() {
        AlertDialog dialog = openDialog();

        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(app.getString(R.string.error_feedback_message), message.getText().toString());
    }
}
