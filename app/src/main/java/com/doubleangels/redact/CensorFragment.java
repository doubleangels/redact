package com.doubleangels.redact;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorExporter;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.ui.CensorCanvasView;
import com.doubleangels.redact.ui.CensorViewModel;
import com.doubleangels.redact.ui.Haptics;
import com.doubleangels.redact.ui.Notice;
import com.doubleangels.redact.ui.ScanViewModel;
import com.doubleangels.redact.ui.ShareResults;
import com.doubleangels.redact.ui.SummaryCard;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.slider.Slider;

import java.util.List;

/**
 * The Censor tab: covers parts of one photo (pixelate, black or blur) and saves a censored copy
 * without any of the original's metadata. The photo comes from the picker here, from Scan, or
 * from sharing it to Redact › Censor; all of them go through {@link CensorViewModel}.
 */
public class CensorFragment extends TabFragment {

    /** Brush widths for the slider's 1 to 10, as fractions of the photo's longer side at fit. */
    private static final float BRUSH_WIDTH_PER_STEP = 0.01f;
    /** How long the brush size circle stays after the slider is let go. */
    private static final long BRUSH_PREVIEW_LINGER_MS = 700;
    private ActivityResultLauncher<IntentSenderRequest> trashRequestLauncher;
    /** The source photo in the trash request on screen. */
    @Nullable
    private Uri pendingTrashSource;

    private boolean draggingBrushSize;
    private CensorCanvasView.Tool currentTool = CensorCanvasView.Tool.RECT;
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
    private View summaryCard;
    private View historyControls;
    private View toolPill;
    private MaterialButton fullScreenButton;
    private MaterialButton effectButton;
    private View brushSizeBar;
    /** The landscape column holding the summary card; null in portrait. */
    @Nullable
    private View column;
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
    @Nullable
    private androidx.appcompat.widget.PopupMenu effectMenu;
    /** Whether this fragment last hid the bars, so they are only shown or hidden on a change. */
    private boolean immersive;
    /** The rectangle selected on the canvas, which Delete removes, or minus one. */
    private int selectedRectangle = -1;

