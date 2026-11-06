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
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.regex.Pattern
import org.junit.Rule
import org.junit.Test

/** The system share sheet that Redact's Clean share opens with the cleaned copy. */
private val SHARE_SHEET: Pattern = Pattern.compile("com\\.android\\.intentresolver|android")

/** The Quick Settings tiles, each a shortcut to one tab. */
private val TILES = listOf("CleanTileService", "ScanTileService", "CensorTileService")

/** The file picker Clean's Select Files opens. */
private val FILE_PICKER: Pattern = Pattern.compile("com\\.(google\\.)?android\\.documentsui")

/** Both samples are named with this, so the picker and the cleanup can find them. */
private const val SAMPLE_NAME = "redact-profile-sample"

/** A job's longest wait: a Strict Clean re-encodes the sample video, which is slow on an emulator. */
private const val JOB_TIMEOUT_MS = 90_000L

/**
 * Generates the two profiles from two journeys:
 *
 * - [startup] feeds the startup profile, which decides what goes first in the app's primary dex: only
 *   a cold start to the Clean tab, the way a returning user opens the app.
 * - [generate] feeds the baseline profile only: the introduction, every tab, Statistics, Licenses,
 *   the Quick Settings tiles and every share target, and real jobs on a sample photo and video (a
 *   clean from the Clean tab and from a share, a scan of each, a conversion and a censor save), so
 *   the code that does the work is compiled ahead of time too, not only the screens.
 *
 * Each sets up what it needs itself, so they run in any order.
 *
 * The class name is kept short on purpose: it starts the name of every profile file the emulator
 * writes, and with a longer one the copy back to Windows passes the 260-character path limit adb
 * cannot go over, so no profile arrives and the run deletes the committed ones.
 */
class Profiles {

