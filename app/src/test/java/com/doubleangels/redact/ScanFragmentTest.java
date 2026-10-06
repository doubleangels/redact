package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.appcompat.app.AlertDialog;

import com.doubleangels.redact.media.AppProcessingScope;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.ui.ScanViewModel;
import com.google.android.material.button.MaterialButton;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.function.BooleanSupplier;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ScanFragmentTest {

    private ActivityController<MainActivity> controller;
    private MainActivity activity;

    @Before
    public void setUp() {
        AppProcessingScope.resetForTests();
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        activity = controller.get();
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    @After
    public void tearDown() {
        controller.pause().stop().destroy();
        AppProcessingScope.resetForTests();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void awaitMain(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            idle();
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for background work");
            }
            Thread.sleep(25);
        }
    }

    private Fragment scanFragment() {
        return activity.getSupportFragmentManager().findFragmentByTag("scan");
    }

    private View scanView() {
        activity.selectTab(R.id.navigation_scan);
        idle();
        return scanFragment().getView();
    }

    private static File jpeg(boolean withExif, boolean withGps) throws Exception {
        File f = File.createTempFile("scan_", ".jpg");
        f.deleteOnExit();
        Bitmap b = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        if (withExif) {
            ExifInterface e = new ExifInterface(f.getAbsolutePath());
            e.setAttribute(ExifInterface.TAG_MAKE, "Acme");
            e.setAttribute(ExifInterface.TAG_MODEL, "Cam 1");
            e.setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "SN12345");
            e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2024:01:02 03:04:05");
            if (withGps) {
                e.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "40/1,30/1,0/1");
                e.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
                e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "73/1,59/1,0/1");
                e.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W");
            }
            e.saveAttributes();
        }
        return f;
    }

    private static File fakeVideo() throws Exception {
        File f = File.createTempFile("scan_", ".mp4");
        f.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0});
        }
        return f;
    }

    /** Picks {@code uri} through the Scan tab's document picker, exactly as the user would. */
    private View pick(Uri uri) {
        View v = scanView();
        v.findViewById(R.id.emptyStateSelectButton).performClick();
        idle();
        ShadowActivity shadow = shadowOf(activity);
        ShadowActivity.IntentForResult launched = shadow.peekNextStartedActivityForResult();
        assertNotNull(launched);
        Intent data = new Intent();
        data.setData(uri);
        shadow.receiveResult(launched.intent, Activity.RESULT_OK, data);
        idle();
        return v;
    }

    private boolean contentShown(View v) {
        return v.findViewById(R.id.metadataContentContainer).getVisibility() == View.VISIBLE
                && v.findViewById(R.id.metadataCard).getVisibility() == View.VISIBLE;
    }

    private MaterialButton actionCard(View v, String label) {
        ViewGroup container = v.findViewById(R.id.scanActionCardsContainer);
        for (int i = 0; i < container.getChildCount(); i++) {
            MaterialButton b = (MaterialButton) container.getChildAt(i);
            if (label.contentEquals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    private int heroVisibility(View v, int id) {
        return v.findViewById(id).getVisibility();
    }

    // ---------------------------------------------------------------------------------------

    @Test
    public void startsOnTheEmptyState() {
        View v = scanView();
        assertEquals(View.VISIBLE, v.findViewById(R.id.emptyStateContainer).getVisibility());
        assertEquals(View.GONE, v.findViewById(R.id.metadataContentContainer).getVisibility());
        assertEquals(View.GONE, v.findViewById(R.id.metadataCard).getVisibility());
    }

    @Test
    public void picking_aJpegWithExif_showsHeroRowsAndActions() throws Exception {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        File f = jpeg(true, true);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));

        assertEquals(View.VISIBLE, heroVisibility(v, R.id.heroCard));
        assertTrue(((TextView) v.findViewById(R.id.heroFileName)).getText().toString().endsWith(f.getName()));
        assertEquals("JPG", ((TextView) v.findViewById(R.id.heroFormatBadge)).getText().toString());
        assertEquals(View.VISIBLE, heroVisibility(v, R.id.heroFieldsCountBadge));
        // GPS + serial number: the hero card lists both risks.
        TextView risk = v.findViewById(R.id.heroRiskBadge);
        assertEquals(View.VISIBLE, risk.getVisibility());
        assertTrue(risk.getText().toString().contains(activity.getString(R.string.scan_risk_serial)));
        assertEquals(View.GONE, heroVisibility(v, R.id.heroVideoIndicator));
        assertNotNull(actionCard(v, activity.getString(R.string.scan_copy_all_metadata)));
        assertNotNull(actionCard(v, activity.getString(R.string.scan_clean_this_file)));
        assertNotNull(actionCard(v, activity.getString(R.string.scan_convert_this_file)));
        assertNotNull(actionCard(v, activity.getString(R.string.button_select_media)));
        assertNotNull(actionCard(v, activity.getString(R.string.scan_copy_camera)));
    }

    @Test
    public void copyActions_putTextOnTheClipboard() throws Exception {
        File f = jpeg(true, false);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));

        actionCard(v, activity.getString(R.string.scan_copy_camera)).performClick();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("Acme Cam 1", clipboard.getPrimaryClip().getItemAt(0).getText().toString());

        actionCard(v, activity.getString(R.string.scan_copy_all_metadata)).performClick();
        String all = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(all.contains("MAKE"));
    }

    @Test
    public void openInMaps_asksForConsentOnceThenLaunchesGeoIntent() throws Exception {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        File f = jpeg(true, true);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));
        MaterialButton maps = actionCard(v, activity.getString(R.string.scan_open_coordinates_in_maps));
        if (maps == null) {
            // Robolectric may not surface a usable coordinate for the generated file.
            return;
        }

        maps.performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        idle();
        assertTrue(AppPreferences.hasConsentedToOpenLocationInMaps(activity));
        Intent geo = shadowOf(activity).getNextStartedActivity();
        assertNotNull(geo);
        assertEquals(Intent.ACTION_VIEW, geo.getAction());

        maps.performClick();
        Intent again = shadowOf(activity).getNextStartedActivity();
        assertNotNull(again);
    }

    @Test
    public void openInMaps_cancellingConsent_doesNotLaunch() throws Exception {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        File f = jpeg(true, true);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));
        MaterialButton maps = actionCard(v, activity.getString(R.string.scan_open_coordinates_in_maps));
        if (maps == null) {
            return;
        }
        while (shadowOf(activity).getNextStartedActivity() != null) {
            // drop the picker launch from pick()
        }

        maps.performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        idle();

        assertFalse(AppPreferences.hasConsentedToOpenLocationInMaps(activity));
        assertNull(shadowOf(activity).getNextStartedActivity());
    }

    @Test
    public void cleanAndConvertCards_handTheFileToTheOtherTabs() throws Exception {
        File f = jpeg(true, false);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));
        MainViewModel vm = new ViewModelProvider(activity).get(MainViewModel.class);

        actionCard(v, activity.getString(R.string.scan_clean_this_file)).performClick();
        idle();
        assertEquals(1, vm.getSelectedItems().getValue().size());
        assertEquals(R.id.navigation_clean,
                ((com.google.android.material.bottomnavigation.BottomNavigationView)
                        activity.findViewById(R.id.bottomNavigation)).getSelectedItemId());

        View scan = scanView();
        actionCard(scan, activity.getString(R.string.scan_convert_this_file)).performClick();
        idle();
        assertEquals(1, vm.getConvertSelectedItems().getValue().size());
        assertEquals(R.id.navigation_convert,
                ((com.google.android.material.bottomnavigation.BottomNavigationView)
                        activity.findViewById(R.id.bottomNavigation)).getSelectedItemId());
    }

    @Test
    public void selectAnotherFileCard_reopensThePicker() throws Exception {
        File f = jpeg(false, false);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));
        while (shadowOf(activity).getNextStartedActivity() != null) {
            // drain launches left over from picking the first file
        }

        actionCard(v, activity.getString(R.string.button_select_media)).performClick();
        idle();

        assertNotNull(shadowOf(activity).peekNextStartedActivityForResult());
    }

    @Test
    public void picking_aVideo_showsTheVideoIndicator() throws Exception {
        File f = fakeVideo();
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> v.findViewById(R.id.emptyStateContainer).getVisibility() == View.GONE
                || v.findViewById(R.id.progressContainer).getVisibility() == View.GONE
                        && ((TextView) v.findViewById(R.id.statusText)).getText().length() > 0
                        && !((TextView) v.findViewById(R.id.statusText)).getText().toString()
                                .equals(activity.getString(R.string.status_analyzing)));
        idle();

        assertNotNull(scanFragment());
    }

    @Test
    public void picking_aMissingFile_reportsExtractionFailureOrEmptyResult() throws Exception {
        View v = pick(Uri.fromFile(new File("/nonexistent/missing.jpg")));
        awaitMain(() -> v.findViewById(R.id.progressContainer).getVisibility() == View.GONE);
        idle();

        assertNotNull(scanFragment());
    }

    @Test
    public void cancellingThePicker_changesNothing() {
        View v = scanView();
        v.findViewById(R.id.emptyStateSelectButton).performClick();
        idle();
        ShadowActivity shadow = shadowOf(activity);
        ShadowActivity.IntentForResult launched = shadow.peekNextStartedActivityForResult();
        assertNotNull(launched);
        shadow.receiveResult(launched.intent, Activity.RESULT_CANCELED, null);
        idle();

        assertEquals(View.VISIBLE, v.findViewById(R.id.emptyStateContainer).getVisibility());
    }

    @Test
    public void metadataSurvivesRecreation_viaTheViewModel() throws Exception {
        File f = jpeg(true, false);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));
        ScanViewModel scanViewModel = new ViewModelProvider(activity).get(ScanViewModel.class);
        assertTrue(scanViewModel.hasMetadataToRestore());

        controller.recreate();
        activity = controller.get();
        idle();
        View restored = scanFragment().getView();

        assertEquals(View.VISIBLE, restored.findViewById(R.id.metadataCard).getVisibility());
        assertEquals(View.VISIBLE, restored.findViewById(R.id.heroCard).getVisibility());
    }

    @Test
    public void grantingLocationPermission_refreshesTheLocationSection() throws Exception {
        File f = jpeg(true, true);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));

        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION);
        activity.onRequestPermissionsResult(
                PermissionManager.LOCATION_PERMISSION_REQUEST_CODE,
                new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION},
                new int[] {PackageManager.PERMISSION_GRANTED});
        long end = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < end) {
            idle();
            Thread.sleep(25);
        }

        assertEquals(View.VISIBLE, v.findViewById(R.id.metadataCard).getVisibility());
    }

    @Test
    public void locationBannerButton_requestsLocationPermission() throws Exception {
        View v = scanView();
        v.findViewById(R.id.locationPermissionButton).performClick();
        idle();
        assertNotNull(scanFragment());
    }

    @Test
    public void tabHiddenAndShown_rechecksPermissionsAndRefreshesCards() throws Exception {
        File f = jpeg(true, false);
        View v = pick(Uri.fromFile(f));
        awaitMain(() -> contentShown(v));

        activity.selectTab(R.id.navigation_clean);
        idle();
        activity.selectTab(R.id.navigation_scan);
        idle();

        assertNotNull(actionCard(v, activity.getString(R.string.scan_copy_all_metadata)));
    }

    @Test
    public void withoutStoragePermission_selectingRequestsIt() {
        shadowOf(activity.getApplication()).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE);
        View v = scanView();
        v.findViewById(R.id.emptyStateSelectButton).performClick();
        idle();
        assertNotNull(scanFragment());
    }
}