    /** Asks before Back throws away covered areas that have not been saved. */
    private final OnBackPressedCallback unsavedEditsCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            confirmDiscard(() -> viewModel.close());
        }
    };

    /** Back leaves full screen before anything else. */
    private final OnBackPressedCallback fullScreenCallback = new OnBackPressedCallback(false) {
        @Override
        public void handleOnBackPressed() {
            setFullScreen(false);
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
        summaryCard = view.findViewById(R.id.censorSummaryCard);
        SummaryCard.bind(summaryCard, R.drawable.ic_censor, R.string.censor_hint,
                R.string.censor_select_photo, AppPreferences.HINT_CENSOR);
        fileName = view.findViewById(R.id.summaryTitle);
        // A file name keeps its extension visible, which a count has no need for.
        fileName.setSingleLine(true);
        fileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        historyControls = view.findViewById(R.id.censorHistoryControls);
        toolPill = view.findViewById(R.id.censorToolPill);
        fullScreenButton = view.findViewById(R.id.censorFullScreenButton);
        effectButton = view.findViewById(R.id.censorEffectButton);
        brushSizeBar = view.findViewById(R.id.censorBrushSizeContainer);
        column = view.findViewById(R.id.censorColumn);
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
        view.findViewById(R.id.summaryClearButton).setOnClickListener(v -> {
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
        setUpTools(view);
        setUpEffects();
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
                updateHistoryControls();
            }

            @Override
            public void onZoomChanged(float zoom) {
                showZoom(zoom);
            }
        });

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), unsavedEditsCallback);
        // Added last, so it runs first: Back leaves full screen before it asks about unsaved edits.
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), fullScreenCallback);

        viewModel.getPreview().observe(getViewLifecycleOwner(), bitmap -> {
            canvas.setImage(bitmap);
            viewModel.onPreviewShown(bitmap);
        });
        viewModel.getFileName().observe(getViewLifecycleOwner(), name -> {
            fileName.setText(name != null && !name.isEmpty()
                    ? name : getString(R.string.scan_default_file_name));
            SummaryCard.refreshHint(summaryCard, AppPreferences.HINT_CENSOR);
        });
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
            if (hidden) {
                // Leaving the tab (to Scan with Scan Result, say) ends full screen, rather than
                // coming back to it later without the bottom navigation.
                viewModel.setFullScreen(false);
            }
            updateControls();
            updateRecentsPreview();
            applyFullScreen();
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
    public void onDestroyView() {
        canvas.removeCallbacks(hideBrushPreview);
        com.doubleangels.redact.ui.SecureWindow.setPhotoOpen(requireActivity(), false);
        setImmersive(false);
        if (effectMenu != null) {
            effectMenu.dismiss();
            effectMenu = null;
        }
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
            Notice.show(requireContext(), R.string.share_error_generic, false);
        }
    }

    private void setUpTools(@NonNull View view) {
        Slider brushSize = view.findViewById(R.id.censorBrushSize);
        // The tool and brush size used last time; both are saved the moment they change.
        currentTool = toolFor(AppPreferences.getCensorTool(requireContext()));
        float size = AppPreferences.getCensorBrushSize(requireContext(), brushSize.getValue());
        brushSize.setValue(Math.max(brushSize.getValueFrom(), Math.min(brushSize.getValueTo(), Math.round(size))));
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
        for (CensorCanvasView.Tool tool : CensorCanvasView.Tool.values()) {
            view.findViewById(buttonFor(tool)).setOnClickListener(v -> {
                Haptics.tick(v);
                currentTool = tool;
                AppPreferences.setCensorTool(requireContext(), tool.name());
                applyTool();
            });
        }
        fullScreenButton.setOnClickListener(v -> {
            Haptics.tick(v);
            setFullScreen(!viewModel.isFullScreen());
        });
        applyTool();
    }

    /** Puts the current tool on the canvas and marks its button, the only one checked. */
    private void applyTool() {
        canvas.setTool(currentTool);
        for (CensorCanvasView.Tool tool : CensorCanvasView.Tool.values()) {
            ((MaterialButton) toolPill.findViewById(buttonFor(tool))).setChecked(tool == currentTool);
        }
        updateBrushSizeBar();
    }

    /** The brush size bar shows with Brush on a photo, and the status moves down to clear it. */
    private void updateBrushSizeBar() {
        boolean visible = currentTool == CensorCanvasView.Tool.BRUSH && isPhotoShown();
        brushSizeBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        float density = getResources().getDisplayMetrics().density;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) statusText.getLayoutParams();
        int top = Math.round((visible ? 12 + 48 + 8 : 12) * density);
        if (lp.topMargin != top) {
            lp.topMargin = top;
            statusText.setLayoutParams(lp);
        }
    }

    private boolean isPhotoShown() {
        CensorViewModel.State state = viewModel.getState().getValue();
        return state == CensorViewModel.State.READY || state == CensorViewModel.State.SAVING;
    }

    private void setFullScreen(boolean fullScreen) {
        viewModel.setFullScreen(fullScreen);
        applyFullScreen();
    }

    /**
     * Shows the photo on its own, filling the screen, while full screen is on and the photo is
     * open: the card above it (or beside it, in landscape), the bottom navigation and the system
     * bars all hide. The tools stay, over the photo.
     */
    private void applyFullScreen() {
        CensorViewModel.State state = viewModel.getState().getValue();
        boolean open = state != null && state != CensorViewModel.State.NONE;
        if (state == CensorViewModel.State.LOAD_FAILED || state == CensorViewModel.State.UNSUPPORTED) {
            // Nothing to edit, and the card with Clear and Select must be in reach.
            viewModel.setFullScreen(false);
        }
        // Loading a replacement keeps full screen, so it does not drop out and back in.
        boolean on = viewModel.isFullScreen() && open && !isHidden();
        toolPill.setVisibility(isPhotoShown() ? View.VISIBLE : View.GONE);
        updateBrushSizeBar();
        int label = on ? R.string.censor_exit_full_screen : R.string.censor_full_screen;
        fullScreenButton.setIconResource(on ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        fullScreenButton.setContentDescription(getString(label));
        fullScreenButton.setTooltipText(getString(label));
        if (column != null) {
            column.setVisibility(open && !on ? View.VISIBLE : View.GONE);
        } else {
            summaryCard.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        fullScreenCallback.setEnabled(on);
        setImmersive(on);
    }

    private void setImmersive(boolean immersive) {
        if (this.immersive == immersive) {
            return;
        }
        this.immersive = immersive;
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setImmersive(immersive);
        }
    }

    /** The tool for a saved tool name; Rectangle when there is none. */
    @NonNull
    private static CensorCanvasView.Tool toolFor(@Nullable String name) {
        for (CensorCanvasView.Tool tool : CensorCanvasView.Tool.values()) {
            if (tool.name().equals(name)) {
                return tool;
            }
        }
        return CensorCanvasView.Tool.RECT;
    }

    private static int buttonFor(@NonNull CensorCanvasView.Tool tool) {
        return switch (tool) {
            case BRUSH -> R.id.censorToolBrush;
            case MOVE -> R.id.censorToolMove;
            default -> R.id.censorToolRect;
        };
    }

    private void setUpEffects() {
        effectButton.setOnClickListener(v -> {
            Haptics.tick(v);
            showEffectMenu(v);
        });
        viewModel.getEffect().observe(getViewLifecycleOwner(), effect -> {
            if (effect == null) {
                return;
            }
            effectButton.setIconResource(iconFor(effect));
            String label = getString(R.string.censor_effect_current, getString(labelFor(effect)));
            effectButton.setContentDescription(label);
            effectButton.setTooltipText(label);
        });
    }

    /** Pixelate, Black and Blur, with the current one checked. */
    private void showEffectMenu(@NonNull View anchor) {
        if (effectMenu != null) {
            return;
        }
        androidx.appcompat.widget.PopupMenu popup =
                new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        effectMenu = popup;
        popup.setOnDismissListener(menu -> effectMenu = null);
        android.view.Menu menu = popup.getMenu();
        CensorEffects.Effect current = viewModel.getEffect().getValue();
        CensorEffects.Effect[] effects = CensorEffects.Effect.values();
        for (int i = 0; i < effects.length; i++) {
            menu.add(0, i, i, labelFor(effects[i])).setIcon(iconFor(effects[i]))
                    .setChecked(effects[i] == current);
        }
        menu.setGroupCheckable(0, true, true);
        popup.setForceShowIcon(true);
        popup.setOnMenuItemClickListener(item -> {
            chooseEffect(effects[item.getItemId()]);
            return true;
        });
        popup.show();
    }

    @androidx.annotation.VisibleForTesting
    void chooseEffect(@NonNull CensorEffects.Effect effect) {
        viewModel.setEffect(effect);
        updateControls();
    }

    private static int iconFor(@NonNull CensorEffects.Effect effect) {
        return switch (effect) {
            case BLACK -> R.drawable.ic_effect_black;
            case BLUR -> R.drawable.ic_effect_blur;
            default -> R.drawable.ic_censor;
        };
    }

    private static int labelFor(@NonNull CensorEffects.Effect effect) {
        return switch (effect) {
            case BLACK -> R.string.censor_effect_black;
            case BLUR -> R.string.censor_effect_blur;
            default -> R.string.censor_effect_pixelate;
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
        canvas.setEnabled(state == CensorViewModel.State.READY);
        updateControls();
        applyFullScreen();
    }

    /**
     * The undo pill floats over the photo, so it shows only while it holds something to use; it
     * stays up while a copy saves so it does not blink out and back.
     */
    private void updateHistoryControls() {
        CensorViewModel.State state = viewModel.getState().getValue();
        boolean editing = state == CensorViewModel.State.READY
                || state == CensorViewModel.State.SAVING;
        boolean useful = viewModel.canUndo() || viewModel.canRedo()
                || deleteButton.getVisibility() == View.VISIBLE;
        historyControls.setVisibility(editing && useful ? View.VISIBLE : View.GONE);
    }

    private void updateControls() {
        CensorViewModel.State state = viewModel.getState().getValue();
        boolean ready = state == CensorViewModel.State.READY;
        boolean saving = state == CensorViewModel.State.SAVING;
        boolean unsaved = viewModel.hasUnsavedEdits();
        undoButton.setEnabled(ready && viewModel.canUndo());
        redoButton.setEnabled(ready && viewModel.canRedo());
        updateHistoryControls();

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
        // The tool pill stops 12dp above the chip while it shows (the chip's 48dp touch target
        // has 8dp of it below its 32dp shape), and runs to 12dp from the bottom otherwise.
        ViewGroup.MarginLayoutParams pill = (ViewGroup.MarginLayoutParams) toolPill.getLayoutParams();
        int bottom = Math.round((zoomed ? 12 + 48 + 4 : 12) * getResources().getDisplayMetrics().density);
        if (pill.bottomMargin != bottom) {
            pill.bottomMargin = bottom;
            toolPill.setLayoutParams(pill);
        }
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
            Notice.show(requireContext(), R.string.share_error_generic, false);
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
