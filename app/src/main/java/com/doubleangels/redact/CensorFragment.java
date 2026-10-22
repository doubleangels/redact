package com.doubleangels.redact;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorExporter;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.ui.CensorCanvasView;
import com.doubleangels.redact.ui.CensorViewModel;
import com.doubleangels.redact.ui.Haptics;
import com.doubleangels.redact.ui.ScanViewModel;
import com.doubleangels.redact.ui.ShareResults;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.slider.Slider;

import java.util.List;

/**
 * The Censor tab: covers parts of one photo (pixelate, black or blur) and saves a censored copy
 * without any of the original's metadata. The photo comes from the picker here, from Scan, or
 * from sharing it to Redact › Censor; all of them go through {@link CensorViewModel}.
 */
public class CensorFragment extends Fragment {

    /** Brush widths for the slider's 1 to 10, as fractions of the photo's longer side at fit. */
    private static final float BRUSH_WIDTH_PER_STEP = 0.01f;
    /** How long the brush size circle stays after the slider is let go. */
    private static final long BRUSH_PREVIEW_LINGER_MS = 700;
    private ActivityResultLauncher<IntentSenderRequest> trashRequestLauncher;
    /** The source photo in the trash request on screen. */
    @Nullable
    private Uri pendingTrashSource;

    private boolean draggingBrushSize;
    /** False until the view's saved state is back, so restoring the tool buttons is not taken as a choice. */
    private boolean toolsRestored;
    private final Runnable hideBrushPreview = () -> {
        if (this.canvas != null) {
            this.canvas.setBrushPreview(false);
        }
    };

    private ActivityResultLauncher<String[]> photoPickerLauncher;
    private CensorViewModel viewModel;
    private View emptyState;
    private View content;
    private CensorCanvasView canvas;
    private View loading;
    private TextView message;
    private TextView fileName;
    private View toolsCard;
    private View deleteButton;
    private com.google.android.material.chip.Chip zoomChip;
    private View undoButton;
    private View redoButton;
    private TextView statusText;
    private ExtendedFloatingActionButton saveButton;
    private View shareButton;
    private View scanResultButton;
    @Nullable
    private androidx.appcompat.app.AlertDialog replaceDialog;
    /** The rectangle selected on the canvas, which Delete removes, or minus one. */
    private int selectedRectangle = -1;

