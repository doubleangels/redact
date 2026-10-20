package com.doubleangels.redact;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.doubleangels.redact.sentry.SentryManager;

/**
 * Single host activity: version line and bottom navigation stay fixed;
 * tab fragments are added on first visit to reduce cold-start cost.
 */
public class MainActivity extends AppCompatActivity {

    static final String TAG_CLEAN = "clean";
    private static final String TAG_SCAN = "scan";
    private static final String TAG_CONVERT = "convert";
    private static final String TAG_SETTINGS = "settings";
    private static final String KEY_SELECTED_TAB = "selected_tab";
    /** Extra set by the launcher shortcuts in res/xml/shortcuts.xml and by {@link CleanTileService}. */
    static final String EXTRA_TAB = "tab";
    /** Manifest alias that receives "Convert with Redact" shares. */
    private static final String CONVERT_SHARE_TARGET = ".ConvertShareTarget";
    /** Same cap as picking files in Convert. */
    private static final int MAX_CONVERT_SHARE_ITEMS = 20;

    @Override
    protected void onDestroy() {
        com.doubleangels.redact.permission.PermissionManager.setInitialFlowCompletedCallback(null);
        com.doubleangels.redact.permission.PermissionManager.clearRuntimePermissionRequestOnDestroy();
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        try {
            if (savedInstanceState == null
                    && com.doubleangels.redact.permission.PermissionManager.shouldPromptInitialPermissions(this)) {
                com.doubleangels.redact.permission.PermissionManager.prepareInitialPermissionFlow();
            }

            DynamicColors.applyToActivityIfAvailable(this);
            EdgeToEdge.enable(this);

            super.onCreate(savedInstanceState);
            com.doubleangels.redact.ui.SecureWindow.apply(this);
            setContentView(R.layout.activity_main);

            setupEdgeToEdgeInsets();
            setupStatusBarColors();
            setupVersionNumber();

            SentryManager.logEvent("lifecycle", "The MainActivity was created.");

            // Tells Play vitals / Macrobenchmark when the first screen is usable.
            getWindow().getDecorView().post(this::reportFullyDrawn);

            // RedactApplication.onCreate already runs this sweep once per process; this call
            // only matters when MainActivity is recreated without a fresh process (e.g. after a
            // configuration change) and isAnyProcessing requires a live Activity to check, so it
            // can't move into the Application-level call.
            if (!com.doubleangels.redact.ui.MainViewModel.isAnyProcessing(this)) {
                com.doubleangels.redact.CacheCleanup.scheduleAutoCleanupIfEnabled(this);
            }

            if (savedInstanceState == null) {
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment_container, new CleanFragment(), TAG_CLEAN)
                        .commitNow();
            }

            BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
            bottomNavigationView.setOnItemSelectedListener(item -> {
                try {
                    int itemId = item.getItemId();
                    SentryManager.logEvent("navigation", "The user selected a tab.");
                    Fragment target = ensureFragmentForTab(itemId);
                    if (target == null) {
                        return false;
                    }
                    FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                    for (String tag : new String[] {TAG_CLEAN, TAG_SCAN, TAG_CONVERT, TAG_SETTINGS}) {
                        Fragment f = getSupportFragmentManager().findFragmentByTag(tag);
                        if (f != null) {
                            if (f == target) {
                                ft.show(f);
                            } else {
                                ft.hide(f);
                            }
                        }
                    }
                    ft.commit();
                    return true;
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
                return false;
            });

            if (savedInstanceState != null) {
                int restoredTab = savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.navigation_clean);
                bottomNavigationView.setSelectedItemId(restoredTab);
                restoreTabVisibility(restoredTab);
            } else {
                bottomNavigationView.setSelectedItemId(
                        queueConvertShareIntent(getIntent())
                                ? R.id.navigation_convert
                                : queueViewIntent(getIntent())
                                        ? R.id.navigation_scan
                                        : tabFromIntent(getIntent()));
            }

            SentryManager.setCustomKey("app_started", true);

            com.doubleangels.redact.permission.PermissionManager.setInitialFlowCompletedCallback(() -> {
                notifyVisibleFragmentPermissionFlowCompleted();
                com.doubleangels.redact.ui.CrashReportingPrompt.onFirstStartPermissionsDone(this);
            });
            if (com.doubleangels.redact.permission.PermissionManager.isInitialPermissionFlowInProgress()) {
                com.doubleangels.redact.permission.PermissionManager.startInitialPermissionFlow(this);
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    protected void onNewIntent(@NonNull android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (queueConvertShareIntent(intent)) {
            selectTab(R.id.navigation_convert);
        } else if (queueViewIntent(intent)) {
            selectTab(R.id.navigation_scan);
        } else if (intent.hasExtra(EXTRA_TAB)) {
            selectTab(tabFromIntent(intent));
        }
    }

    /**
     * Selects shared images/videos in Convert when they were shared to "Convert with Redact"
     * (ACTION_SEND or ACTION_SEND_MULTIPLE). Returns whether the share was for Convert.
     */
    private boolean queueConvertShareIntent(@Nullable android.content.Intent intent) {
        if (intent == null || intent.getComponent() == null
                || !intent.getComponent().getClassName().endsWith(CONVERT_SHARE_TARGET)) {
            return false;
        }
        java.util.List<android.net.Uri> shared = new java.util.ArrayList<>();
        if (android.content.Intent.ACTION_SEND.equals(intent.getAction())) {
            android.net.Uri uri = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, android.content.Intent.EXTRA_STREAM, android.net.Uri.class);
            if (uri != null) {
                shared.add(uri);
            }
        } else if (android.content.Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            java.util.List<android.net.Uri> uris = androidx.core.content.IntentCompat
                    .getParcelableArrayListExtra(
                            intent, android.content.Intent.EXTRA_STREAM, android.net.Uri.class);
            if (uris != null) {
                shared.addAll(uris);
            }
        } else {
            return false;
        }
        java.util.List<android.net.Uri> media = new java.util.ArrayList<>();
        for (android.net.Uri uri : shared) {
            if (isForeignContentUri(uri) && isImageOrVideo(uri, intent.getType())) {
                media.add(uri);
            }
        }
        if (media.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.share_error_unsupported_media,
                    android.widget.Toast.LENGTH_LONG).show();
            return true;
        }
        if (media.size() > MAX_CONVERT_SHARE_ITEMS) {
            android.widget.Toast.makeText(this,
                    getString(R.string.media_picker_selection_capped, MAX_CONVERT_SHARE_ITEMS),
                    android.widget.Toast.LENGTH_LONG).show();
            media = com.doubleangels.redact.media.MediaPickerContracts.trimToMax(
                    media, MAX_CONVERT_SHARE_ITEMS);
        }
        com.doubleangels.redact.ui.MainViewModel viewModel = new androidx.lifecycle.ViewModelProvider(this)
                .get(com.doubleangels.redact.ui.MainViewModel.class);
        if (viewModel.getConvertProcessingState().getValue()
                == com.doubleangels.redact.ui.MainViewModel.ProcessingState.PROCESSING) {
            // Don't swap the files out from under a running convert; just show its progress.
            return true;
        }
        SentryManager.logEvent("share", "Media was shared to Convert.");
        new com.doubleangels.redact.media.MediaSelector(this)
                .processMediaUrisAsync(media, viewModel::setConvertSelectedItems);
        return true;
    }

