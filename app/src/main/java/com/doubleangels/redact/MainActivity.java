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

    private static final String TAG_CLEAN = "clean";
    private static final String TAG_SCAN = "scan";
    private static final String TAG_CONVERT = "convert";
    private static final String TAG_SETTINGS = "settings";
    private static final String KEY_SELECTED_TAB = "selected_tab";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            DynamicColors.applyToActivityIfAvailable(this);
            EdgeToEdge.enable(this);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            setupEdgeToEdgeInsets();
            setupStatusBarColors();
            setupVersionNumber();

            SentryManager.logEvent("lifecycle", "MainActivity created");

            if (AppPreferences.isAutoClearTempFiles(this)) {
                com.doubleangels.redact.CacheCleanup.clearAllTempFiles(this);
            }

            if (savedInstanceState == null) {
                com.doubleangels.redact.permission.PermissionManager.requestAllInitialPermissions(this);
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment_container, new CleanFragment(), TAG_CLEAN)
                        .commit();
            }

            BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
            bottomNavigationView.setOnItemSelectedListener(item -> {
                try {
                    int itemId = item.getItemId();
                    SentryManager.logEvent("navigation", "Tab selected");
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
                bottomNavigationView.setSelectedItemId(
                        savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.navigation_clean));
            } else {
                bottomNavigationView.setSelectedItemId(R.id.navigation_clean);
            }

            SentryManager.setCustomKey("app_started", true);
        } catch (Exception e) {
            SentryManager.recordException(e);
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
                SentryManager.logEvent("ui", "Insets controller is null");
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    private void setupVersionNumber() {
        try {
            android.widget.TextView versionText = findViewById(R.id.versionText);
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionText.setText(packageInfo.versionName);
            assert packageInfo.versionName != null;
            SentryManager.setCustomKey("app_version", packageInfo.versionName);
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        try {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            SentryManager.logEvent("permission", "Permission result received");
            SentryManager.setCustomKey("permission_request_code", requestCode);

            Fragment scan = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
            Fragment clean = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
            Fragment convert = getSupportFragmentManager().findFragmentByTag(TAG_CONVERT);

            for (int i = 0; i < permissions.length; i++) {
                if (android.Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])) {
                    if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                        AppPreferences.setNotificationsEnabled(this, true);
                    }
                }
            }

            if (convert != null && convert.isVisible()) {
                ((ConvertFragment) convert).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (scan != null && scan.isVisible()) {
                ((ScanFragment) scan).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (clean != null && clean.isVisible()) {
                ((CleanFragment) clean).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (convert != null) {
                ((ConvertFragment) convert).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (scan != null) {
                ((ScanFragment) scan).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (clean != null) {
                ((CleanFragment) clean).handlePermissionResult(requestCode, permissions, grantResults);
            }
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    protected void onResume() {
        try {
            super.onResume();
            SentryManager.logEvent("lifecycle", "MainActivity resumed");
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    protected void onPause() {
        try {
            super.onPause();
            SentryManager.logEvent("lifecycle", "MainActivity paused");
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }
}
