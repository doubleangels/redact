package com.doubleangels.redact;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.doubleangels.redact.permission.PermissionStatusHelper;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings screen for notifications, conversion defaults, privacy, permissions, and about info.
 */
public class SettingsFragment extends Fragment {

    private boolean suppressNotificationToggleCallback;

    private MaterialSwitch switchNotifications;
    private MaterialSwitch switchClean;
    private MaterialSwitch switchConvert;
    private MaterialSwitch switchProgress;
    private MaterialSwitch switchCrashReporting;
    private MaterialSwitch switchShareConfirm;
    private View rowClean;
    private View rowConvert;
    private View rowProgress;
    private TextView textPermissionMediaStatus;
    private TextView textPermissionNotificationsStatus;
    private TextView textStorageSize;
    private MaterialAutoCompleteTextView dropdownDefaultImageFormat;
    private MaterialAutoCompleteTextView dropdownDefaultVideoFormat;
    private MaterialAutoCompleteTextView dropdownImageQuality;

    private String[] imageFormatLabels;
    private String[] videoFormatLabels;
    private String[] qualityLabels;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        try {
            bindViews(view);
            imageFormatLabels = getResources().getStringArray(R.array.settings_image_format_labels);
            videoFormatLabels = getResources().getStringArray(R.array.settings_video_format_labels);
            qualityLabels = getResources().getStringArray(R.array.settings_quality_labels);

            bindPermissions(view);
            bindConversionDefaults();
            bindShareTarget();
            bindNotifications();
            bindStorage(view);
            bindPrivacy(view);
            bindAbout(view);
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPermissionStatuses();
        refreshStorageSize();
    }

    @Override
    public void onDestroyView() {
        backgroundExecutor.shutdownNow();
        super.onDestroyView();
    }

    private void bindViews(@NonNull View view) {
        switchNotifications = view.findViewById(R.id.switchNotifications);
        switchClean = view.findViewById(R.id.switchCleanNotifications);
        switchConvert = view.findViewById(R.id.switchConvertNotifications);
        switchProgress = view.findViewById(R.id.switchProgressNotifications);
        switchCrashReporting = view.findViewById(R.id.switchCrashReporting);
        switchShareConfirm = view.findViewById(R.id.switchShareConfirm);
        rowClean = view.findViewById(R.id.rowCleanNotifications);
        rowConvert = view.findViewById(R.id.rowConvertNotifications);
        rowProgress = view.findViewById(R.id.rowProgressNotifications);
        textPermissionMediaStatus = view.findViewById(R.id.textPermissionMediaStatus);
        textPermissionNotificationsStatus = view.findViewById(R.id.textPermissionNotificationsStatus);
        textStorageSize = view.findViewById(R.id.textStorageSize);
        dropdownDefaultImageFormat = view.findViewById(R.id.dropdownDefaultImageFormat);
        dropdownDefaultVideoFormat = view.findViewById(R.id.dropdownDefaultVideoFormat);
        dropdownImageQuality = view.findViewById(R.id.dropdownImageQuality);
    }

    private void bindNotifications() {
        boolean masterOn = AppPreferences.areNotificationsEnabled(requireContext());
        switchNotifications.setChecked(masterOn);
        switchClean.setChecked(AppPreferences.areCleanNotificationsEnabled(requireContext()));
        switchConvert.setChecked(AppPreferences.areConvertNotificationsEnabled(requireContext()));
        switchProgress.setChecked(AppPreferences.areProgressNotificationsEnabled(requireContext()));
        setNotificationSubRowsEnabled(masterOn);

        switchNotifications.setOnCheckedChangeListener((btn, isChecked) -> {
            if (suppressNotificationToggleCallback) {
                return;
            }
            try {
                if (!isChecked) {
                    applyNotificationsEnabled(false);
                    SentryManager.setCustomKey("notifications_enabled", false);
                    return;
                }
                if (needsNotificationPermission() && !hasNotificationPermission()) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
                    return;
                }
                applyNotificationsEnabled(true);
                SentryManager.setCustomKey("notifications_enabled", true);
            } catch (Exception e) {
                SentryManager.recordException(e);
            }
        });

        switchClean.setOnCheckedChangeListener((btn, isChecked) -> {
            AppPreferences.setCleanNotificationsEnabled(requireContext(), isChecked);
            SentryManager.setCustomKey("clean_notifications_enabled", isChecked);
        });

