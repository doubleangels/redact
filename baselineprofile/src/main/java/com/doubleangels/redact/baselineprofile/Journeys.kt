package com.doubleangels.redact.baselineprofile

import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/** Steps shared by the profile generator and the startup benchmark. */

internal const val PACKAGE = "com.doubleangels.redact"

/**
 * The system permission prompt's allow buttons by id: "Allow", "Allow all" (photos), "While using the
 * app" and the like. Some prompts use ids outside this pattern on some images, so [ALLOW_LABEL]
 * backs it up.
 */
private val ALLOW_BUTTON: Pattern =
    // Listed one by one: a wider pattern would also catch "Select photos" (permission_allow_selected_button),
    // which opens a picker instead of granting access.
    Pattern.compile(
        "com\\.(google\\.)?android\\.permissioncontroller:id/permission_allow(_all|_foreground_only|_one_time)?_button")

/** The permission prompt's packages, to find an allow button by its label. */
private val PERMISSION_CONTROLLER: Pattern = Pattern.compile("com\\.(google\\.)?android\\.permissioncontroller")

/** The allow buttons' English labels, for a prompt whose button id the pattern above misses. */
private val ALLOW_LABEL: Pattern = Pattern.compile("(?i)allow( all)?|while using the app|only this time")

/** The introduction's crash-report page, whose main button would turn crash reports on. */
private const val CRASH_REPORTS_STEP = 8

/**
 * Opens the app and goes through the introduction, so what comes next starts as a returning user.
 * It waits for the main screen itself: on a freshly booted emulator the introduction can take a while
 * to show, and the main screen can flash before the introduction opens over it. A freshly booted
 * emulator can also be slow for minutes while Google Play services syncs, so it keeps trying for up
 * to [SETTLE_TIMEOUT_MS]; when the emulator is responsive this takes seconds.
 */
internal fun becomeReturningUser(device: UiDevice) {
    device.executeShellCommand("am start -W -n $PACKAGE/.MainActivity")
    val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
    var settled = false
    while (!settled && System.currentTimeMillis() < deadline) {
        finishIntroduction(device)
        allowPermissionPrompts(device)
        settled = device.wait(Until.hasObject(By.res(PACKAGE, "bottomNavigation")), 5_000) &&
            !device.wait(Until.hasObject(By.res(PACKAGE, "onboardingProgress")), 2_000)
    }
    check(settled) { "The introduction did not finish, so the app is not in its returning-user state" }
}

/** How long [becomeReturningUser] keeps trying before it fails the run. */
private const val SETTLE_TIMEOUT_MS = 180_000L

/**
 * Skips to the introduction's choices, then allows media, location and notification access as
 * a new user would. Crash reports get Not Now: a profile or benchmark run must never turn them on.
 */
internal fun finishIntroduction(device: UiDevice) {
    // Skip jumps to the choices from the first pages. It is not on the later ones, and a permission
    // prompt over the introduction hides it, so the pages below are worked through either way.
    clickWhenShown(device, By.res(PACKAGE, "onboardingSkip"), 5_000)
    repeat(12) {
        device.waitForIdle()
        allowPermissionPrompts(device)
        val progress = device.findObject(By.res(PACKAGE, "onboardingProgress")) ?: return
        // "Step 8 of 9": the step number is the first number in the description.
        val step = Regex("\\d+").find(progress.contentDescription ?: "")?.value?.toInt()
        val button = if (step == CRASH_REPORTS_STEP) "onboardingSecondary" else "onboardingPrimary"
        clickWhenShown(device, By.res(PACKAGE, button), 2_000)
    }
}

internal fun allowPermissionPrompts(device: UiDevice) {
    repeat(4) {
        val clicked = clickWhenShown(device, By.res(ALLOW_BUTTON), 2_000) ||
            clickWhenShown(device, By.pkg(PERMISSION_CONTROLLER).text(ALLOW_LABEL).clickable(true), 500)
        if (!clicked) return
        device.waitForIdle()
    }
}

/**
 * Clicks what [selector] finds once it shows, and returns whether it did. A permission prompt or
 * an introduction page can redraw between finding a button and clicking it, which makes the found
 * object stale; then it looks again instead of failing the run.
 */
internal fun clickWhenShown(device: UiDevice, selector: BySelector, timeoutMs: Long): Boolean {
    repeat(3) {
        val found = device.wait(Until.findObject(selector), timeoutMs) ?: return false
        try {
            found.click()
            return true
        } catch (e: StaleObjectException) {
            device.waitForIdle()
        }
    }
    return false
}
