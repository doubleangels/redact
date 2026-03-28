package com.doubleangels.redact;

import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Single host activity: version line and bottom navigation stay fixed; {@link CleanFragment}
 * and {@link ScanFragment} swap in the area above.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG_CLEAN = "clean";
    private static final String TAG_SCAN = "scan";
    private static final String KEY_SELECTED_TAB = "selected_tab";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        try {
            DynamicColors.applyToActivityIfAvailable(this);
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

            super.onCreate(savedInstanceState);
            setContentView(R.layout.activity_main);

            setupEdgeToEdgeInsets();
            setupStatusBarColors();
            setupVersionNumber();

            FirebaseCrashlytics.getInstance().log("MainActivity created");

            if (savedInstanceState == null) {
                ScanFragment scanFrag = new ScanFragment();
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment_container, new CleanFragment(), TAG_CLEAN)
                        .add(R.id.fragment_container, scanFrag, TAG_SCAN)
                        .hide(scanFrag)
                        .commit();
            } else {
                int tab = savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.navigation_clean);
                Fragment c = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
                Fragment s = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
                if (c != null && s != null) {
                    FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                    if (tab == R.id.navigation_scan) {
                        ft.show(s).hide(c);
                    } else {
                        ft.show(c).hide(s);
                    }
                    ft.commit();
                }
            }
            getSupportFragmentManager().executePendingTransactions();

            BottomNavigationView bottomNavigationView = findViewById(R.id.bottomNavigation);
            bottomNavigationView.setOnItemSelectedListener(item -> {
                try {
                    int itemId = item.getItemId();
                    FirebaseCrashlytics.getInstance().log("Bottom navigation item selected: " + itemId);
                    Fragment clean = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
                    Fragment scan = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
                    if (clean == null || scan == null) {
                        return false;
                    }
                    FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                    if (itemId == R.id.navigation_clean) {
                        ft.show(clean).hide(scan);
                    } else if (itemId == R.id.navigation_scan) {
                        ft.show(scan).hide(clean);
                    } else {
                        return false;
                    }
                    ft.commit();
                    return true;
                } catch (Exception e) {
                    FirebaseCrashlytics.getInstance().recordException(e);
                }
                return false;
            });

            if (savedInstanceState == null) {
                bottomNavigationView.setSelectedItemId(R.id.navigation_clean);
            } else {
                bottomNavigationView.setSelectedItemId(
                        savedInstanceState.getInt(KEY_SELECTED_TAB, R.id.navigation_clean));
            }

            FirebaseCrashlytics.getInstance().setCustomKey("app_started", true);
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
            try {
                setContentView(R.layout.activity_main);
            } catch (Exception ignored) {
            }
        }
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
            FirebaseCrashlytics.getInstance().recordException(e);
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
                    FirebaseCrashlytics.getInstance().setCustomKey("theme_mode", "dark");
                } else {
                    insetsController.setSystemBarsAppearance(
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                    FirebaseCrashlytics.getInstance().setCustomKey("theme_mode", "light");
                }
            } else {
                FirebaseCrashlytics.getInstance().log("Insets controller is null");
            }
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    private void setupVersionNumber() {
        try {
            android.widget.TextView versionText = findViewById(R.id.versionText);
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            versionText.setText(packageInfo.versionName);
            assert packageInfo.versionName != null;
            FirebaseCrashlytics.getInstance().setCustomKey("app_version", packageInfo.versionName);
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        try {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            FirebaseCrashlytics.getInstance().log("Permission result received");
            FirebaseCrashlytics.getInstance().setCustomKey("permission_request_code", requestCode);

            Fragment scan = getSupportFragmentManager().findFragmentByTag(TAG_SCAN);
            Fragment clean = getSupportFragmentManager().findFragmentByTag(TAG_CLEAN);
            if (scan != null && scan.isVisible()) {
                ((ScanFragment) scan).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (clean != null && clean.isVisible()) {
                ((CleanFragment) clean).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (scan != null) {
                ((ScanFragment) scan).handlePermissionResult(requestCode, permissions, grantResults);
            } else if (clean != null) {
                ((CleanFragment) clean).handlePermissionResult(requestCode, permissions, grantResults);
            }
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    @Override
    protected void onResume() {
        try {
            super.onResume();
            FirebaseCrashlytics.getInstance().log("MainActivity resumed");
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }

    @Override
    protected void onPause() {
        try {
            super.onPause();
            FirebaseCrashlytics.getInstance().log("MainActivity paused");
        } catch (Exception e) {
            FirebaseCrashlytics.getInstance().recordException(e);
        }
    }
}
