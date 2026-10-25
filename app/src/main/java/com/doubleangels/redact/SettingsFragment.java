package com.doubleangels.redact;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Filter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.content.ContextCompat;

import com.doubleangels.redact.ShareHandlerActivity;
import com.doubleangels.redact.media.OutputDestination;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.permission.PermissionStatusHelper;
import com.doubleangels.redact.ui.MainViewModel;
import com.doubleangels.redact.sentry.SentryInitializer;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.stats.StatsSnapshot;
import com.doubleangels.redact.stats.StatsStore;
import com.doubleangels.redact.ui.Notice;
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
public class SettingsFragment extends TabFragment {

    private boolean suppressNotificationToggleCallback;
    private boolean suppressCrashReportingToggleCallback;
    private boolean suppressPreserveLocationToggleCallback;
    private boolean requestLocationAfterMediaGrant;

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
    private TextView textPermissionLocationStatus;
    private MaterialButton buttonLocationPermission;
    private TextView textPermissionNotificationsStatus;
    private TextView textStorageSize;
    private View rowStatsSummary;
    private TextView textStatsSummarySubtitle;
    private TextView textStatsSummaryFields;
    private TextView textStatsSummaryLocations;
    private TextView textStatsSummaryFiles;
    private MaterialAutoCompleteTextView dropdownDefaultImageFormat;
    private MaterialAutoCompleteTextView dropdownDefaultVideoFormat;
    private MaterialAutoCompleteTextView dropdownImageQuality;

    private MaterialSwitch switchStrictClean;
    private MaterialSwitch switchDeleteOriginals;
    private MaterialSwitch switchKeepOriginalNames;
    private MaterialSwitch switchWarnAlreadyClean;
    private MaterialSwitch switchSecureWindow;
    private MaterialSwitch switchPreserveCamera;
    private MaterialSwitch switchPreserveLocation;
    private MaterialSwitch switchAutoClearTemp;
    private MaterialSwitch switchVideoFallback;
    private MaterialAutoCompleteTextView dropdownSecureDelete;
    private MaterialAutoCompleteTextView dropdownMaxBitmapSize;
    private MaterialAutoCompleteTextView dropdownClipboardClear;

    private String[] imageFormatLabels;
    private String[] videoFormatLabels;
    private String[] qualityLabels;
    private String[] secureDeleteLabels;
    private String[] maxBitmapSizeLabels;
    private String[] clipboardClearLabels;

