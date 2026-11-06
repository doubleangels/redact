package com.doubleangels.redact.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * Measures a returning user's cold start to the Clean tab twice: without any ahead-of-time
 * compilation, and with the baseline profile the app ships. The gap between the two is what the
 * profiles buy; `/benchmark` runs it and compares the medians.
 *
 * Run it with `./gradlew :baselineprofile:Pixel_10_Pro_BenchmarkBenchmarkReleaseAndroidTest`, on the
 * stripped-down benchmark emulator (`Pixel_10_Pro_Benchmark`, an Automated Test Device image), not
 * the profile emulator.
 */
class StartupBenchmark {

    @get:Rule
    val rule = MacrobenchmarkRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun startupWithoutProfile() = measureColdStart(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = measureColdStart(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun measureColdStart(mode: CompilationMode) {
        // Measured as a returning user, so the introduction is never part of the start.
        becomeReturningUser(device)
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = mode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            device.wait(Until.hasObject(By.res(PACKAGE, "bottomNavigation")), 5_000)
        }
    }
}
