package com.doubleangels.redact.baselineprofile

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test

private const val PACKAGE = "com.doubleangels.redact"

/** The system permission prompt's allow buttons: "Allow", "Allow all" (photos) or "While using the app". */
private val ALLOW_BUTTON: Pattern =
    Pattern.compile("com\\.android\\.permissioncontroller:id/permission_allow(_all|_foreground_only)?_button")

/** The system share sheet that Redact's Clean share opens with the cleaned copy. */
private val SHARE_SHEET: Pattern = Pattern.compile("com\\.android\\.intentresolver|android")

/** The introduction's crash-report page, whose main button would turn crash reports on. */
private const val CRASH_REPORTS_STEP = 8

/** The Quick Settings tiles, each a shortcut to one tab. */
private val TILES = listOf("CleanTileService", "ScanTileService", "CensorTileService")

/** The share targets that open a tab with the shared photo, besides Clean's ShareHandlerActivity. */
private val TAB_SHARE_TARGETS = mapOf(
    "ScanShareTarget" to "metadataCard",
    "ConvertShareTarget" to "convertSummaryCard",
    "CensorShareTarget" to "censorContent",
)

/**
 * Generates the two profiles from two journeys:
 *
 * - [startup] feeds the startup profile, which decides what goes first in the app's primary dex: only
 *   a cold start to the Clean tab, the way a returning user opens the app.
 * - [generate] feeds the baseline profile only: the introduction, every tab, Statistics, Licenses,
 *   the Quick Settings tiles and every share target, so their code is compiled ahead of time too.
 *
 * Each sets up what it needs itself, so they run in any order.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun startup() {
        // A returning user: the introduction is done before collecting, so it stays out of the profile.
        device.executeShellCommand("am start -W -n $PACKAGE/.MainActivity")
        finishIntroduction(device)
        allowPermissionPrompts(device)

        rule.collect(PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.res(PACKAGE, "bottomNavigation")), 5_000)
        }
    }

    @Test
    fun generate() {
        // Start as a new install, so the introduction runs even when [startup] already went through it.
        device.executeShellCommand("pm clear $PACKAGE")
        val photo = insertSamplePhoto()
        try {
            for (tile in TILES) device.executeShellCommand("cmd statusbar add-tile $PACKAGE/.$tile")

            rule.collect(PACKAGE, includeInStartupProfile = false) {
                pressHome()
                startActivityAndWait()
                // A fresh install opens the introduction, which covers the tabs; without going through it
                // the taps below find nothing and the profile misses every tab but Clean.
                finishIntroduction(device)
                allowPermissionPrompts(device)
                for (tab in listOf("scan", "convert", "censor", "settings")) {
                    openTab(tab)
                }
                openStatistics()
                openLicenses()
                openTab("clean")

                for (tile in TILES) {
                    device.executeShellCommand("cmd statusbar click-tile $PACKAGE/.$tile")
                    device.wait(Until.hasObject(By.res(PACKAGE, "bottomNavigation")), 5_000)
                    device.waitForIdle()
                }

                shareToClean(photo)
                for ((target, shownId) in TAB_SHARE_TARGETS) {
                    shareToTab(photo, target, shownId)
                }
            }
        } finally {
            for (tile in TILES) device.executeShellCommand("cmd statusbar remove-tile $PACKAGE/.$tile")
            InstrumentationRegistry.getInstrumentation().context.contentResolver.delete(photo, null, null)
        }
    }

    private fun openTab(tab: String) {
        device.wait(Until.findObject(By.res(PACKAGE, "navigation_$tab")), 3_000)?.click()
        device.waitForIdle()
    }

    /** Settings › the statistics card, then back. */
    private fun openStatistics() {
        val card = device.wait(Until.findObject(By.res(PACKAGE, "cardStatsSummary")), 3_000) ?: return
        card.click()
        device.wait(Until.hasObject(By.res(PACKAGE, "statsScroll")), 5_000)
        device.pressBack()
        device.waitForIdle()
    }

    /** Settings › About › Open source licenses, at the bottom of the page, then back. */
    private fun openLicenses() {
        val row = device.findObject(By.res(PACKAGE, "rowAboutLicenses"))
            ?: device.findObject(By.scrollable(true))
                ?.scrollUntil(Direction.DOWN, Until.findObject(By.res(PACKAGE, "rowAboutLicenses")))
            ?: return
        row.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    /** Shares the photo to Redact's Clean entry, waits for the share sheet with the clean copy, and closes it. */
    private fun shareToClean(photo: Uri) {
        device.executeShellCommand(shareCommand(photo, "ShareHandlerActivity"))
        device.wait(Until.hasObject(By.pkg(SHARE_SHEET).depth(0)), 20_000)
        device.pressBack()
        device.waitForIdle()
    }

    /** Shares the photo to a tab's share target and waits for that tab to show it. */
    private fun shareToTab(photo: Uri, target: String, shownId: String) {
        device.executeShellCommand(shareCommand(photo, target))
        device.wait(Until.hasObject(By.res(PACKAGE, shownId)), 10_000)
        device.waitForIdle()
    }

    private fun shareCommand(photo: Uri, target: String) =
        "am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM $photo " +
            "-n $PACKAGE/.$target"

    /**
     * Saves a small JPEG with a camera make and a GPS location to the shared Pictures folder, so the
     * share journeys have something to clean and scan. Redact reads it with the photo access the
     * introduction granted.
     */
    private fun insertSamplePhoto(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.cacheDir, "redact-profile-sample.jpg")
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(90, 140, 200)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MAKE, "ProfileCam")
            setAttribute(ExifInterface.TAG_MODEL, "Sample")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:01:01 12:00:00")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,41/1,21/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "74/1,2/1,40/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
            saveAttributes()
        }

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri).use { out -> file.inputStream().use { it.copyTo(requireNotNull(out)) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        file.delete()
        return uri
    }

    /**
     * Skips to the introduction's choices, then allows media, location and notification access as
     * a new user would. Crash reports get Not Now: a profile run must never turn them on.
     */
    private fun finishIntroduction(device: UiDevice) {
        if (!clickWhenShown(device, By.res(PACKAGE, "onboardingSkip"), 3_000)) return
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

    private fun allowPermissionPrompts(device: UiDevice) {
        repeat(4) {
            if (!clickWhenShown(device, By.res(ALLOW_BUTTON), 2_000)) return
            device.waitForIdle()
        }
    }

    /**
     * Clicks what [selector] finds once it shows, and returns whether it did. A permission prompt or
     * an introduction page can redraw between finding a button and clicking it, which makes the found
     * object stale; then it looks again instead of failing the run.
     */
    private fun clickWhenShown(device: UiDevice, selector: BySelector, timeoutMs: Long): Boolean {
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
}
