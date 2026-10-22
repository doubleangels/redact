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

/** Cold-starts the app and visits every bottom-nav tab so their code lands in the profile. */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // A fresh install asks for media, location and notification access in turn. Answer each
        // prompt so the first-launch flow lands in the profile and the tabs underneath can be tapped.
        allowPermissionPrompts(device)
        for (tab in listOf("scan", "convert", "censor", "settings", "clean")) {
            val item = device.wait(Until.findObject(By.res(PACKAGE, "navigation_$tab")), 3_000)
            item?.click()
            device.waitForIdle()
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
