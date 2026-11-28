package com.doubleangels.redact;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProvider;

import com.doubleangels.redact.media.CensorEffects;
import com.doubleangels.redact.media.CensorExporter;
import com.doubleangels.redact.media.CensorRegion;
import com.doubleangels.redact.media.VideoCensorRegion;
import com.doubleangels.redact.media.ProcessingResourceWarnings;
import com.doubleangels.redact.media.MediaItem;
import com.doubleangels.redact.sentry.AppMetrics;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.ui.CensorCanvasView;
import com.doubleangels.redact.ui.CensorViewModel;
import com.doubleangels.redact.ui.FloatingToolbar;
import com.doubleangels.redact.ui.FoldDialogs;
import com.doubleangels.redact.ui.Haptics;
import com.doubleangels.redact.ui.Notice;
import com.doubleangels.redact.ui.ReviewPrompt;
import com.doubleangels.redact.ui.ScanViewModel;
import com.doubleangels.redact.ui.SecureWindow;
import com.doubleangels.redact.ui.OpenResult;
import com.doubleangels.redact.ui.ShareResults;
import com.doubleangels.redact.ui.SummaryCard;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.Slider;

import java.text.NumberFormat;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * The Censor tab: covers parts of one photo (pixelate, black or blur), optionally crops it, and
 * saves a censored copy without any of the original's metadata. The photo comes from the picker here, from Scan, or
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
    /** The row of tools under the photo. */
    private View toolbar;
    private MaterialButton fullScreenButton;
    private View brushSizeBar;
    private View brushSizeDot;
    /** Add Area and the divider before it, shown only while an accessibility service is on. */
    private View addAreaButton;
    private View addAreaDivider;
    /** Delete beside the selected rectangle, and Reset Crop with the crop tool. */
    private View contextActions;
    /** The selected rectangle on screen, in the photo card's coordinates; null for none. */
    @Nullable
    private RectF selectionBounds;
    @Nullable
    private AccessibilityManager accessibilityManager;
    private final AccessibilityManager.AccessibilityStateChangeListener accessibilityListener =
            enabled -> updateAddArea();
    /** The landscape column holding the summary card; null in portrait. */
    @Nullable
    private View column;
    private View deleteButton;
    /** Shows with the crop tool while there is a crop, and puts the whole photo back. */
    private View resetCropButton;
    private Chip zoomChip;
    private View undoButton;
    private View redoButton;
    private TextView statusText;
    private ExtendedFloatingActionButton saveButton;
    private View shareButton;
    private View scanResultButton;
    private View openResultButton;
    @Nullable
    private AlertDialog replaceDialog;
    /** Whether this fragment last hid the bars, so they are only shown or hidden on a change. */
    private boolean immersive;
    /** The rectangle selected on the canvas, which Delete removes, or minus one. */
    private int selectedRectangle = -1;

    /** A video's timeline under the photo, gone for a photo. */
    private View videoControls;
    private TextView videoTime;
    private Slider videoPosition;
    /** When the selected area covers the video, shown while a rectangle is selected. */
    private View areaTimes;
    private TextView areaTimesLabel;
    private RangeSlider areaTimesSlider;
    private View wholeVideoButton;
    /** Whether the selected area moves, and its keyframes, shown while a rectangle is selected. */
    private View areaMotion;
    private MaterialSwitch areaMovesSwitch;
    private TextView keyframeCount;
    private View previousKeyframeButton;
    private View nextKeyframeButton;
    private View deleteKeyframeButton;
    /** Whether a thumb of {@link #areaTimesSlider} is being dragged; its range is set when it is let go. */
    private boolean draggingAreaTimes;
    /** The area's range as the slider last showed it, in milliseconds, to tell which end moved. */
    private float[] shownAreaTimes = {0f, 0f};

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
                        SentryManager.logEvent("censor", "A photo was selected in CensorFragment.");
                        viewModel.requestOpen(uri);
                    }
                });
        trashRequestLauncher = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    Uri trashed = pendingTrashSource;
                    pendingTrashSource = null;
                    // The original is gone, so there is nothing left to save another copy from.
                    if (result.getResultCode() == Activity.RESULT_OK && trashed != null
                            && viewModel != null && trashed.equals(viewModel.getSource())) {
                        viewModel.close();
                    }
                });
    }

    @Override
    boolean acceptsMedia() {
        return true;
    }

    /** Censor edits one photo: the first one dropped or pasted opens, as a pick would. */
    @Override
    void onMediaReceived(@NonNull List<Uri> uris) {
        if (!uris.isEmpty() && viewModel != null) {
            viewModel.requestOpen(uris.get(0));
        }
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
        SentryManager.logEvent("censor", "The CensorFragment view was created.");
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
        fileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        historyControls = view.findViewById(R.id.censorHistoryControls);
        toolbar = view.findViewById(R.id.censorToolbar);
        // Dragged over the photo by its grip, and left where it was put.
        FloatingToolbar.attach((ViewGroup) toolbar,
                view.findViewById(R.id.censorToolbarCard), view.findViewById(R.id.censorToolbarGrip));
        fullScreenButton = view.findViewById(R.id.censorFullScreenButton);
        brushSizeBar = view.findViewById(R.id.censorBrushSizeContainer);
        brushSizeDot = view.findViewById(R.id.censorBrushSizeDot);
        addAreaButton = view.findViewById(R.id.censorAddAreaButton);
        addAreaDivider = view.findViewById(R.id.censorAddAreaDivider);
        contextActions = view.findViewById(R.id.censorContextActions);
        column = view.findViewById(R.id.censorColumn);
        deleteButton = view.findViewById(R.id.censorDeleteButton);
        resetCropButton = view.findViewById(R.id.censorResetCropButton);
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
        openResultButton = view.findViewById(R.id.openResultButton);
        videoControls = view.findViewById(R.id.censorVideoControls);
        videoTime = view.findViewById(R.id.censorVideoTime);
        videoPosition = view.findViewById(R.id.censorVideoPosition);
        areaTimes = view.findViewById(R.id.censorAreaTimes);
        areaTimesLabel = view.findViewById(R.id.censorAreaTimesLabel);
        areaTimesSlider = view.findViewById(R.id.censorAreaTimesSlider);
        wholeVideoButton = view.findViewById(R.id.censorWholeVideoButton);
        areaMotion = view.findViewById(R.id.censorAreaMotion);
        areaMovesSwitch = view.findViewById(R.id.censorAreaMovesSwitch);
        keyframeCount = view.findViewById(R.id.censorKeyframeCount);
        previousKeyframeButton = view.findViewById(R.id.censorPreviousKeyframe);
        nextKeyframeButton = view.findViewById(R.id.censorNextKeyframe);
        deleteKeyframeButton = view.findViewById(R.id.censorDeleteKeyframe);

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
        resetCropButton.setOnClickListener(v -> {
            Haptics.tick(v);
            viewModel.setCrop(null);
        });
        undoButton.setOnClickListener(v -> {
            Haptics.tick(v);
            viewModel.undo();
        });
        redoButton.setOnClickListener(v -> {
            Haptics.tick(v);
            viewModel.redo();
        });
        saveButton.setOnClickListener(v -> {
            if (viewModel.isVideo()) {
                saveVideoAfterWarnings();
            } else {
                viewModel.save();
            }
        });
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
        openResultButton.setOnClickListener(v -> {
            Haptics.tick(v);
            CensorExporter.Result saved = viewModel.getLastSaved();
            if (saved != null) {
                OpenResult.open(requireContext(), saved.uri);
            }
        });
        setUpTools(view);
        setUpEffects();
        setUpVideoControls();
        // The photo's size changes with full screen and rotation, and the actions placed on it move.
        canvas.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                selectionBounds = canvas.selectedBoundsInView();
                updateContextActions();
            }
        });
        canvas.setListener(new CensorCanvasView.Listener() {
            @Override
            public void onRegionDrawn(@NonNull CensorRegion region) {
                viewModel.addRegion(region);
                Haptics.tick(canvas);
            }

            @Override
            public void onRegionChanged(int index, @NonNull CensorRegion region) {
                viewModel.replaceRegion(index, region);
                Haptics.tick(canvas);
            }

            @Override
            public void onRegionRemoved(int index) {
                canvas.clearSelection();
                viewModel.removeRegion(index);
                Haptics.tick(canvas);
            }

            @Override
            public void onSelectionChanged(int index) {
                if (index >= 0 && index != selectedRectangle) {
                    // A light tick tells the user the tap picked up the rectangle.
                    Haptics.tick(canvas);
                }
                selectedRectangle = index;
                selectionBounds = canvas.selectedBoundsInView();
                updateContextActions();
                updateVideoControls();
                // The effect buttons show the selected rectangle's effect, which they change.
                updateEffectButtons();
            }

            @Override
            public void onSelectionBoundsChanged(@Nullable RectF viewBounds) {
                selectionBounds = viewBounds;
                updateContextActions();
            }

            @Override
            public void onZoomChanged(float zoom) {
                showZoom(zoom);
            }

            @Override
            public void onCropChanged(@Nullable CensorRegion crop) {
                viewModel.setCrop(crop);
                Haptics.tick(canvas);
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
            // A video's moving area moves when another frame shows; it stays selected.
            canvas.setRegions(viewModel.regions(), viewModel.isVideo());
            canvas.setCrop(viewModel.getCrop());
            // Delete follows the selected rectangle, which a moving area moves between frames.
            selectionBounds = canvas.selectedBoundsInView();
            updateControls();
            updateEffectButtons();
            updateVideoControls();
        });
        viewModel.getSaveProgress().observe(getViewLifecycleOwner(), percent -> updateControls());
        viewModel.getState().observe(getViewLifecycleOwner(), this::render);
        viewModel.getPendingReplacement().observe(getViewLifecycleOwner(), this::askToReplace);
    }

    @Override
    public void onResume() {
        super.onResume();
        // An accessibility service turned on or off while the app was away.
        if (addAreaButton != null) {
            updateAddArea();
        }
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
        SecureWindow.setPhotoOpen(requireActivity(), photoShowing);
    }

    @Override
    public void onDestroyView() {
        canvas.removeCallbacks(hideBrushPreview);
        SecureWindow.setPhotoOpen(requireActivity(), false);
        setImmersive(false);
        if (accessibilityManager != null) {
            accessibilityManager.removeAccessibilityStateChangeListener(accessibilityListener);
            accessibilityManager = null;
        }
        if (replaceDialog != null) {
            replaceDialog.dismiss();
            replaceDialog = null;
        }
        super.onDestroyView();
    }

    private void pickPhoto() {
        try {
            photoPickerLauncher.launch(new String[] {"image/*", "video/*"});
        } catch (ActivityNotFoundException e) {
            AppMetrics.externalAppMissing(AppMetrics.EXTERNAL_FILE_PICKER);
            Notice.show(requireContext(), R.string.status_media_picker_fail, false);
        }
    }

    private void setUpTools(@NonNull View view) {
        Slider brushSize = view.findViewById(R.id.censorBrushSize);
        // The tool and brush size used last time; both are saved the moment they change.
        currentTool = toolFor(AppPreferences.getCensorTool(requireContext()));
        float size = AppPreferences.getCensorBrushSize(requireContext(), brushSize.getValue());
        brushSize.setValue(Math.max(brushSize.getValueFrom(), Math.min(brushSize.getValueTo(), Math.round(size))));
        canvas.setBrushWidth(brushSize.getValue() * BRUSH_WIDTH_PER_STEP);
        showBrushSizeDot(brushSize.getValue());
        // A circle of the brush's real size shows while the slider moves, since the size on the
        // photo depends on the zoom.
        brushSize.addOnChangeListener((slider, value, fromUser) -> {
            canvas.setBrushWidth(value * BRUSH_WIDTH_PER_STEP);
            showBrushSizeDot(value);
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
        accessibilityManager = (AccessibilityManager) requireContext()
                .getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (accessibilityManager != null) {
            accessibilityManager.addAccessibilityStateChangeListener(accessibilityListener);
        }
        updateAddArea();
        addAreaButton.setOnClickListener(v -> {
            Haptics.tick(v);
            // The added area is a rectangle, so its tool comes up to keep it selected for editing.
            if (currentTool != CensorCanvasView.Tool.RECT) {
                currentTool = CensorCanvasView.Tool.RECT;
                AppPreferences.setCensorTool(requireContext(), currentTool.name());
                applyTool();
            }
            canvas.addCentredRegion();
        });
        applyTool();
    }

    /** Sizes the dot beside the brush size slider to the brush: 6dp at 1, up to 24dp at 10. */
    private void showBrushSizeDot(float value) {
        int size = Math.round((6 + (value - 1) * 2) * getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = brushSizeDot.getLayoutParams();
        if (lp.width != size) {
            lp.width = size;
            lp.height = size;
            brushSizeDot.setLayoutParams(lp);
        }
    }

    /**
     * Add Area is for drawing without a drag (TalkBack, Switch Access), so it takes room in the
     * toolbar only while an accessibility service is on.
     */
    private void updateAddArea() {
        boolean shown = accessibilityManager != null && accessibilityManager.isEnabled();
        addAreaButton.setVisibility(shown ? View.VISIBLE : View.GONE);
        addAreaDivider.setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    /** Puts the current tool on the canvas and marks its button, the only one checked. */
    private void applyTool() {
        canvas.setTool(currentTool);
        for (CensorCanvasView.Tool tool : CensorCanvasView.Tool.values()) {
            ((MaterialButton) toolbar.findViewById(buttonFor(tool))).setChecked(tool == currentTool);
        }
        updateBrushSizeBar();
        updateHistoryControls();
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
     * bars all hide. The tools stay, under the photo.
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
        toolbar.setVisibility(isPhotoShown() ? View.VISIBLE : View.GONE);
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
        fitTimelineBesideActions();
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
            case CROP -> R.id.censorToolCrop;
            default -> R.id.censorToolRect;
        };
    }

    /** Pixelate, Blur and Black are one tap each in the toolbar. */
    private void setUpEffects() {
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            toolbar.findViewById(buttonFor(effect)).setOnClickListener(v -> {
                Haptics.tick(v);
                chooseEffect(effect);
            });
        }
        viewModel.getEffect().observe(getViewLifecycleOwner(), effect -> {
            canvas.setEffect(effect);
            updateEffectButtons();
        });
    }

    /**
     * Sets the effect for the areas covered next and, when a rectangle is selected, covers that one
     * with it too, as an edit that can be undone. Areas already covered otherwise keep theirs.
     */
    @VisibleForTesting
    void chooseEffect(@NonNull CensorEffects.Effect effect) {
        viewModel.setEffect(effect);
        if (selectedRectangle >= 0) {
            viewModel.setRegionEffect(selectedRectangle, effect);
        }
        updateControls();
        updateEffectButtons();
    }

    /** Checks the selected rectangle's effect, or the one new areas get when none is selected. */
    private void updateEffectButtons() {
        CensorEffects.Effect shown = selectedRectangle >= 0
                ? viewModel.regionEffect(selectedRectangle) : null;
        if (shown == null) {
            shown = viewModel.getEffect().getValue();
        }
        for (CensorEffects.Effect effect : CensorEffects.Effect.values()) {
            ((MaterialButton) toolbar.findViewById(buttonFor(effect))).setChecked(effect == shown);
        }
    }

    private static int buttonFor(@NonNull CensorEffects.Effect effect) {
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
            CensorExporter.Result saved = viewModel.getLastSaved();
            if (saved != null && !saved.downscaled) {
                // A short notice: a full-size copy needs nothing more from the user, so nothing
                // stays over the result. A smaller copy keeps its status, which says what to change.
                Notice.show(requireContext(), R.string.censor_saved);
            }
            viewModel.acknowledgeSaveResult();
            // Android's trash confirmation takes priority over asking for a rating.
            if (!requestTrashOriginal()) {
                ReviewPrompt.askIfDue(this);
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
            boolean video = viewModel.isSourceVideo();
            message.setText(state == CensorViewModel.State.UNSUPPORTED
                    ? (video ? R.string.censor_error_unsupported_video : R.string.censor_error_unsupported)
                    : (video ? R.string.censor_error_open_video : R.string.censor_error_open));
        }
        canvas.setEnabled(state == CensorViewModel.State.READY);
        updateControls();
        updateVideoControls();
        applyFullScreen();
    }

    /**
     * The pill with Undo, Redo and Full Screen shows whenever the photo does (and stays while a copy
     * saves), so it never blinks in and out; Undo and Redo are disabled when there is nothing to do.
     */
    private void updateHistoryControls() {
        historyControls.setVisibility(isPhotoShown() ? View.VISIBLE : View.GONE);
        updateContextActions();
    }

    /**
     * Shows Delete beside the selected rectangle, above it or, with no room there, below it, and
     * Reset Crop at the bottom middle while the crop tool shows a crop.
     */
    private void updateContextActions() {
        CensorViewModel.State state = viewModel.getState().getValue();
        boolean ready = state == CensorViewModel.State.READY;
        boolean cropShown = currentTool == CensorCanvasView.Tool.CROP && viewModel.getCrop() != null;
        RectF bounds = selectedRectangle >= 0 ? selectionBounds : null;
        resetCropButton.setVisibility(cropShown ? View.VISIBLE : View.GONE);
        resetCropButton.setEnabled(ready);
        deleteButton.setVisibility(bounds != null ? View.VISIBLE : View.GONE);
        boolean shown = isPhotoShown() && (cropShown || bounds != null);
        contextActions.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        float margin = 12 * density;
        float gap = 8 * density;
        float button = 48 * density;
        float width = button * ((cropShown ? 1 : 0) + (bounds != null ? 1 : 0));
        float height = button;
        float cardWidth = canvas.getWidth();
        float cardHeight = canvas.getHeight();
        float x;
        float y;
        if (bounds != null) {
            x = bounds.centerX() - width / 2f;
            y = bounds.top - gap - height;
            if (y < margin) {
                y = bounds.bottom + gap;
            }
        } else {
            x = (cardWidth - width) / 2f;
            y = cardHeight - margin - height;
        }
        contextActions.setTranslationX(clamp(x, margin, cardWidth - margin - width));
        contextActions.setTranslationY(clamp(y, margin, cardHeight - margin - height));
    }

    /** {@code value} kept between {@code min} and {@code max}, or at {@code min} when there is no room. */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
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
        boolean savedMatches = ready && saved != null && !unsaved && viewModel.hasEdits();
        shareButton.setVisibility(savedMatches ? View.VISIBLE : View.GONE);
        scanResultButton.setVisibility(savedMatches ? View.VISIBLE : View.GONE);
        openResultButton.setVisibility(savedMatches ? View.VISIBLE : View.GONE);

        if (saving) {
            Integer percent = viewModel.getSaveProgress().getValue();
            showStatus(viewModel.isVideo()
                    ? getString(R.string.progress_transcoding_percent, percent != null ? percent : 0)
                    : getString(R.string.status_processing));
        } else if (viewModel.isLastSaveFailed()) {
            showStatus(getString(R.string.censor_error_save));
        } else if (savedMatches && saved.downscaled) {
            showStatus(getString(R.string.censor_saved_downscaled));
        } else {
            statusText.setVisibility(View.GONE);
        }

        unsavedEditsCallback.setEnabled(!isHidden() && unsaved);
        fitTimelineBesideActions();

        // The hint explains how to start; once something is covered it only takes the photo's room.
        if (viewModel.hasEdits()) {
            summaryCard.findViewById(R.id.summaryHint).setVisibility(View.GONE);
        } else {
            SummaryCard.refreshHint(summaryCard, AppPreferences.HINT_CENSOR);
        }
    }

    private void setUpVideoControls() {
        LabelFormatter time = value -> DateUtils.formatElapsedTime(Math.round(value / 1000f));
        videoPosition.setLabelFormatter(time);
        areaTimesSlider.setLabelFormatter(time);
        areaTimesSlider.setValues(0f, 1f);
        videoPosition.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                viewModel.setPlayhead(Math.round(value) * 1000L);
                showVideoTime();
            }
        });
        areaTimesSlider.addOnSliderTouchListener(new RangeSlider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull RangeSlider slider) {
                draggingAreaTimes = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull RangeSlider slider) {
                draggingAreaTimes = false;
                applyAreaTimes();
            }
        });
        areaTimesSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser) {
                return;
            }
            List<Float> values = slider.getValues();
            // The frame at the end being moved shows, so the area's edge can be set exactly.
            float moved = values.get(0) != shownAreaTimes[0] ? values.get(0) : values.get(1);
            shownAreaTimes = new float[] {values.get(0), values.get(1)};
            viewModel.setPlayhead(Math.round(moved) * 1000L);
            showAreaTimesLabel(values.get(0), values.get(1));
            if (!draggingAreaTimes) {
                // Moved by keys or an accessibility action: no touch to wait for.
                applyAreaTimes();
            }
        });
        wholeVideoButton.setOnClickListener(v -> {
            if (selectedRectangle >= 0) {
                Haptics.tick(v);
                viewModel.setRegionTimes(selectedRectangle, 0L, VideoCensorRegion.END_OF_VIDEO);
            }
        });
        areaMovesSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (button.isPressed() || button.isFocused()) {
                Haptics.tick(button);
            }
            if (selectedRectangle >= 0 && checked != viewModel.isMoving(selectedRectangle)) {
                viewModel.setMoving(selectedRectangle, checked);
            }
        });
        previousKeyframeButton.setOnClickListener(v -> stepToKeyframe(false));
        nextKeyframeButton.setOnClickListener(v -> stepToKeyframe(true));
        deleteKeyframeButton.setOnClickListener(v -> {
            if (selectedRectangle >= 0) {
                Haptics.tick(v);
                viewModel.removeKeyframeAtPlayhead(selectedRectangle);
            }
        });
    }

    /** Shows the selected area's next keyframe, or its previous one. */
    private void stepToKeyframe(boolean forward) {
        if (selectedRectangle < 0) {
            return;
        }
        long time = viewModel.adjacentKeyframe(selectedRectangle, forward);
        if (time >= 0) {
            Haptics.tick(forward ? nextKeyframeButton : previousKeyframeButton);
            viewModel.setPlayhead(time);
            updateVideoControls();
        }
    }

    /** Gives the selected area the range on the slider, as an edit that can be undone. */
    private void applyAreaTimes() {
        if (selectedRectangle < 0) {
            return;
        }
        List<Float> values = areaTimesSlider.getValues();
        viewModel.setRegionTimes(selectedRectangle, Math.round(values.get(0)) * 1000L,
                Math.round(values.get(1)) * 1000L);
    }

    /**
     * Shows the timeline for a video (and only Rectangle among the drawing tools, as a video has
     * no brush), with the selected area's time range while one is selected.
     */
    private void updateVideoControls() {
        boolean video = viewModel.isVideo();
        View brush = toolbar.findViewById(R.id.censorToolBrush);
        brush.setVisibility(video ? View.GONE : View.VISIBLE);
        if (video && currentTool == CensorCanvasView.Tool.BRUSH) {
            // Not saved: the brush comes back for the next photo.
            currentTool = CensorCanvasView.Tool.RECT;
            applyTool();
        }
        boolean shown = video && isPhotoShown();
        videoControls.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (!shown) {
            return;
        }
        boolean ready = viewModel.getState().getValue() == CensorViewModel.State.READY;
        float durationMs = Math.max(1f, viewModel.getDurationUs() / 1000f);
        setRange(videoPosition, durationMs);
        videoPosition.setValue(clamp(viewModel.getPlayheadUs() / 1000f, 0f, durationMs));
        videoPosition.setEnabled(ready);
        showVideoTime();
        boolean selected = selectedRectangle >= 0 && selectedRectangle < viewModel.regions().size();
        areaTimes.setVisibility(selected ? View.VISIBLE : View.GONE);
        areaMotion.setVisibility(selected ? View.VISIBLE : View.GONE);
        if (selected) {
            showMotion(ready);
        }
        if (!selected || draggingAreaTimes) {
            return;
        }
        CensorViewModel.Span span = viewModel.regionTimes(selectedRectangle);
        setRange(areaTimesSlider, durationMs);
        float start = clamp(span.startUs() / 1000f, 0f, durationMs);
        float end = span.endUs() == VideoCensorRegion.END_OF_VIDEO
                ? durationMs : clamp(span.endUs() / 1000f, start, durationMs);
        areaTimesSlider.setValues(start, end);
        shownAreaTimes = new float[] {start, end};
        areaTimesSlider.setContentDescription(getString(R.string.censor_area_times, selectedRectangle + 1));
        areaTimesSlider.setEnabled(ready);
        showAreaTimesLabel(start, end);
        wholeVideoButton.setEnabled(ready && !span.isWhole());
    }

    /**
     * Shows whether the selected area moves and, when it does, how many keyframes it has and
     * which of the step and delete buttons apply at the playhead.
     */
    private void showMotion(boolean ready) {
        boolean moving = viewModel.isMoving(selectedRectangle);
        if (areaMovesSwitch.isChecked() != moving) {
            areaMovesSwitch.setChecked(moving);
        }
        areaMovesSwitch.setEnabled(ready);
        int keyframes = viewModel.keyframeTimes(selectedRectangle).size();
        keyframeCount.setVisibility(moving ? View.VISIBLE : View.INVISIBLE);
        keyframeCount.setText(getString(R.string.censor_keyframe_count, keyframes));
        previousKeyframeButton.setVisibility(moving ? View.VISIBLE : View.GONE);
        nextKeyframeButton.setVisibility(moving ? View.VISIBLE : View.GONE);
        deleteKeyframeButton.setVisibility(moving ? View.VISIBLE : View.GONE);
        previousKeyframeButton.setEnabled(ready && viewModel.adjacentKeyframe(selectedRectangle, false) >= 0);
        nextKeyframeButton.setEnabled(ready && viewModel.adjacentKeyframe(selectedRectangle, true) >= 0);
        deleteKeyframeButton.setEnabled(ready && viewModel.hasKeyframeAtPlayhead(selectedRectangle));
    }

    /** Sets {@code slider}'s range to 0 to {@code max}, moving its value into it first. */
    private static void setRange(@NonNull Slider slider, float max) {
        if (slider.getValueTo() != max) {
            slider.setValue(0f);
            slider.setValueTo(max);
        }
    }

    /** Sets {@code slider}'s range to 0 to {@code max}, moving its values into it first. */
    private static void setRange(@NonNull RangeSlider slider, float max) {
        if (slider.getValueTo() != max) {
            slider.setValues(0f, Math.min(max, slider.getValueTo()));
            slider.setValueTo(max);
        }
    }

    private void showVideoTime() {
        videoTime.setText(getString(R.string.censor_video_time,
                DateUtils.formatElapsedTime(viewModel.getPlayheadUs() / 1_000_000L),
                DateUtils.formatElapsedTime(viewModel.getDurationUs() / 1_000_000L)));
    }

    private void showAreaTimesLabel(float startMs, float endMs) {
        areaTimesLabel.setText(getString(R.string.censor_area_time_range,
                DateUtils.formatElapsedTime(Math.round(startMs / 1000f)),
                DateUtils.formatElapsedTime(Math.round(endMs / 1000f))));
    }

    /**
     * Saves a censored video after the same storage and memory warnings Clean gives, since the
     * whole video is encoded again. The checks read the file's size, so they run off the main thread.
     */
    private void saveVideoAfterWarnings() {
        Uri source = viewModel.getSource();
        if (source == null) {
            return;
        }
        Context app = requireContext().getApplicationContext();
        WeakReference<CensorFragment> screen = new WeakReference<>(this);
        new Thread(() -> {
            List<ProcessingResourceWarnings.Warning> warnings;
            try {
                warnings = ProcessingResourceWarnings.assess(app, List.of(new MediaItem(source, true, null)));
            } catch (RuntimeException e) {
                // The checks only warn; when they can't run, save without them.
                SentryManager.recordException(e);
                warnings = List.of();
            }
            List<ProcessingResourceWarnings.Warning> found = warnings;
            CensorFragment fragment = screen.get();
            Activity activity = fragment != null ? fragment.getActivity() : null;
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    if (fragment.isAdded()) {
                        ProcessingResourceWarnings.runWithWarnings(
                                fragment.requireActivity(), found, fragment.viewModel::save);
                    }
                });
            }
        }, "redact-censor-checks").start();
    }

    /**
     * In landscape full screen the column is hidden and the timeline reaches the right edge, where
     * Save Copy, Share, Scan Result and Open sit: it ends before the first of them that shows.
     * Otherwise it ends at the column (or the edge, in portrait) as laid out.
     */
    private void fitTimelineBesideActions() {
        if (column == null || videoControls == null) {
            return;
        }
        videoControls.post(() -> {
            if (getView() == null) {
                return;
            }
            View root = getView();
            int clear = 0;
            if (column.getVisibility() != View.VISIBLE && videoControls.getVisibility() == View.VISIBLE) {
                for (View action : new View[] {saveButton, shareButton, scanResultButton, openResultButton}) {
                    if (action.getVisibility() == View.VISIBLE && action.getWidth() > 0) {
                        clear = Math.max(clear, root.getWidth() - action.getLeft());
                    }
                }
            }
            if (!(videoControls.getLayoutParams() instanceof ConstraintLayout.LayoutParams lp)) {
                return;
            }
            // The column is gone in full screen, so the margin that applies is the gone one.
            float density = getResources().getDisplayMetrics().density;
            int end = clear > 0 ? clear + Math.round(8 * density) : Math.round(16 * density);
            if (lp.goneEndMargin != end) {
                lp.goneEndMargin = end;
                videoControls.setLayoutParams(lp);
            }
        });
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
        NumberFormat format = NumberFormat.getNumberInstance();
        format.setMaximumFractionDigits(rounded == Math.rint(rounded) ? 0 : 1);
        return format.format(rounded);
    }

    /**
     * After a save with Delete Originals After Cleaning on, asks Android (with its own confirmation,
     * the same one Clean uses) to move the uncensored original to the trash, when it is a photo or
     * video in the phone's media library. Returns whether the request was made.
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
            if (MediaStore.AUTHORITY.equals(source.getAuthority())) {
                // A photo picker item's number is the picker's own, not the library's.
                List<String> path = source.getPathSegments();
                if (path.isEmpty() || path.get(0).startsWith("picker")) {
                    return null;
                }
                media = source;
            } else {
                media = MediaStore.getMediaUri(requireContext(), source);
            }
            return media != null
                    ? CleanFragment.trashableMediaUri(media, viewModel.isVideo() ? "video/*" : "image/*") : null;
        } catch (Exception notMedia) {
            // A photo from another app's provider: leave it alone.
            return null;
        }
    }

    private void requestTrash(@NonNull Uri source, @NonNull Uri media) {
        try {
            pendingTrashSource = source;
            trashRequestLauncher.launch(new IntentSenderRequest.Builder(
                    MediaStore.createTrashRequest(requireContext().getContentResolver(),
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
        replaceDialog = FoldDialogs.place(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.censor_discard_title)
                .setMessage(R.string.censor_discard_message)
                .setNegativeButton(R.string.censor_keep_editing, (d, w) -> viewModel.cancelReplacement())
                .setPositiveButton(R.string.censor_discard, (d, w) -> viewModel.confirmReplacement())
                .setOnCancelListener(d -> viewModel.cancelReplacement())
                .setOnDismissListener(d -> replaceDialog = null)
                .show());
    }

    private void confirmDiscard(@NonNull Runnable onDiscard) {
        FoldDialogs.place(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.censor_discard_title)
                .setMessage(R.string.censor_discard_message)
                .setNegativeButton(R.string.censor_keep_editing, null)
                .setPositiveButton(R.string.censor_discard, (d, w) -> {
                    onDiscard.run();
                })
                .show());
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
