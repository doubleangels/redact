package com.doubleangels.redact.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test

private const val PACKAGE = "com.doubleangels.redact"

/** The system permission prompt's allow buttons: "Allow", "Allow all" (photos) or "While using the app". */
private val ALLOW_BUTTON: Pattern =
    Pattern.compile("com\\.android\\.permissioncontroller:id/permission_allow(_all|_foreground_only)?_button")

/** The introduction's crash-report page, whose main button would turn crash reports on. */
private const val CRASH_REPORTS_STEP = 8

/** Cold-starts the app and visits every bottom-nav tab so their code lands in the profile. */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // A fresh install opens the introduction, which covers the tabs; without going through it
        // the taps below find nothing and the profile misses every tab but Clean.
        finishIntroduction(device)
        allowPermissionPrompts(device)
        for (tab in listOf("scan", "convert", "censor", "settings", "clean")) {
            val item = device.wait(Until.findObject(By.res(PACKAGE, "navigation_$tab")), 3_000)
            item?.click()
            device.waitForIdle()
        }
    }

    /**
     * Skips to the introduction's choices, then allows media, location and notification access as
     * a new user would. Crash reports get Not Now: a profile run must never turn them on.
     */
    private fun finishIntroduction(device: UiDevice) {
        device.wait(Until.findObject(By.res(PACKAGE, "onboardingSkip")), 3_000)?.click() ?: return
        repeat(12) {
            device.waitForIdle()
            allowPermissionPrompts(device)
            val progress = device.findObject(By.res(PACKAGE, "onboardingProgress")) ?: return
            // "Step 8 of 9": the step number is the first number in the description.
            val step = Regex("\\d+").find(progress.contentDescription ?: "")?.value?.toInt()
            val button = if (step == CRASH_REPORTS_STEP) "onboardingSecondary" else "onboardingPrimary"
            device.wait(Until.findObject(By.res(PACKAGE, button)), 2_000)?.click()
        }
    }

    private fun allowPermissionPrompts(device: UiDevice) {
        repeat(4) {
            val allow = device.wait(Until.findObject(By.res(ALLOW_BUTTON)), 2_000) ?: return
            allow.click()
            device.waitForIdle()
        }
    }
}
