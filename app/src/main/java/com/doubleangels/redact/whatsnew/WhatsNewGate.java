package com.doubleangels.redact.whatsnew;

/**
 * Whether What's New opens by itself on this start. It never interrupts a task, so every
 * condition must hold; the screen is always there in Settings › About.
 */
public final class WhatsNewGate {

    private WhatsNewGate() {
    }

    /**
     * @param onboardingDone        a new install sees the introduction, not release notes
     * @param seenVersion           the versionCode whose notes were last opened, 0 if none
     * @param installedVersion      this build's versionCode
     * @param announces             fix-only releases don't open it
     * @param notesForThisVersion   notes written for another version are never shown
     * @param plainLaunch           a launcher start: a share, view, tile or shortcut is a task
     * @param restored              a rotation or process restore doesn't open it again
     * @param permissionFlowRunning one thing at a time
     */
    public static boolean shouldShow(boolean onboardingDone, int seenVersion, int installedVersion,
                                     boolean announces, boolean notesForThisVersion,
                                     boolean plainLaunch, boolean restored,
                                     boolean permissionFlowRunning) {
        return onboardingDone
                && seenVersion < installedVersion
                && announces
                && notesForThisVersion
                && plainLaunch
                && !restored
                && !permissionFlowRunning;
    }
}