    private boolean isImageOrVideo(@NonNull android.net.Uri uri, @Nullable String intentType) {
        String type;
        try {
            type = getContentResolver().getType(uri);
        } catch (Exception e) {
            SentryManager.recordException(e);
            type = null;
        }
        if (type == null) {
            type = intentType;
        }
        return type != null && (type.startsWith("image/") || type.startsWith("video/"));
    }

    /**
     * Queues an image/video for Scan when it was opened from another app (ACTION_VIEW) or shared
     * to "Scan with Redact" (ACTION_SEND). Returns whether one was queued.
     */
    private boolean queueViewIntent(@Nullable android.content.Intent intent) {
        if (intent == null) {
            return false;
        }
        android.net.Uri data;
        if (android.content.Intent.ACTION_VIEW.equals(intent.getAction())) {
            data = intent.getData();
        } else if (android.content.Intent.ACTION_SEND.equals(intent.getAction())) {
            data = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent, android.content.Intent.EXTRA_STREAM, android.net.Uri.class);
        } else {
            return false;
        }
        if (!isForeignContentUri(data)) {
            return false;
        }
        try {
            String type = getContentResolver().getType(data);
            if (type == null) {
                type = intent.getType();
            }
            if (type == null || !(type.startsWith("image/") || type.startsWith("video/"))) {
                return false;
            }
            new androidx.lifecycle.ViewModelProvider(this)
                    .get(com.doubleangels.redact.ui.ScanViewModel.class).setPendingUri(data);
            return true;
        } catch (Exception e) {
            SentryManager.recordException(e);
            return false;
        }
    }

    /**
     * Only content from another app's provider: a file:// path or one of Redact's own providers
     * would let the sender point Scan at files that are private to Redact.
     */
    private boolean isForeignContentUri(@Nullable android.net.Uri uri) {
        if (uri == null || !android.content.ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            return false;
        }
        String authority = uri.getAuthority();
        return authority != null && !authority.startsWith(getPackageName());
    }

    private static int tabFromIntent(@Nullable android.content.Intent intent) {
        String tab = intent == null ? null : intent.getStringExtra(EXTRA_TAB);
        if (TAG_SCAN.equals(tab)) {
            return R.id.navigation_scan;
        }
        return TAG_CONVERT.equals(tab) ? R.id.navigation_convert : R.id.navigation_clean;
    }

    /** Switches bottom navigation and shows the tab fragment. */
    public void selectTab(int navigationItemId) {
        BottomNavigationView nav = findViewById(R.id.bottomNavigation);
        if (nav != null) {
            nav.setSelectedItemId(navigationItemId);
        }
    }

    @Nullable
    private Fragment ensureFragmentForTab(int itemId) {
        String tag;
        Fragment newFragment;
        if (itemId == R.id.navigation_scan) {
            tag = TAG_SCAN;
            newFragment = new ScanFragment();
        } else if (itemId == R.id.navigation_convert) {
            tag = TAG_CONVERT;
            newFragment = new ConvertFragment();
        } else if (itemId == R.id.navigation_settings) {
            tag = TAG_SETTINGS;
            newFragment = new SettingsFragment();
        } else if (itemId == R.id.navigation_clean) {
            tag = TAG_CLEAN;
            newFragment = new CleanFragment();
        } else {
            return null;
        }
        Fragment existing = getSupportFragmentManager().findFragmentByTag(tag);
        if (existing != null) {
            return existing;
        }
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, newFragment, tag)
                .hide(newFragment)
                .commitNow();
        return getSupportFragmentManager().findFragmentByTag(tag);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        BottomNavigationView nav = findViewById(R.id.bottomNavigation);
        if (nav != null) {
            outState.putInt(KEY_SELECTED_TAB, nav.getSelectedItemId());
        }
    }

    private void setupEdgeToEdgeInsets() {
        try {
            View rootView = findViewById(android.R.id.content);
            if (rootView != null) {
                final int barsAndCutout = WindowInsetsCompat.Type.systemBars()
                        | WindowInsetsCompat.Type.displayCutout();
                ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                    androidx.core.graphics.Insets outer = insets.getInsets(barsAndCutout);
                    androidx.core.graphics.Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
                    androidx.core.graphics.Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
                    int bottomOverlap = Math.max(nav.bottom, ime.bottom);

                    View coordinatorLayout = findViewById(R.id.root_layout);
                    if (coordinatorLayout != null) {
                        coordinatorLayout.setPadding(
                                outer.left,
                                outer.top,
                                outer.right,
                                0
                        );
                    }

                    BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
                    if (bottomNav != null) {
                        bottomNav.setPadding(
                                bottomNav.getPaddingLeft(),
                                bottomNav.getPaddingTop(),
                                bottomNav.getPaddingRight(),
                                bottomOverlap
                        );
                    }

                    return insets;
                });
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private void setupStatusBarColors() {
        try {
            int nightModeFlags = getResources().getConfiguration().uiMode &
                    Configuration.UI_MODE_NIGHT_MASK;

            WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null) {
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    insetsController.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                    SentryManager.setCustomKey("theme_mode", "dark");
                } else {
                    insetsController.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                    SentryManager.setCustomKey("theme_mode", "light");
                }
            } else {
                SentryManager.logEvent("ui", "The insets controller is null.");
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private void setupVersionNumber() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            assert packageInfo.versionName != null;
            SentryManager.setCustomKey("app_version", packageInfo.versionName);
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private void restoreTabVisibility(int selectedItemId) {
        Fragment target = ensureFragmentForTab(selectedItemId);
        if (target == null) {
            return;
        }
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        for (String tag : new String[] {TAG_CLEAN, TAG_SCAN, TAG_CONVERT, TAG_SETTINGS}) {
            Fragment f = getSupportFragmentManager().findFragmentByTag(tag);
            if (f != null) {
                if (f == target) {
                    ft.show(f);
                } else {
                    ft.hide(f);
                }
            }
        }
        ft.commit();
    }

    @Nullable
    private Fragment resolvePermissionResultTarget(int requestCode) {
        if (requestCode
                == com.doubleangels.redact.permission.PermissionManager.LOCATION_PERMISSION_REQUEST_CODE) {
            return getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
        }
        if (requestCode
                == com.doubleangels.redact.permission.PermissionManager.NOTIFICATION_PERMISSION_REQUEST_CODE) {
            return getSupportFragmentManager().findFragmentByTag(TAG_SETTINGS);
        }
        if (requestCode
                == com.doubleangels.redact.permission.PermissionManager.STORAGE_PERMISSION_REQUEST_CODE) {
            return getVisibleTabFragment();
        }
        return null;
    }

    private void notifyVisibleFragmentPermissionFlowCompleted() {
        Fragment target = getVisibleTabFragment();
        if (target instanceof CleanFragment cleanFragment) {
            cleanFragment.onHostPermissionFlowCompleted();
        } else if (target instanceof ScanFragment scanFragment) {
            scanFragment.onHostPermissionFlowCompleted();
        } else if (target instanceof ConvertFragment convertFragment) {
            convertFragment.onHostPermissionFlowCompleted();
        }
    }

    @Nullable
    private Fragment getVisibleTabFragment() {
        BottomNavigationView nav = findViewById(R.id.bottomNavigation);
        if (nav == null) {
            return null;
        }
        int selectedItemId = nav.getSelectedItemId();
        String tag;
        if (selectedItemId == R.id.navigation_scan) {
            tag = TAG_SCAN;
        } else if (selectedItemId == R.id.navigation_convert) {
            tag = TAG_CONVERT;
        } else if (selectedItemId == R.id.navigation_settings) {
            tag = TAG_SETTINGS;
        } else {
            tag = TAG_CLEAN;
        }
        return getSupportFragmentManager().findFragmentByTag(tag);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        try {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            SentryManager.logEvent("permission", "The app received a permission result.");
            SentryManager.setCustomKey("permission_request_code", requestCode);

            com.doubleangels.redact.permission.PermissionManager.storeActivityPermissionResult(
                    requestCode, permissions, grantResults);

            if (com.doubleangels.redact.permission.PermissionManager.handleInitialPermissionFlowResult(
                    this, requestCode, permissions, grantResults)) {
                return;
            }

            Fragment target = resolvePermissionResultTarget(requestCode);
            if (target instanceof CleanFragment cleanFragment) {
                cleanFragment.handlePermissionResult(requestCode, permissions, grantResults);
            } else if (target instanceof ScanFragment scanFragment) {
                scanFragment.handlePermissionResult(requestCode, permissions, grantResults);
            } else if (target instanceof ConvertFragment convertFragment) {
                convertFragment.handlePermissionResult(requestCode, permissions, grantResults);
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    protected void onResume() {
        try {
            super.onResume();
            SentryManager.logEvent("lifecycle", "The MainActivity resumed.");
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    protected void onPause() {
        try {
            super.onPause();
            SentryManager.logEvent("lifecycle", "The MainActivity paused.");
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }
}
