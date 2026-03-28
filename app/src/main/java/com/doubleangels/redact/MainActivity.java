package com.doubleangels.redact;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.doubleangels.redact.sentry.SentryManager;

/**
 * Single host activity: version line and bottom navigation stay fixed;
 * {@link CleanFragment}, {@link ScanFragment}, and {@link ConvertFragment} swap above.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG_CLEAN = "clean";
    private static final String TAG_SCAN = "scan";
    private static final String TAG_CONVERT = "convert";
    private static final String KEY_SELECTED_TAB = "selected_tab";
    private static final String PREFS_NAME = "redact_prefs";
    private static final String KEY_POST_NOTIFICATIONS_PROMPTED = "post_notifications_prompted";

    private ActivityResultLauncher<String> notificationPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        notificationPermissionLauncher =
                registerForActivityResult(
                        new ActivityResultContracts.RequestPermission(),
                        granted ->
                                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                        .edit()
                                        .putBoolean(KEY_POST_NOTIFICATIONS_PROMPTED, true)
                                        .apply());
        try {
            DynamicColors.applyToActivityIfAvailable(this);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            maybeRequestNotificationPermission();

            setupEdgeToEdgeInsets();
            setupStatusBarColors();
            setupVersionNumber();

            SentryManager.log("MainActivity created");

            if (savedInstanceState == null) {
                ScanFragment scanFrag = new ScanFragment();
                ConvertFragment convertFrag = new ConvertFragment();
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment_container, new CleanFragment(), TAG_CLEAN)
                        .add(R.id.fragment_container, scanFrag, TAG_SCAN)
                        .add(R.id.fragment_container, convertFrag, TAG_CONVERT)
                        .hide(scanFrag)
                        .hide(convertFrag)
                        .commit();
            } else {
                int tab = savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.navigation_clean);
                Fragment c = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
                Fragment s = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
                Fragment cv = getSupportFragmentManager().findFragmentByTag(TAG_CONVERT);
                if (cv == null) {
                    getSupportFragmentManager().beginTransaction()
                            .add(R.id.fragment_container, new ConvertFragment(), TAG_CONVERT)
                            .commit();
                }
                getSupportFragmentManager().executePendingTransactions();
                c = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
                s = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
                cv = getSupportFragmentManager().findFragmentByTag(TAG_CONVERT);
                if (c != null && s != null && cv != null) {
                    FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                    ft.hide(c).hide(s).hide(cv);
                    if (tab == R.id.navigation_scan) {
                        ft.show(s);
                    } else if (tab == R.id.navigation_convert) {
                        ft.show(cv);
                    } else {
                        ft.show(c);
                    }
                    ft.commit();
                }
            }
            getSupportFragmentManager().executePendingTransactions();

            BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
            bottomNavigationView.setOnItemSelectedListener(item -> {
                try {
                    int itemId = item.getItemId();
                    SentryManager.log("Bottom navigation item selected: " + itemId);
                    Fragment clean = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
                    Fragment scan = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
                    Fragment convert = getSupportFragmentManager().findFragmentByTag(TAG_CONVERT);
                    if (clean == null || scan == null || convert == null) {
                        return false;
                    }
                    FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                    ft.hide(clean).hide(scan).hide(convert);
                    if (itemId == R.id.navigation_clean) {
                        ft.show(clean);
                    } else if (itemId == R.id.navigation_scan) {
                        ft.show(scan);
                    } else if (itemId == R.id.navigation_convert) {
                        ft.show(convert);
                    } else {
                        return false;
                    }
                    ft.commit();
                    return true;
                } catch (Exception e) {
                    SentryManager.recordException(e);
                }
                return false;
            });

            if (savedInstanceState == null) {
                bottomNavigationView.setSelectedItemId(R.id.navigation_clean);
            } else {
                bottomNavigationView.setSelectedItemId(
                        savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.navigation_clean));
            }

            SentryManager.setCustomKey("app_started", true);
        } catch (Exception e) {
            SentryManager.recordException(e);
            try {
                setContentView(R.layout.activity_main);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * One-time prompt for {@link Manifest.permission#POST_NOTIFICATIONS} on API 33+ so local
     * completion notifications can be shown.
     */
    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationPermissionLauncher == null) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_POST_NOTIFICATIONS_PROMPTED, false)) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean(KEY_POST_NOTIFICATIONS_PROMPTED, true).apply();
            return;
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
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
                ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, insets) -> {
                    androidx.core.graphics.Insets systemBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    androidx.core.graphics.Insets statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars());
                    androidx.core.graphics.Insets navigationBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars());

                    View coordinatorLayout = findViewById(R.id.root_layout);
                    if (coordinatorLayout != null) {
                        coordinatorLayout.setPadding(
                                systemBarInsets.left,
                                statusBarInsets.top,
                                systemBarInsets.right,
                                0
                        );
                    }

                    BottomNavigationView bottomNav = findViewById(R.id.bottomNavigation);
                    if (bottomNav != null) {
                        bottomNav.setPadding(
                                bottomNav.getPaddingLeft(),
                                bottomNav.getPaddingTop(),
                                bottomNav.getPaddingRight(),
                                navigationBarInsets.bottom
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
                    insetsController.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    SentryManager.setCustomKey("theme_mode", "dark");
                } else {
                    insetsController.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    SentryManager.setCustomKey("theme_mode", "light");
                }
            } else {
                SentryManager.log("Insets controller is null");
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
            SentryManager.log("Permission result received");
            SentryManager.setCustomKey("permission_request_code", requestCode);

            Fragment scan = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
            Fragment clean = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
            Fragment convert = getSupportFragmentManager().findFragmentByTag(TAG_CONVERT);

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
            SentryManager.log("MainActivity resumed");
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    protected void onPause() {
        try {
            super.onPause();
            SentryManager.log("MainActivity paused");
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }
}