    @Nullable
    private ExecutorService backgroundExecutor;
    /** Counts output folder checks, so only the latest one's answer is shown. Main thread only. */
    private int outputFolderCheck;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        backgroundExecutor = Executors.newSingleThreadExecutor();
        try {
            bindViews(view);
            imageFormatLabels = getResources().getStringArray(R.array.settings_image_format_labels);
            videoFormatLabels = getResources().getStringArray(R.array.settings_video_format_labels);
            qualityLabels = getResources().getStringArray(R.array.settings_quality_labels);
            secureDeleteLabels = getResources().getStringArray(R.array.settings_secure_delete_labels);
            clipboardClearLabels = getResources().getStringArray(R.array.settings_clipboard_clear_labels);
            maxBitmapSizeLabels = getResources().getStringArray(R.array.settings_max_bitmap_size_labels);

            bindStatistics(view);
            bindPermissions(view);
            bindConversionDefaults();
            bindShareTarget();
            bindNotifications();
            bindStorage(view);
            bindPrivacy(view);
            bindAdvancedSettings();
            bindAbout(view);
            com.doubleangels.redact.ui.SwitchRows.wire(view);
        } catch (Exception e) {
            SentryManager.recordException(e);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPermissionStatuses();
        refreshStorageSize();
        refreshOutputFolders();
        refreshStatistics();
        maybeRequestLocationAfterMediaGrant();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            // A chosen folder can be deleted or its storage removed while another tab is open.
            refreshOutputFolders();
            // A clean or convert on another tab adds to the counts.
            refreshStatistics();
        }
    }

    @Override
    public void onDestroyView() {
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdown();
            backgroundExecutor = null;
        }
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
        textPermissionLocationStatus = view.findViewById(R.id.textPermissionLocationStatus);
        buttonLocationPermission = view.findViewById(R.id.buttonLocationPermission);
        textPermissionNotificationsStatus = view.findViewById(R.id.textPermissionNotificationsStatus);
        textStorageSize = view.findViewById(R.id.textStorageSize);
        dropdownDefaultImageFormat = view.findViewById(R.id.dropdownDefaultImageFormat);
        dropdownDefaultVideoFormat = view.findViewById(R.id.dropdownDefaultVideoFormat);
        dropdownImageQuality = view.findViewById(R.id.dropdownImageQuality);
        
        switchStrictClean = view.findViewById(R.id.switchStrictClean);
        switchDeleteOriginals = view.findViewById(R.id.switchDeleteOriginals);
        switchKeepOriginalNames = view.findViewById(R.id.switchKeepOriginalNames);
        switchWarnAlreadyClean = view.findViewById(R.id.switchWarnAlreadyClean);
        switchSecureWindow = view.findViewById(R.id.switchSecureWindow);
        switchPreserveCamera = view.findViewById(R.id.switchPreserveCamera);
        switchPreserveLocation = view.findViewById(R.id.switchPreserveLocation);
        switchAutoClearTemp = view.findViewById(R.id.switchAutoClearTemp);
        switchVideoFallback = view.findViewById(R.id.switchVideoFallback);
        dropdownSecureDelete = view.findViewById(R.id.dropdownSecureDelete);
        dropdownMaxBitmapSize = view.findViewById(R.id.dropdownMaxBitmapSize);
        dropdownClipboardClear = view.findViewById(R.id.dropdownClipboardClear);
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
            if (suppressCrashReportingToggleCallback) {
                return;
            }
            try {
                if (!isChecked) {
                    AppPreferences.setCrashReportingEnabled(requireContext(), false);
                    SentryInitializer.shutdown();
                    SentryManager.setCustomKey("crash_reporting_enabled", false);
                    return;
                }
                revertSwitch(switchCrashReporting, false);
                showSentryConsentDialog(
                        () -> {
                            AppPreferences.setCrashReportingEnabled(requireContext(), true);
                            SentryInitializer.initializeBlocking(requireContext());
                            if (!SentryInitializer.isInitialized()) {
                                Notice.show(requireContext(),
                                        SentryInitializer.hasConfiguredDsn()
                                                ? R.string.settings_crash_reporting_init_failed
                                                : R.string.settings_crash_reporting_unconfigured,
                                        true);
                            }
                            setSwitchChecked(switchCrashReporting, true);
                            SentryManager.setCustomKey("crash_reporting_enabled", true);
                        },
                        null);
            } catch (Exception e) {
                android.util.Log.e("SettingsFragment", "Error saving crash reporting pref", e);
                SentryManager.recordException(e);
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

    private void bindAdvancedSettings() {
        switchStrictClean.setChecked(AppPreferences.isStrictClean(requireContext()));
        switchDeleteOriginals.setChecked(AppPreferences.isDeleteOriginalsAfterClean(requireContext()));
        switchDeleteOriginals.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setDeleteOriginalsAfterClean(requireContext(), isChecked));
        switchKeepOriginalNames.setChecked(AppPreferences.isKeepOriginalFileNames(requireContext()));
        switchKeepOriginalNames.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setKeepOriginalFileNames(requireContext(), isChecked));
        switchWarnAlreadyClean.setChecked(AppPreferences.isWarnAlreadyClean(requireContext()));
        switchWarnAlreadyClean.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setWarnAlreadyClean(requireContext(), isChecked));
        switchSecureWindow.setChecked(AppPreferences.isSecureWindow(requireContext()));
        switchSecureWindow.setOnCheckedChangeListener((btn, isChecked) -> {
            AppPreferences.setSecureWindow(requireContext(), isChecked);
            com.doubleangels.redact.ui.SecureWindow.apply(requireActivity());
        });

        int[] clearOptions = AppPreferences.CLIPBOARD_CLEAR_SECONDS_OPTIONS;
        int clearIndex = 0;
        for (int i = 0; i < clearOptions.length; i++) {
            if (clearOptions[i] == AppPreferences.getClipboardClearSeconds(requireContext())) {
                clearIndex = i;
            }
        }
        setupDropdown(dropdownClipboardClear, clipboardClearLabels, clearIndex,
                index -> AppPreferences.setClipboardClearSeconds(requireContext(), clearOptions[index]));
        switchPreserveCamera.setChecked(AppPreferences.isPreserveCameraSettings(requireContext()));
        switchPreserveLocation.setChecked(AppPreferences.isPreserveLocation(requireContext()));
        switchAutoClearTemp.setChecked(AppPreferences.isAutoClearTempFiles(requireContext()));
        switchVideoFallback.setChecked(AppPreferences.isVideoFallbackCopy(requireContext()));

        updatePreservationTogglesState(switchStrictClean.isChecked());

        switchStrictClean.setOnCheckedChangeListener((btn, isChecked) -> {
            AppPreferences.setStrictClean(requireContext(), isChecked);
            if (isChecked) {
                AppPreferences.setPreserveCameraSettings(requireContext(), false);
                AppPreferences.setPreserveLocation(requireContext(), false);
            }
            updatePreservationTogglesState(isChecked);
        });

        switchPreserveCamera.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setPreserveCameraSettings(requireContext(), isChecked));

        switchPreserveLocation.setOnCheckedChangeListener((btn, isChecked) -> {
            if (suppressPreserveLocationToggleCallback) {
                return;
            }
            if (isChecked) {
                suppressPreserveLocationToggleCallback = true;
                switchPreserveLocation.setChecked(false);
                suppressPreserveLocationToggleCallback = false;
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_preserve_location_confirm_title)
                        .setMessage(R.string.settings_preserve_location_confirm_message)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            AppPreferences.setPreserveLocation(requireContext(), true);
                            suppressPreserveLocationToggleCallback = true;
                            switchPreserveLocation.setChecked(true);
                            suppressPreserveLocationToggleCallback = false;
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            } else {
                AppPreferences.setPreserveLocation(requireContext(), false);
            }
        });

        switchAutoClearTemp.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setAutoClearTempFiles(requireContext(), isChecked));

        switchVideoFallback.setOnCheckedChangeListener((btn, isChecked) ->
                AppPreferences.setVideoFallbackCopy(requireContext(), isChecked));

        int secureDeleteIndex = switch (AppPreferences.getSecureDeletePasses(requireContext())) {
            case 1 -> 0;
            case 7 -> 2;
            default -> 1;
        };
        setupDropdown(dropdownSecureDelete, secureDeleteLabels, secureDeleteIndex, index -> {
            int passes = switch (index) {
                case 0 -> 1;
                case 2 -> 7;
                default -> 3;
            };
            AppPreferences.setSecureDeletePasses(requireContext(), passes);
        });

        int maxBitmapSizeIndex = switch (AppPreferences.getMaxBitmapSize(requireContext())) {
            case 2048 -> 0;
            case 8192 -> 2;
            default -> 1;
        };
        setupDropdown(dropdownMaxBitmapSize, maxBitmapSizeLabels, maxBitmapSizeIndex, index -> {
            int size = switch (index) {
                case 0 -> 2048;
                case 2 -> 8192;
                default -> 4096;
            };
            AppPreferences.setMaxBitmapSize(requireContext(), size);
        });
    }

    private void updatePreservationTogglesState(boolean strictClean) {
        boolean enabled = !strictClean;
        float alpha = enabled ? 1f : 0.38f;
        
        switchPreserveCamera.setEnabled(enabled);
        switchPreserveLocation.setEnabled(enabled);
        
        View rowCamera = getView().findViewById(R.id.rowPreserveCamera);
        if (rowCamera != null) rowCamera.setAlpha(alpha);
        
        View rowLocation = getView().findViewById(R.id.rowPreserveLocation);
        if (rowLocation != null) rowLocation.setAlpha(alpha);
        
        if (strictClean) {
            switchPreserveCamera.setChecked(false);
            switchPreserveLocation.setChecked(false);
        }
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
        View rowPermissionLocation = view.findViewById(R.id.rowPermissionLocation);
        if (rowPermissionLocation != null) {
            rowPermissionLocation.setOnClickListener(v -> requestLocationPermissionFromSettings());
        }
        if (buttonLocationPermission != null) {
            buttonLocationPermission.setOnClickListener(v -> requestLocationPermissionFromSettings());
        }
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
        bindOutputFolder(view);
    }

    private final ActivityResultLauncher<Uri> imageFolderPicker = registerOutputFolderPicker(false);
    private final ActivityResultLauncher<Uri> videoFolderPicker = registerOutputFolderPicker(true);
    private TextView textOutputImages;
    private TextView textOutputVideos;
    private TextView textOutputImagesWarning;
    private TextView textOutputVideosWarning;
    private View buttonResetOutputImages;
    private View buttonResetOutputVideos;

    private ActivityResultLauncher<Uri> registerOutputFolderPicker(boolean video) {
        return registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), tree -> {
            if (tree == null) {
                return;
            }
            boolean saved = onOutputFolderPicked(requireContext(), video, tree);
            refreshOutputFolders();
            if (saved && !tree.equals(AppPreferences.getOutputTree(requireContext(), !video))) {
                offerFolderForOtherType(video, tree);
            }
        });
    }

    /** Asks whether the folder just chosen for {@code video} output should take the other type too. */
    private void offerFolderForOtherType(boolean video, @NonNull Uri tree) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(video ? R.string.settings_output_also_images : R.string.settings_output_also_videos)
                .setMessage(OutputDestination.describe(requireContext(), tree))
                .setPositiveButton(R.string.settings_output_also_confirm, (d, w) -> {
                    onOutputFolderPicked(requireContext(), !video, tree);
                    refreshOutputFolders();
                })
                .setNegativeButton(R.string.settings_output_also_decline, null)
                .show();
    }

    /**
     * Saves a folder picked for {@code video} output. Picking the default folder itself resets to
     * the default; a folder Redact cannot write to is refused with a message and nothing changes.
     *
     * @return true when {@code tree} is now the chosen folder for {@code video} output
     */
    @VisibleForTesting
    static boolean onOutputFolderPicked(@NonNull Context context, boolean video, @NonNull Uri tree) {
        Uri old = AppPreferences.getOutputTree(context, video);
        if (OutputDestination.isDefaultFolder(tree, video)) {
            AppPreferences.resetOutputTree(context, video);
            releaseOutputTree(context, old);
            return false;
        }
        try {
            context.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException e) {
            Notice.show(context, R.string.settings_output_error, true);
            return false;
        }
        if (!OutputDestination.acceptsNewFiles(context, tree)) {
            releaseOutputTree(context, tree);
            Notice.show(context, R.string.settings_output_error, true);
            return false;
        }
        AppPreferences.setOutputTree(context, video, tree);
        releaseOutputTree(context, old);
        OutputDestination.clearFallbackWarnings();
        return true;
    }

    /** Drops the persisted grant for a replaced folder unless the other media type still uses it. */
    private static void releaseOutputTree(@NonNull Context context, @Nullable Uri old) {
        if (old == null
                || old.equals(AppPreferences.getOutputTree(context, false))
                || old.equals(AppPreferences.getOutputTree(context, true))) {
            return;
        }
        try {
            context.getContentResolver().releasePersistableUriPermission(old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The grant was already gone.
        }
    }

    private void bindOutputFolder(@NonNull View view) {
        textOutputImages = view.findViewById(R.id.textOutputImages);
        textOutputVideos = view.findViewById(R.id.textOutputVideos);
        textOutputImagesWarning = view.findViewById(R.id.textOutputImagesWarning);
        textOutputVideosWarning = view.findViewById(R.id.textOutputVideosWarning);
        buttonResetOutputImages = view.findViewById(R.id.buttonResetOutputImages);
        buttonResetOutputVideos = view.findViewById(R.id.buttonResetOutputVideos);
        View.OnClickListener pickImages = v -> launchOutputFolderPicker(imageFolderPicker, false);
        View.OnClickListener pickVideos = v -> launchOutputFolderPicker(videoFolderPicker, true);
        view.findViewById(R.id.cardOutputImages).setOnClickListener(pickImages);
        view.findViewById(R.id.buttonChooseOutputImages).setOnClickListener(pickImages);
        view.findViewById(R.id.cardOutputVideos).setOnClickListener(pickVideos);
        view.findViewById(R.id.buttonChooseOutputVideos).setOnClickListener(pickVideos);
        view.findViewById(R.id.buttonOpenOutputImages).setOnClickListener(v -> openOutputFolder(false));
        view.findViewById(R.id.buttonOpenOutputVideos).setOnClickListener(v -> openOutputFolder(true));
        buttonResetOutputImages.setOnClickListener(v -> resetOutputFolder(false));
        buttonResetOutputVideos.setOnClickListener(v -> resetOutputFolder(true));
        refreshOutputFolders();
    }

    private void launchOutputFolderPicker(@NonNull ActivityResultLauncher<Uri> picker, boolean video) {
        withFolderLocation(context -> OutputDestination.pickerStartLocation(context, video), start -> {
            try {
                picker.launch(start);
            } catch (ActivityNotFoundException e) {
                Notice.show(requireContext(), R.string.settings_output_error, true);
            }
        });
    }

    /** Shows the folder {@code video} output currently goes to in the system file manager. */
    private void openOutputFolder(boolean video) {
        withFolderLocation(context -> OutputDestination.currentFolderDocument(context, video), folder -> {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(folder, android.provider.DocumentsContract.Document.MIME_TYPE_DIR);
            // Only a chosen folder is under a grant Redact can pass on; asking to grant the default
            // folder, which Redact holds no grant for, makes startActivity throw.
            if (android.provider.DocumentsContract.isTreeUri(folder)) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException e) {
                Notice.show(requireContext(), R.string.settings_output_open_unavailable, false);
            }
        });
    }

    /**
     * Works out a folder location in the background, since checking a chosen folder asks its
     * storage provider (slow for an SD card or a cloud folder), then hands it to {@code then} on
     * the main thread while this screen is still showing.
     */
    private void withFolderLocation(@NonNull java.util.function.Function<Context, Uri> locate,
                                    @NonNull java.util.function.Consumer<Uri> then) {
        View root = getView();
        if (root == null || backgroundExecutor == null) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        backgroundExecutor.execute(() -> {
            Uri location = locate.apply(app);
            root.post(() -> {
                if (getView() == root && isAdded()) {
                    then.accept(location);
                }
            });
        });
    }

    private void resetOutputFolder(boolean video) {
        Context context = requireContext();
        Uri old = AppPreferences.getOutputTree(context, video);
        AppPreferences.resetOutputTree(context, video);
        releaseOutputTree(context, old);
        refreshOutputFolders();
    }

    /**
     * Shows where each media type's output goes. Whether a chosen folder still works is checked in
     * the background, since that asks its storage provider; until the answer arrives a chosen
     * folder's badge and warning keep what they last showed.
     */
    private void refreshOutputFolders() {
        View root = getView();
        if (textOutputImages == null || textOutputVideos == null || root == null) {
            return;
        }
        Context context = requireContext();
        Uri images = AppPreferences.getOutputTree(context, false);
        Uri videos = AppPreferences.getOutputTree(context, true);
        showOutputFolder(textOutputImages, buttonResetOutputImages, images, false);
        showOutputFolder(textOutputVideos, buttonResetOutputVideos, videos, true);
        // The default folder is always usable, so it needs no check and no lingering warning.
        if (images == null) {
            showOutputFolderUsable(textOutputImagesWarning, root.findViewById(R.id.badgeOutputImages),
                    root.findViewById(R.id.iconOutputImages), false, true);
        }
        if (videos == null) {
            showOutputFolderUsable(textOutputVideosWarning, root.findViewById(R.id.badgeOutputVideos),
                    root.findViewById(R.id.iconOutputVideos), true, true);
        }
        if (backgroundExecutor == null) {
            return;
        }
        int check = ++outputFolderCheck;
        Context app = context.getApplicationContext();
        backgroundExecutor.execute(() -> {
            boolean imagesUsable = images == null || OutputDestination.isUsable(app, images);
            boolean videosUsable = videos == null || OutputDestination.isUsable(app, videos);
            root.post(() -> {
                // A newer refresh (or a new view) has taken over; its answer is the one to show.
                if (check != outputFolderCheck || getView() != root || !isAdded()) {
                    return;
                }
                showOutputFolderUsable(textOutputImagesWarning, root.findViewById(R.id.badgeOutputImages),
                        root.findViewById(R.id.iconOutputImages), false, imagesUsable);
                showOutputFolderUsable(textOutputVideosWarning, root.findViewById(R.id.badgeOutputVideos),
                        root.findViewById(R.id.iconOutputVideos), true, videosUsable);
            });
        });
    }

    /** Shows where {@code video} output goes. Reset is offered only for a chosen folder. */
    private void showOutputFolder(@NonNull TextView label, @NonNull View reset, @Nullable Uri tree,
                                  boolean video) {
        if (tree == null) {
            label.setText(OutputDestination.defaultPath(video));
            reset.setVisibility(View.GONE);
            return;
        }
        label.setText(OutputDestination.describe(requireContext(), tree));
        reset.setVisibility(View.VISIBLE);
    }

    /**
     * A chosen folder Redact can no longer write to says so, since files then go to the default.
     * The default folder is always usable.
     */
    private void showOutputFolderUsable(@NonNull TextView warning, @NonNull View badge,
                                        @NonNull ImageView icon, boolean video, boolean usable) {
        tintOutputBadge(badge, icon, usable);
        if (usable) {
            warning.setVisibility(View.GONE);
        } else {
            warning.setText(getString(R.string.settings_output_unavailable, OutputDestination.defaultPath(video)));
            warning.setVisibility(View.VISIBLE);
        }
    }

    /** Paints an unusable folder's badge in the error colours so it stands out from a working one. */
    private void tintOutputBadge(@NonNull View badge, @NonNull ImageView icon, boolean usable) {
        Context context = requireContext();
        int background = MaterialColors.getColor(context, usable
                ? com.google.android.material.R.attr.colorSecondaryContainer
                : com.google.android.material.R.attr.colorErrorContainer, 0);
        int foreground = MaterialColors.getColor(context, usable
                ? com.google.android.material.R.attr.colorOnSecondaryContainer
                : com.google.android.material.R.attr.colorOnErrorContainer, 0);
        badge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(background));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(foreground));
    }

    private void bindStatistics(@NonNull View view) {
        rowStatsSummary = view.findViewById(R.id.rowStatsSummary);
        textStatsSummarySubtitle = view.findViewById(R.id.textStatsSummarySubtitle);
        textStatsSummaryFields = view.findViewById(R.id.textStatsSummaryFields);
        textStatsSummaryLocations = view.findViewById(R.id.textStatsSummaryLocations);
        textStatsSummaryFiles = view.findViewById(R.id.textStatsSummaryFiles);
        view.findViewById(R.id.cardStatsSummary).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), StatisticsActivity.class)));
        refreshStatistics();
    }

    /** Shows the headline counts, or an invitation to clean something while there are none. */
    private void refreshStatistics() {
        if (rowStatsSummary == null || getContext() == null) {
            return;
        }
        StatsSnapshot stats = StatsStore.snapshot(requireContext());
        if (stats.isEmpty()) {
            rowStatsSummary.setVisibility(View.GONE);
            textStatsSummarySubtitle.setText(R.string.stats_settings_empty);
            return;
        }
        textStatsSummarySubtitle.setText(getString(R.string.stats_settings_since,
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                        .format(new java.util.Date(stats.trackingSince))));
        textStatsSummaryFields.setText(compactCount(stats.fieldsRemoved));
        textStatsSummaryLocations.setText(compactCount(stats.locationsRemoved));
        textStatsSummaryFiles.setText(compactCount(stats.filesCleaned()));
        rowStatsSummary.setVisibility(View.VISIBLE);
    }

    /** A count short enough for a third of the card: 1,284 stays as it is, 1,284,000 becomes 1.3M. */
    @NonNull
    private static String compactCount(long count) {
        if (count < 1_000_000) {
            return java.text.NumberFormat.getIntegerInstance().format(count);
        }
        return android.icu.text.CompactDecimalFormat.getInstance(
                java.util.Locale.getDefault(),
                android.icu.text.CompactDecimalFormat.CompactStyle.SHORT).format(count);
    }

    private void bindAbout(@NonNull View view) {
        TextView version = view.findViewById(R.id.textAboutVersion);
        version.setText(getString(R.string.settings_about_version, BuildConfig.VERSION_NAME));

        view.findViewById(R.id.rowAboutGithub).setOnClickListener(v ->
                openUrl(getString(R.string.url_github)));
        view.findViewById(R.id.rowAboutPrivacy).setOnClickListener(v ->
                openUrl(getString(R.string.url_privacy_policy)));
        view.findViewById(R.id.rowAboutLicenses).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), LicensesActivity.class)));
        view.findViewById(R.id.rowAboutIntro).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), OnboardingActivity.class)
                        .putExtra(OnboardingActivity.EXTRA_REPLAY, true)));
        view.findViewById(R.id.rowAboutReportIssue).setOnClickListener(v ->
                openUrl(getString(R.string.url_report_issue)));
        view.findViewById(R.id.rowAboutRate).setOnClickListener(v ->
                com.doubleangels.redact.ui.ReviewPrompt.openStoreListing(requireContext()));
        view.findViewById(R.id.rowAboutDonate).setOnClickListener(v ->
                openUrl(getString(R.string.url_donate)));
        view.findViewById(R.id.rowResetSettings).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.settings_reset_all_title)
                        .setMessage(R.string.settings_reset_all_message)
                        .setPositiveButton(R.string.settings_reset_all_confirm, (d, w) -> resetAllSettings())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
    }

    /**
     * Restores every default: releases the chosen folders' grants, stops crash reporting if it was
     * on, and recreates the activity so every screen reads the reset values.
     */
    private void resetAllSettings() {
        Context context = requireContext();
        // A running job may be writing into a chosen folder whose grant this would release.
        if (ShareHandlerActivity.isShareProcessingActive() || MainViewModel.isAnyProcessing(requireActivity())) {
            Notice.show(context, R.string.settings_reset_all_busy, false);
            return;
        }
        Uri images = AppPreferences.getOutputTree(context, false);
        Uri videos = AppPreferences.getOutputTree(context, true);
        boolean crashReporting = AppPreferences.isCrashReportingEnabled(context);
        AppPreferences.resetAll(context);
        releaseOutputTree(context, images);
        releaseOutputTree(context, videos);
        OutputDestination.clearFallbackWarnings();
        if (crashReporting) {
            SentryInitializer.shutdown();
        }
        Notice.show(context, R.string.settings_reset_all_done, false);
        // Restored view state would put back each switch and dropdown as it was, and the switch
        // listeners would then write the old values straight back into the reset preferences.
        View root = getView();
        if (root != null) {
            disableStateSaving(root);
        }
        requireActivity().recreate();
    }

    private static void disableStateSaving(@NonNull View view) {
        view.setSaveEnabled(false);
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                disableStateSaving(group.getChildAt(i));
            }
        }
    }

    private void setupDropdown(@NonNull MaterialAutoCompleteTextView dropdown,
                               @NonNull String[] labels,
                               int selectedIndex,
                               @NonNull IndexConsumer onSelected) {
        // Exposed dropdown menus must not filter by the current text, or only the selected item appears.
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                labels) {
            @NonNull
            @Override
            public Filter getFilter() {
                return new Filter() {
                    @Override
                    protected FilterResults performFiltering(CharSequence constraint) {
                        FilterResults results = new FilterResults();
                        results.values = labels;
                        results.count = labels.length;
                        return results;
                    }

                    @Override
                    protected void publishResults(CharSequence constraint, FilterResults results) {
                        notifyDataSetChanged();
                    }
                };
            }
        };
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
        if (textPermissionLocationStatus != null) {
            PermissionStatusHelper.Status locationStatus =
                    PermissionStatusHelper.getLocationStatus(requireContext());
            applyPermissionStatus(textPermissionLocationStatus, locationStatus);
            if (buttonLocationPermission != null) {
                buttonLocationPermission.setVisibility(
                        locationStatus == PermissionStatusHelper.Status.GRANTED
                                ? View.GONE
                                : View.VISIBLE);
            }
        }
        applyPermissionStatus(
                textPermissionNotificationsStatus,
                PermissionStatusHelper.getNotificationsStatus(requireContext()));
    }

    private void applyPermissionStatus(
            @NonNull TextView statusView,
            @NonNull PermissionStatusHelper.Status status) {
        statusView.setText(getString(statusLabel(status)));
        int color = switch (status) {
            // Green for granted, harmonized with the primary (the wallpaper's, where Android offers
            // one) as Material asks of colors outside the scheme.
            case GRANTED -> MaterialColors.harmonizeWithPrimary(requireContext(),
                    ContextCompat.getColor(requireContext(), R.color.permission_status_granted));
            case PARTIAL -> MaterialColors.getColor(
                    requireContext(),
                    com.google.android.material.R.attr.colorSecondary,
                    ContextCompat.getColor(requireContext(), R.color.md_theme_secondary));
            case DENIED, BLOCKED -> MaterialColors.getColor(
                    requireContext(),
                    androidx.appcompat.R.attr.colorError,
                    ContextCompat.getColor(requireContext(), R.color.md_theme_error));
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
            case PARTIAL -> R.string.settings_permission_partial;
            case DENIED -> R.string.settings_permission_denied;
            case BLOCKED -> R.string.settings_permission_needs_media;
            case NOT_REQUIRED -> R.string.settings_permission_not_required;
        };
    }

    private void refreshStorageSize() {
        if (textStorageSize == null || backgroundExecutor == null) {
            return;
        }
        final android.content.Context ctx = requireContext().getApplicationContext();
        backgroundExecutor.execute(() -> {
            long bytes = CacheCleanup.getTempCacheSizeBytes(ctx);
            android.app.Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (isAdded()) {
                        textStorageSize.setText(getString(R.string.settings_storage_size, CacheCleanup.formatSize(bytes)));
                    }
                });
            }
        });
    }

    private void clearTempFiles() {
        if (ShareHandlerActivity.isShareProcessingActive()) {
            Notice.show(requireContext(), R.string.settings_storage_clear_busy, false);
            return;
        }
        if (getActivity() != null && MainViewModel.isAnyProcessing(requireActivity())) {
            Notice.show(requireContext(), R.string.settings_storage_clear_busy, false);
            return;
        }
        if (backgroundExecutor == null) {
            return;
        }
        final android.content.Context ctx = requireContext().getApplicationContext();
        backgroundExecutor.execute(() -> {
            long before = CacheCleanup.getTempCacheSizeBytes(ctx);
            int deleted = CacheCleanup.clearAllTempFiles(ctx);
            android.app.Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (isAdded()) {
                        refreshStorageSize();
                        if (before == 0 || deleted == 0) {
                            Notice.show(ctx, R.string.settings_storage_clear_empty, false);
                        } else {
                            Notice.show(ctx, R.string.settings_storage_clear_success, false);
                        }
                    }
                });
            }
        });
    }

    private void openUrl(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Notice.show(requireContext(), R.string.settings_link_unavailable, false);
        }
    }

    private void requestLocationPermissionFromSettings() {
        if (ContextCompat.checkSelfPermission(
                        requireContext(), android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (!PermissionStatusHelper.hasMediaReadPrerequisiteForLocation(requireContext())) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_location_needs_media_title)
                    .setMessage(R.string.settings_location_needs_media_message)
                    .setPositiveButton(R.string.settings_location_grant_media_first, (d, w) -> {
                        requestLocationAfterMediaGrant = true;
                        PermissionManager.requestMissingRuntimePermissions(
                                requireActivity(), true);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION);
    }

    private void maybeRequestLocationAfterMediaGrant() {
        if (!requestLocationAfterMediaGrant) {
            return;
        }
        if (!PermissionStatusHelper.hasMediaReadPrerequisiteForLocation(requireContext())) {
            requestLocationAfterMediaGrant = false;
            return;
        }
        requestLocationAfterMediaGrant = false;
        if (ContextCompat.checkSelfPermission(
                        requireContext(), android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION);
        }
    }

    private final ActivityResultLauncher<String> locationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        refreshPermissionStatuses();
                        Notice.show(requireContext(),
                                granted ? R.string.settings_location_permission_granted : R.string.settings_location_permission_denied,
                                false);
                    });

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            applyNotificationsEnabled(true);
                            SentryManager.setCustomKey("notifications_enabled", true);
                            Notice.show(requireContext(),
                                    R.string.settings_notifications_permission_granted,
                                    false);
                        } else {
                            suppressNotificationToggleCallback = true;
                            switchNotifications.setChecked(false);
                            suppressNotificationToggleCallback = false;
                            applyNotificationsEnabled(false);
                            Notice.show(requireContext(),
                                    R.string.settings_notifications_permission_denied,
                                    false);
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

    private void showSentryConsentDialog(
            @NonNull Runnable onAllow,
            @Nullable Runnable onDeny) {
        com.doubleangels.redact.ui.CrashReportingPrompt.consentDialog(requireContext(), onAllow, () -> {
                    if (onDeny != null) {
                        onDeny.run();
                    }
                })
                .setOnCancelListener(d -> {
                    if (onDeny != null) {
                        onDeny.run();
                    }
                })
                .show();
    }

    private void revertSwitch(@NonNull MaterialSwitch target, boolean checked) {
        suppressCrashReportingToggleCallback = true;
        target.setChecked(checked);
        suppressCrashReportingToggleCallback = false;
    }

    private void setSwitchChecked(@NonNull MaterialSwitch target, boolean checked) {
        suppressCrashReportingToggleCallback = true;
        target.setChecked(checked);
        suppressCrashReportingToggleCallback = false;
    }

    @FunctionalInterface
    private interface IndexConsumer {
        void accept(int index);
    }
}
