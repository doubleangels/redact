package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.view.HapticFeedbackConstants;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HapticsTest {

    @Test
    public void justCompleted_onlyForALiveFinish() {
        assertTrue(Haptics.justCompleted(MainViewModel.ProcessingState.PROCESSING,
                MainViewModel.ProcessingState.COMPLETED));
        // A replay to a new view, and the other transitions, stay quiet.
        assertFalse(Haptics.justCompleted(null, MainViewModel.ProcessingState.COMPLETED));
        assertFalse(Haptics.justCompleted(MainViewModel.ProcessingState.COMPLETED,
                MainViewModel.ProcessingState.COMPLETED));
        assertFalse(Haptics.justCompleted(MainViewModel.ProcessingState.PROCESSING,
                MainViewModel.ProcessingState.CANCELLED));
        assertFalse(Haptics.justCompleted(MainViewModel.ProcessingState.IDLE,
                MainViewModel.ProcessingState.PROCESSING));
    }

    @Test
    public void runFinished_confirmsSuccessAndRejectsTotalFailure() {
        View view = new View(RuntimeEnvironment.getApplication());

        Haptics.runFinished(view, 2);
        assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(view).lastHapticFeedbackPerformed());

        Haptics.runFinished(view, 0);
        assertEquals(HapticFeedbackConstants.REJECT, shadowOf(view).lastHapticFeedbackPerformed());
    }

    @Test
    public void nullView_isIgnored() {
        Haptics.confirm(null);
        Haptics.reject(null);
        Haptics.tick(null);
    }
}