    @get:Rule
    val rule = BaselineProfileRule()

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun startup() {
        // A returning user: the introduction is done before collecting, so it stays out of the profile.
        becomeReturningUser(device)

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
        val video = recordSampleVideo()
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

                cleanFromCleanTab()
                shareToClean(photo, "image/jpeg")
                shareToTab(photo, "image/jpeg", "ScanShareTarget", "metadataCard")
                convertToPng(photo)
                censorAndSave(photo)
                if (video != null) {
                    // The introduction's photo prompt can leave Redact without video access, and the
                    // sample video belongs to the shell, so grant it here, after the introduction.
                    device.executeShellCommand("pm grant $PACKAGE android.permission.READ_MEDIA_VIDEO")
                    shareToClean(video, "video/mp4")
                    shareToTab(video, "video/mp4", "ScanShareTarget", "metadataCard")
                }
            }
        } finally {
            for (tile in TILES) device.executeShellCommand("cmd statusbar remove-tile $PACKAGE/.$tile")
            InstrumentationRegistry.getInstrumentation().context.contentResolver.delete(photo, null, null)
            if (video != null) {
                device.executeShellCommand("content delete --uri $video")
            }
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

    /**
     * Clean › Select Files, picks the sample photo in the file picker, and cleans it: the Clean tab's
     * own job, which saves the copy, unlike a share.
     */
    private fun cleanFromCleanTab() {
        openTab("clean")
        if (!clickWhenShown(device, By.res(PACKAGE, "emptyStateSelectButton"), 3_000)) return
        if (!device.wait(Until.hasObject(By.pkg(FILE_PICKER)), 10_000)) return
        // The picker opens on Recent, where the sample was just added.
        if (!clickWhenShown(device, By.pkg(FILE_PICKER).textStartsWith("$SAMPLE_NAME.jpg"), 10_000)) {
            device.pressBack()
            return
        }
        if (!clickWhenShown(device, By.res(PACKAGE, "stripButton"), 10_000)) return
        waitForJob("shareResultsButton")
    }

    /**
     * Shares the file to Redact's Clean entry, waits for the share sheet with the clean copy, and
     * closes it. Stops waiting early if the share handler closes without a share sheet (the clean
     * failed), so a failure costs seconds, not the whole job timeout. The handler is see-through, so
     * the main screen showing underneath says nothing; the top activity does.
     */
    private fun shareToClean(file: Uri, mimeType: String) {
        device.executeShellCommand(shareCommand(file, mimeType, "ShareHandlerActivity"))
        val deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.pkg(SHARE_SHEET).depth(0)), 2_000)) {
                device.pressBack()
                break
            }
            if (!topActivity().contains("ShareHandlerActivity")) break
        }
        device.waitForIdle()
    }

    /** Shares the file to a tab's share target and waits for that tab to show it. */
    private fun shareToTab(file: Uri, mimeType: String, target: String, shownId: String) {
        device.executeShellCommand(shareCommand(file, mimeType, target))
        device.wait(Until.hasObject(By.res(PACKAGE, shownId)), 20_000)
        device.waitForIdle()
    }

    /** Shares the photo to Convert, picks PNG and converts it. */
    private fun convertToPng(photo: Uri) {
        shareToTab(photo, "image/jpeg", "ConvertShareTarget", "convertButton")
        clickWhenShown(device, By.res(PACKAGE, "chipFormatPng"), 3_000)
        if (!clickWhenShown(device, By.res(PACKAGE, "convertButton"), 3_000)) return
        waitForJob("shareResultsButton")
    }

    /** Shares the photo to Censor, draws a box across the middle and saves the censored copy. */
    private fun censorAndSave(photo: Uri) {
        shareToTab(photo, "image/jpeg", "CensorShareTarget", "censorCanvas")
        val canvas = device.wait(Until.findObject(By.res(PACKAGE, "censorCanvas")), 10_000) ?: return
        device.waitForIdle()
        val box = canvas.visibleBounds
        device.swipe(box.centerX() - box.width() / 4, box.centerY() - box.height() / 8,
            box.centerX() + box.width() / 4, box.centerY() + box.height() / 8, 20)
        device.waitForIdle()
        if (!clickWhenShown(device, By.res(PACKAGE, "censorSaveButton"), 3_000)) return
        waitForJob("shareResultsButton")
    }

    /**
     * Waits until a job shows [resultId] (its Share button), answering any dialog on the way with
     * its negative button: the rating prompt can follow a job, and its positive button would leave
     * for the Play Store.
     */
    private fun waitForJob(resultId: String) {
        val deadline = System.currentTimeMillis() + JOB_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (device.wait(Until.hasObject(By.res(PACKAGE, resultId)), 2_000)) break
            clickWhenShown(device, By.res("android", "button2"), 500)
        }
        device.waitForIdle()
        clickWhenShown(device, By.res("android", "button2"), 1_000)
    }

    /** The `topResumedActivity` line of `dumpsys activity activities`. */
    private fun topActivity(): String =
        device.executeShellCommand("dumpsys activity activities").lineSequence()
            .firstOrNull { it.contains("topResumedActivity") }.orEmpty()

    private fun shareCommand(file: Uri, mimeType: String, target: String) =
        "am start -a android.intent.action.SEND -t $mimeType --eu android.intent.extra.STREAM $file " +
            "-n $PACKAGE/.$target"

    /**
     * Records a two-second screen video into the shared Movies folder with the shell's screenrecord,
     * so the video journeys have a real MP4 to clean and scan, and returns its MediaStore URI. Null
     * if the emulator could not record one; the video journeys are skipped then.
     */
    private fun recordSampleVideo(): Uri? {
        val path = "/sdcard/Movies/$SAMPLE_NAME.mp4"
        device.executeShellCommand("screenrecord --time-limit 2 --size 640x360 --bit-rate 2000000 $path")
        device.executeShellCommand(
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path")
        repeat(10) {
            val row = device.executeShellCommand(
                "content query --uri content://media/external/video/media --projection _id " +
                    "--where _display_name='$SAMPLE_NAME.mp4'")
            Regex("_id=(\\d+)").find(row)?.let {
                return Uri.parse("content://media/external/video/media/${it.groupValues[1]}")
            }
            device.waitForIdle(1_000)
        }
        return null
    }

    /**
     * Saves a small JPEG with a camera make and a GPS location to the shared Pictures folder, so the
     * share journeys have something to clean and scan. Redact reads it with the photo access the
     * introduction granted.
     */
    private fun insertSamplePhoto(): Uri {
        val context = InstrumentationRegistry.getInstrumentation().context
        val file = File(context.cacheDir, "$SAMPLE_NAME.jpg")
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
}