        switchConvert.setOnCheckedChangeListener((btn, isChecked) -> {
            AppPreferences.setConvertNotificationsEnabled(requireContext(), isChecked);
            SentryManager.setCustomKey("convert_notifications_enabled", isChecked);
        });

        switchProgress.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setProgressNotificationsEnabled(requireContext(), isChecked));
    }

    private void bindPrivacy(@NonNull View view) {
        switchCrashReporting.setChecked(AppPreferences.isCrashReportingEnabled(requireContext()));
        switchCrashReporting.setOnCheckedChangeListener((btn, isChecked) -> {
            try {
                AppPreferences.setCrashReportingEnabled(requireContext(), isChecked);
            } catch (Exception e) {
                android.util.Log.e("SettingsFragment", "Error saving crash reporting pref", e);
            }
        });

        MaterialButton learnMore = view.findViewById(R.id.buttonCrashReportingLearnMore);
        learnMore.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_crash_reporting_detail_title)
                .setMessage(R.string.settings_crash_reporting_detail_message)
                .setPositiveButton(R.string.settings_crash_reporting_privacy_policy, (d, w) ->
                        openUrl(getString(R.string.url_privacy_policy)))
                .setNegativeButton(R.string.settings_crash_reporting_dismiss, null)
                .show());
    }

    private void bindConversionDefaults() {
        String[] imageLabels = imageFormatLabels;
        int imageIndex = AppPreferences.getDefaultImageFormatIndex(requireContext());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            imageLabels = new String[]{
                    imageFormatLabels[0], imageFormatLabels[1], imageFormatLabels[2]};
            imageIndex = Math.min(imageIndex, 2);
        }
        setupDropdown(dropdownDefaultImageFormat, imageLabels, imageIndex,
                index -> AppPreferences.setDefaultImageFormatIndex(requireContext(), index));

        setupDropdown(dropdownDefaultVideoFormat, videoFormatLabels,
                AppPreferences.getDefaultVideoFormatIndex(requireContext()),
                index -> AppPreferences.setDefaultVideoFormatIndex(requireContext(), index));

        setupDropdown(dropdownImageQuality, qualityLabels,
                AppPreferences.getImageQualityPreset(requireContext()),
                index -> AppPreferences.setImageQualityPreset(requireContext(), index));
    }

    private void bindShareTarget() {
        switchShareConfirm.setChecked(AppPreferences.isShareConfirmBeforeStrip(requireContext()));
        switchShareConfirm.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setShareConfirmBeforeStrip(requireContext(), isChecked));
    }

    private void bindPermissions(@NonNull View view) {
        MaterialButton openSettings = view.findViewById(R.id.buttonOpenSystemSettings);
        openSettings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
            startActivity(intent);
        });
        refreshPermissionStatuses();
    }

    private void bindStorage(@NonNull View view) {
        MaterialButton clearButton = view.findViewById(R.id.buttonClearTempFiles);
        clearButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_storage_clear_confirm_title)
                .setMessage(R.string.settings_storage_clear_confirm_message)
                .setPositiveButton(R.string.settings_storage_clear, (d, w) -> clearTempFiles())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        refreshStorageSize();
    }

    private void bindAbout(@NonNull View view) {
        TextView version = view.findViewById(R.id.textAboutVersion);
        version.setText(getString(R.string.settings_about_version, BuildConfig.VERSION_NAME));

        view.findViewById(R.id.rowAboutGithub).setOnClickListener(v ->
                openUrl(getString(R.string.url_github)));
        view.findViewById(R.id.rowAboutPrivacy).setOnClickListener(v ->
                openUrl(getString(R.string.url_privacy_policy)));
        view.findViewById(R.id.rowAboutReportIssue).setOnClickListener(v ->
                openUrl(getString(R.string.url_report_issue)));
    }

    private void setupDropdown(@NonNull MaterialAutoCompleteTextView dropdown,
                               @NonNull String[] labels,
                               int selectedIndex,
                               @NonNull IndexConsumer onSelected) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                labels);
        dropdown.setAdapter(adapter);
        int clamped = Math.min(selectedIndex, labels.length - 1);
        dropdown.setText(labels[clamped], false);
        dropdown.setOnItemClickListener((parent, view, position, id) -> onSelected.accept(position));
    }

    private void refreshPermissionStatuses() {
        if (textPermissionMediaStatus == null) {
            return;
        }
        applyPermissionStatus(
                textPermissionMediaStatus,
                PermissionStatusHelper.getMediaAccessStatus(requireContext()));
        applyPermissionStatus(
                textPermissionNotificationsStatus,
                PermissionStatusHelper.getNotificationsStatus(requireContext()));
    }

    private void applyPermissionStatus(
            @NonNull TextView statusView,
            @NonNull PermissionStatusHelper.Status status) {
        statusView.setText(getString(statusLabel(status)));
        int color = switch (status) {
            case GRANTED -> ContextCompat.getColor(requireContext(), R.color.accent);
            case DENIED -> ContextCompat.getColor(requireContext(), R.color.permission_status_denied);
            case NOT_REQUIRED -> MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(requireContext(), R.color.black));
        };
        statusView.setTextColor(color);
    }

    private int statusLabel(@NonNull PermissionStatusHelper.Status status) {
        return switch (status) {
            case GRANTED -> R.string.settings_permission_granted;
            case DENIED -> R.string.settings_permission_denied;
            case NOT_REQUIRED -> R.string.settings_permission_not_required;
        };
    }

    private void refreshStorageSize() {
        if (textStorageSize == null) {
            return;
        }
        backgroundExecutor.execute(() -> {
            long bytes = CacheCleanup.getTempCacheSizeBytes(requireContext());
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> textStorageSize.setText(
                    getString(R.string.settings_storage_size, CacheCleanup.formatSize(bytes))));
        });
    }

    private void clearTempFiles() {
        backgroundExecutor.execute(() -> {
            long before = CacheCleanup.getTempCacheSizeBytes(requireContext());
            int deleted = CacheCleanup.clearAllTempFiles(requireContext());
            if (!isAdded()) {
                return;
            }
            requireActivity().runOnUiThread(() -> {
                refreshStorageSize();
                if (before == 0 || deleted == 0) {
                    Toast.makeText(requireContext(), R.string.settings_storage_clear_empty, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), R.string.settings_storage_clear_success, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void openUrl(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            applyNotificationsEnabled(true);
                            SentryManager.setCustomKey("notifications_enabled", true);
                            Toast.makeText(
                                            requireContext(),
                                            R.string.settings_notifications_permission_granted,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        } else {
                            suppressNotificationToggleCallback = true;
                            switchNotifications.setChecked(false);
                            suppressNotificationToggleCallback = false;
                            applyNotificationsEnabled(false);
                            Toast.makeText(
                                            requireContext(),
                                            R.string.settings_notifications_permission_denied,
                                            Toast.LENGTH_SHORT)
                                    .show();
                        }
                    });

    private void applyNotificationsEnabled(boolean enabled) {
        AppPreferences.setNotificationsEnabled(requireContext(), enabled);
        setNotificationSubRowsEnabled(enabled);
    }

    private void setNotificationSubRowsEnabled(boolean enabled) {
        float alpha = enabled ? 1f : 0.38f;
        rowClean.setAlpha(alpha);
        rowConvert.setAlpha(alpha);
        rowProgress.setAlpha(alpha);
        switchClean.setEnabled(enabled);
        switchConvert.setEnabled(enabled);
        switchProgress.setEnabled(enabled);
    }

    private boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private boolean hasNotificationPermission() {
        if (!needsNotificationPermission()) {
            return true;
        }
        return ContextCompat.checkSelfPermission(
                        requireContext(), android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

  /** Applies saved default format index to Convert tab chips. */
    public static int defaultFormatIndexForSelection(@NonNull android.content.Context context,
                                                     int numImages, int numVideos) {
        if (numVideos == 0) {
            return AppPreferences.getDefaultImageFormatIndex(context);
        }
        if (numImages == 0) {
            return AppPreferences.getDefaultVideoFormatIndex(context);
        }
        return AppPreferences.getDefaultImageFormatIndex(context);
    }

    public static int chipIdForFormatIndex(int index) {
        return switch (index) {
            case 1 -> R.id.chipFormatPng;
            case 2 -> R.id.chipFormatWebp;
            case 3 -> R.id.chipFormatHeif;
            default -> R.id.chipFormatJpeg;
        };
    }

    @FunctionalInterface
    private interface IndexConsumer {
        void accept(int index);
    }
}
