package com.doubleangels.redact.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

private const val PACKAGE = "com.doubleangels.redact"

/** Cold-starts the app and visits every bottom-nav tab so their code lands in the profile. */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(PACKAGE) {
        pressHome()
        startActivityAndWait()
        // Fresh installs show the permission flow first; leave it alone and just exercise the UI.
        for (tab in listOf("scan", "convert", "settings", "clean")) {
            val item = device.wait(Until.findObject(By.res(PACKAGE, "navigation_$tab")), 3_000)
            item?.click()
            device.waitForIdle()
        }
    }
}
