package com.doubleangels.redact.whatsnew;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Every row of the rule: What's New opens by itself only when all of them hold. */
public class WhatsNewGateTest {

    private static final int INSTALLED = 46;

    private static boolean show(boolean onboardingDone, int seen, boolean announces, boolean forThis,
                                boolean plain, boolean restored, boolean permissionFlow) {
        return WhatsNewGate.shouldShow(onboardingDone, seen, INSTALLED, announces, forThis, plain,
                restored, permissionFlow);
    }

    @Test
    public void everyConditionHolding_showsIt() {
        assertEquals(true, show(true, 45, true, true, true, false, false));
        assertEquals("an update from before the screen existed", true,
                show(true, 0, true, true, true, false, false));
    }

    @Test
    public void anyConditionFailing_hidesIt() {
        Object[][] cases = {
                {"a new install sees the introduction", false, 45, true, true, true, false, false},
                {"already seen for this version", true, INSTALLED, true, true, true, false, false},
                {"seen for a newer version", true, INSTALLED + 1, true, true, true, false, false},
                {"a fix-only release", true, 45, false, true, true, false, false},
                {"notes for another version", true, 45, true, false, true, false, false},
                {"a share, tile or shortcut", true, 45, true, true, false, false, false},
                {"a rotation", true, 45, true, true, true, true, false},
                {"a permission flow is running", true, 45, true, true, true, false, true},
        };
        for (Object[] c : cases) {
            assertEquals((String) c[0], false, show((boolean) c[1], (int) c[2], (boolean) c[3],
                    (boolean) c[4], (boolean) c[5], (boolean) c[6], (boolean) c[7]));
        }
    }
}