    /** Asks before Back throws away covered areas that have not been saved. */
    private final OnBackPressedCallback unsavedEditsCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            confirmDiscard(() -> viewModel.close());
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        photoPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        SentryManager.log("A photo was selected in CensorFragment.");
                        viewModel.requestOpen(uri);
                    }
                });
        trashRequestLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    Uri trashed = pendingTrashSource;
                    pendingTrashSource = null;
                    SentryManager.logEvent("censor", result.getResultCode() == android.app.Activity.RESULT_OK
                            ? "The user moved the uncensored original to the trash."
                            : "The user kept the uncensored original.");
                    // The original is gone, so there is nothing left to save another copy from.
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && trashed != null
                            && viewModel != null && trashed.equals(viewModel.getSource())) {
                        viewModel.close();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_censor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        SentryManager.log("The CensorFragment view was created.");
        viewModel = new ViewModelProvider(requireActivity()).get(CensorViewModel.class);

        emptyState = view.findViewById(R.id.censorEmptyState);
        content = view.findViewById(R.id.censorContent);
        canvas = view.findViewById(R.id.censorCanvas);
        loading = view.findViewById(R.id.censorLoading);
        message = view.findViewById(R.id.censorMessage);
        fileName = view.findViewById(R.id.censorFileName);
        toolsCard = view.findViewById(R.id.censorToolsCard);
        deleteButton = view.findViewById(R.id.censorDeleteButton);
        zoomChip = view.findViewById(R.id.censorZoomChip);
        zoomChip.setOnClickListener(v -> {
            Haptics.tick(v);
            canvas.resetZoom();
        });
        undoButton = view.findViewById(R.id.censorUndoButton);
        redoButton = view.findViewById(R.id.censorRedoButton);
        statusText = view.findViewById(R.id.statusText);
        saveButton = view.findViewById(R.id.censorSaveButton);
        shareButton = view.findViewById(R.id.shareResultsButton);
        scanResultButton = view.findViewById(R.id.scanResultButton);

        view.findViewById(R.id.emptyStateSelectButton).setOnClickListener(v -> pickPhoto());
        view.findViewById(R.id.selectButton).setOnClickListener(v -> pickPhoto());
        view.findViewById(R.id.censorClearButton).setOnClickListener(v -> {
            Haptics.tick(v);
            if (viewModel.hasUnsavedEdits()) {
                confirmDiscard(() -> viewModel.close());
            } else {
                viewModel.close();
            }
        });
        deleteButton.setOnClickListener(v -> {
            int index = selectedRectangle;
            if (index >= 0) {
                Haptics.tick(v);
                canvas.clearSelection();
                viewModel.removeRegion(index);
            }
        });
        undoButton.setOnClickListener(v -> {
            Haptics.tick(v);
            viewModel.undo();
        });
        redoButton.setOnClickListener(v -> {
            Haptics.tick(v);
            viewModel.redo();
        });
        saveButton.setOnClickListener(v -> viewModel.save());
        shareButton.setOnClickListener(v -> {
            CensorExporter.Result saved = viewModel.getLastSaved();
            if (saved != null) {
                ShareResults.share(requireContext(), List.of(saved.uri));
            }
        });
        scanResultButton.setOnClickListener(v -> {
            Haptics.tick(v);
            openResultInScan();
        });
        setUpTools(view, savedInstanceState);
        setUpEffects(view);
        canvas.setListener(new CensorCanvasView.Listener() {
            @Override
            public void onRegionDrawn(@NonNull com.doubleangels.redact.media.CensorRegion region) {
                viewModel.addRegion(region);
                Haptics.tick(canvas);
            }

            @Override
            public void onRegionChanged(int index, @NonNull com.doubleangels.redact.media.CensorRegion region) {
                viewModel.replaceRegion(index, region);
                Haptics.tick(canvas);
            }

            @Override
            public void onSelectionChanged(int index) {
                if (index >= 0 && index != selectedRectangle) {
                    // A light tick tells the user the tap picked up the rectangle.
                    Haptics.tick(canvas);
                }
                selectedRectangle = index;
                deleteButton.setVisibility(index >= 0 ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onZoomChanged(float zoom) {
                showZoom(zoom);
            }
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), unsavedEditsCallback);

        viewModel.getPreview().observe(getViewLifecycleOwner(), bitmap -> {
            canvas.setImage(bitmap);
            viewModel.onPreviewShown(bitmap);
        });
        viewModel.getFileName().observe(getViewLifecycleOwner(), name ->
                fileName.setText(name != null && !name.isEmpty()
                        ? name : getString(R.string.scan_default_file_name)));
        viewModel.getHistoryVersion().observe(getViewLifecycleOwner(), v -> {
            canvas.setRegions(viewModel.regions());
            updateControls();
        });
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getPendingReplacement().observe(getViewLifecycleOwner(), this::askToReplace);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (viewModel != null) {
            updateControls();
            updateRecentsPreview();
        }
    }

    /** Hides the recent apps preview while this tab shows an open photo. */
    private void updateRecentsPreview() {
        CensorViewModel.State state = viewModel.getState().getValue();
        boolean photoShowing = !isHidden() && getView() != null
                && state != null && state != CensorViewModel.State.NONE;
        com.doubleangels.redact.ui.SecureWindow.setPhotoOpen(requireActivity(), photoShowing);
    }

    @Override
    public void onViewStateRestored(@Nullable Bundle savedInstanceState) {
        super.onViewStateRestored(savedInstanceState);
        toolsRestored = true;
    }

    @Override
    public void onDestroyView() {
        toolsRestored = false;
        canvas.removeCallbacks(hideBrushPreview);
        com.doubleangels.redact.ui.SecureWindow.setPhotoOpen(requireActivity(), false);
        if (replaceDialog != null) {
            replaceDialog.dismiss();
            replaceDialog = null;
        }
        super.onDestroyView();
    }

    private void pickPhoto() {
        try {
            photoPickerLauncher.launch(new String[] {"image/*"});
        } catch (android.content.ActivityNotFoundException e) {
            SentryManager.recordException(e);
            Toast.makeText(requireContext(), R.string.share_error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    private void setUpTools(@NonNull View view, @Nullable Bundle savedInstanceState) {
        MaterialButtonToggleGroup tools = view.findViewById(R.id.censorToolGroup);
        Slider brushSize = view.findViewById(R.id.censorBrushSize);
        // The tool and brush size used last time; the view's own saved state wins after a rotation.
        if (savedInstanceState == null) {
            tools.check(buttonFor(AppPreferences.getCensorTool(requireContext())));
            float size = AppPreferences.getCensorBrushSize(requireContext(), brushSize.getValue());
            brushSize.setValue(Math.max(brushSize.getValueFrom(), Math.min(brushSize.getValueTo(), Math.round(size))));
        }
        canvas.setBrushWidth(brushSize.getValue() * BRUSH_WIDTH_PER_STEP);
        // A circle of the brush's real size shows while the slider moves, since the size on the
        // photo depends on the zoom.
        brushSize.addOnChangeListener((slider, value, fromUser) -> {
            canvas.setBrushWidth(value * BRUSH_WIDTH_PER_STEP);
            if (fromUser && !draggingBrushSize) {
                // Changed without dragging (keys or an accessibility action): show it briefly.
                canvas.setBrushPreview(true);
                canvas.removeCallbacks(hideBrushPreview);
                canvas.postDelayed(hideBrushPreview, BRUSH_PREVIEW_LINGER_MS);
            }
        });
        brushSize.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                draggingBrushSize = true;
                canvas.removeCallbacks(hideBrushPreview);
                canvas.setBrushPreview(true);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                draggingBrushSize = false;
                canvas.postDelayed(hideBrushPreview, BRUSH_PREVIEW_LINGER_MS);
            }
        });
        brushSize.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                AppPreferences.setCensorBrushSize(requireContext(), value);
            }
        });
        tools.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                applyTool(checkedId, brushSize, toolsRestored);
            }
        });
        // Not remembered here: a rebuilt view (after Reset All Settings, say) would save the old tool again.
        applyTool(tools.getCheckedButtonId(), brushSize, false);
    }

    private void applyTool(int checkedId, @NonNull Slider brushSize, boolean remember) {
        CensorCanvasView.Tool tool = checkedId == R.id.censorToolBrush ? CensorCanvasView.Tool.BRUSH
                : checkedId == R.id.censorToolMove ? CensorCanvasView.Tool.MOVE
                : CensorCanvasView.Tool.RECT;
        canvas.setTool(tool);
        int visibility = tool == CensorCanvasView.Tool.BRUSH ? View.VISIBLE : View.GONE;
        brushSize.setVisibility(visibility);
        View container = getView() != null ? getView().findViewById(R.id.censorBrushSizeContainer) : null;
        if (container != null) {
            container.setVisibility(visibility);
        }
        if (remember) {
            AppPreferences.setCensorTool(requireContext(), tool.name());
        }
    }

    /** The tool button for a saved tool name; Rectangle when there is none. */
    private static int buttonFor(@Nullable String tool) {
        if (CensorCanvasView.Tool.BRUSH.name().equals(tool)) {
            return R.id.censorToolBrush;
        }
        if (CensorCanvasView.Tool.MOVE.name().equals(tool)) {
            return R.id.censorToolMove;
        }
        return R.id.censorToolRect;
    }

    private void setUpEffects(@NonNull View view) {
        ChipGroup effects = view.findViewById(R.id.censorEffectGroup);
        CensorEffects.Effect current = viewModel.getEffect().getValue();
        if (current != null) {
            effects.check(chipFor(current));
        }
        effects.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) {
                return;
            }
            int id = checkedIds.get(0);
            viewModel.setEffect(id == R.id.censorEffectBlack ? CensorEffects.Effect.BLACK
                    : id == R.id.censorEffectBlur ? CensorEffects.Effect.BLUR
                    : CensorEffects.Effect.PIXELATE);
            updateControls();
        });
    }

    private static int chipFor(@NonNull CensorEffects.Effect effect) {
        return switch (effect) {
            case BLACK -> R.id.censorEffectBlack;
            case BLUR -> R.id.censorEffectBlur;
            default -> R.id.censorEffectPixelate;
        };
    }

    private void render(@Nullable CensorViewModel.State state) {
        if (state == null) {
            return;
        }
        if (state == CensorViewModel.State.SAVED) {
            Haptics.confirm(saveButton);
            viewModel.acknowledgeSaveResult();
            // Android's trash confirmation takes priority over asking for a rating.
            if (!requestTrashOriginal()) {
                com.doubleangels.redact.ui.ReviewPrompt.askIfDue(this);
            }
            return;
        }
        if (state == CensorViewModel.State.SAVE_FAILED) {
            Haptics.reject(saveButton);
            viewModel.acknowledgeSaveResult();
            return;
        }
        boolean open = state != CensorViewModel.State.NONE;
        updateRecentsPreview();
        emptyState.setVisibility(open ? View.GONE : View.VISIBLE);
        content.setVisibility(open ? View.VISIBLE : View.GONE);
        loading.setVisibility(state == CensorViewModel.State.LOADING ? View.VISIBLE : View.GONE);
        boolean failedToOpen = state == CensorViewModel.State.LOAD_FAILED
                || state == CensorViewModel.State.UNSUPPORTED;
        message.setVisibility(failedToOpen ? View.VISIBLE : View.GONE);
        if (failedToOpen) {
            message.setText(state == CensorViewModel.State.UNSUPPORTED
                    ? R.string.censor_error_unsupported : R.string.censor_error_open);
        }
        toolsCard.setVisibility(failedToOpen ? View.GONE : View.VISIBLE);
        canvas.setEnabled(state == CensorViewModel.State.READY);
        updateControls();
    }

    private void updateControls() {
        CensorViewModel.State state = viewModel.getState().getValue();
        boolean ready = state == CensorViewModel.State.READY;
        boolean saving = state == CensorViewModel.State.SAVING;
        boolean unsaved = viewModel.hasUnsavedEdits();
        undoButton.setEnabled(ready && viewModel.canUndo());
        redoButton.setEnabled(ready && viewModel.canRedo());

        // Save Copy shows once there is something new to save, as Convert shows its button only
        // with files selected; it stays (disabled) while the copy is being saved.
        saveButton.setVisibility((ready && unsaved) || saving ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(ready);

        // Share and Scan Result offer the saved copy only while it still matches the screen.
        CensorExporter.Result saved = viewModel.getLastSaved();
        boolean savedMatches = ready && saved != null && !unsaved && viewModel.hasRegions();
        shareButton.setVisibility(savedMatches ? View.VISIBLE : View.GONE);
        scanResultButton.setVisibility(savedMatches ? View.VISIBLE : View.GONE);

        if (saving) {
            showStatus(getString(R.string.status_processing));
        } else if (viewModel.isLastSaveFailed()) {
            showStatus(getString(R.string.censor_error_save));
        } else if (savedMatches) {
            showStatus(getString(saved.downscaled ? R.string.censor_saved_downscaled : R.string.censor_saved));
        } else {
            statusText.setVisibility(View.GONE);
        }

        unsavedEditsCallback.setEnabled(!isHidden() && unsaved);
    }

    /** Shows "2×" while zoomed in, so the way back to the whole photo is one tap away. */
    private void showZoom(float zoom) {
        boolean zoomed = zoom > 1.01f;
        zoomChip.setVisibility(zoomed ? View.VISIBLE : View.GONE);
        if (zoomed) {
            String level = zoomLabel(zoom);
            zoomChip.setText(getString(R.string.censor_zoom_level, level));
            zoomChip.setContentDescription(getString(R.string.censor_zoom_reset, level));
        }
    }

    /** 2, 2.5 or 12: whole numbers without a decimal, others to one place. */
    @NonNull
    static String zoomLabel(float zoom) {
        float rounded = Math.round(zoom * 10f) / 10f;
        java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(rounded == Math.rint(rounded) ? 0 : 1);
        return format.format(rounded);
    }

    /**
     * After a save with Delete Originals After Cleaning on, asks Android (with its own confirmation,
     * the same one Clean uses) to move the uncensored original to the trash, when it is a photo in
     * the phone's media library. Returns whether the request was made.
     */
    private boolean requestTrashOriginal() {
        if (!AppPreferences.isDeleteOriginalsAfterClean(requireContext())) {
            return false;
        }
        Uri source = viewModel.getSource();
        Uri media = source != null ? trashableSource(source) : null;
        if (media == null || getView() == null || isHidden()) {
            return false;
        }
        requestTrash(source, media);
        return true;
    }

    /** The trashable Images item for a source photo, or null when it is not in the media library. */
    @Nullable
    private Uri trashableSource(@NonNull Uri source) {
        try {
            Uri media;
            if (android.provider.MediaStore.AUTHORITY.equals(source.getAuthority())) {
                // A photo picker item's number is the picker's own, not the library's.
                List<String> path = source.getPathSegments();
                if (path.isEmpty() || path.get(0).startsWith("picker")) {
                    return null;
                }
                media = source;
            } else {
                media = android.provider.MediaStore.getMediaUri(requireContext(), source);
            }
            return media != null ? CleanFragment.trashableMediaUri(media, "image/*") : null;
        } catch (Exception notMedia) {
            // A photo from another app's provider: leave it alone.
            return null;
        }
    }

    private void requestTrash(@NonNull Uri source, @NonNull Uri media) {
        try {
            pendingTrashSource = source;
            trashRequestLauncher.launch(new IntentSenderRequest.Builder(
                    android.provider.MediaStore.createTrashRequest(requireContext().getContentResolver(),
                            List.of(media), true).getIntentSender()).build());
        } catch (Exception e) {
            pendingTrashSource = null;
            SentryManager.recordException(e);
            Toast.makeText(requireContext(), R.string.share_error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    private void showStatus(@NonNull String text) {
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
    }

    /** Another photo arrived while this one has unsaved edits: ask before replacing it. */
    private void askToReplace(@Nullable Uri pending) {
        if (pending == null || replaceDialog != null) {
            return;
        }
        replaceDialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.censor_discard_title)
                .setMessage(R.string.censor_discard_message)
                .setNegativeButton(R.string.censor_keep_editing, (d, w) -> viewModel.cancelReplacement())
                .setPositiveButton(R.string.censor_discard, (d, w) -> viewModel.confirmReplacement())
                .setOnCancelListener(d -> viewModel.cancelReplacement())
                .setOnDismissListener(d -> replaceDialog = null)
                .show();
    }

    private void confirmDiscard(@NonNull Runnable onDiscard) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.censor_discard_title)
                .setMessage(R.string.censor_discard_message)
                .setNegativeButton(R.string.censor_keep_editing, null)
                .setPositiveButton(R.string.censor_discard, (d, w) -> {
                    SentryManager.logEvent("censor", "The user discarded unsaved censor edits.");
                    onDiscard.run();
                })
                .show();
    }

    private void openResultInScan() {
        CensorExporter.Result saved = viewModel.getLastSaved();
        if (saved == null) {
            return;
        }
        new ViewModelProvider(requireActivity()).get(ScanViewModel.class).setPendingUri(saved.uri);
        if (requireActivity() instanceof MainActivity mainActivity) {
            mainActivity.selectTab(R.id.navigation_scan);
        }
    }
}
