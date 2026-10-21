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
    /** Each tap of a zoom button doubles or halves the zoom. */
    private static final float ZOOM_STEP = 2f;

    private ActivityResultLauncher<String[]> photoPickerLauncher;
    private CensorViewModel viewModel;
    private View emptyState;
    private View content;
    private CensorCanvasView canvas;
    private View loading;
    private TextView message;
    private TextView fileName;
    private View toolsCard;
    private View zoomControls;
    private View zoomInButton;
    private View zoomOutButton;
    private View undoButton;
    private View redoButton;
    private TextView statusText;
    private ExtendedFloatingActionButton saveButton;
    private View shareButton;
    private View scanResultButton;
    @Nullable
    private androidx.appcompat.app.AlertDialog replaceDialog;

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
        zoomControls = view.findViewById(R.id.censorZoomControls);
        zoomInButton = view.findViewById(R.id.censorZoomIn);
        zoomOutButton = view.findViewById(R.id.censorZoomOut);
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
        setUpEffects(view);
        canvas.setListener(new CensorCanvasView.Listener() {
            @Override
            public void onRegionDrawn(@NonNull com.doubleangels.redact.media.CensorRegion region) {
                viewModel.addRegion(region);
            }

            @Override
            public void onRegionChanged(int index, @NonNull com.doubleangels.redact.media.CensorRegion region) {
                viewModel.replaceRegion(index, region);
            }
        });
        zoomInButton.setOnClickListener(v -> {
            Haptics.tick(v);
            canvas.zoomBy(ZOOM_STEP);
        });
        zoomOutButton.setOnClickListener(v -> {
            Haptics.tick(v);
            canvas.zoomBy(1f / ZOOM_STEP);
        });
        canvas.setZoomListener((zoom, canZoomIn, canZoomOut) -> {
            zoomInButton.setEnabled(canZoomIn);
            zoomOutButton.setEnabled(canZoomOut);
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
        }
    }

    @Override
    public void onDestroyView() {
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
            Toast.makeText(requireContext(), R.string.share_error_generic, Toast.LENGTH_SHORT).show();
        }
    }

    private void setUpTools(@NonNull View view) {
        MaterialButtonToggleGroup tools = view.findViewById(R.id.censorToolGroup);
        Slider brushSize = view.findViewById(R.id.censorBrushSize);
        canvas.setBrushWidth(brushSize.getValue() * BRUSH_WIDTH_PER_STEP);
        brushSize.addOnChangeListener((slider, value, fromUser) ->
                canvas.setBrushWidth(value * BRUSH_WIDTH_PER_STEP));
        tools.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                applyTool(checkedId, brushSize);
            }
        });
        applyTool(tools.getCheckedButtonId(), brushSize);
    }

    private void applyTool(int checkedId, @NonNull Slider brushSize) {
        CensorCanvasView.Tool tool = checkedId == R.id.censorToolBrush ? CensorCanvasView.Tool.BRUSH
                : checkedId == R.id.censorToolMove ? CensorCanvasView.Tool.MOVE
                : CensorCanvasView.Tool.RECT;
        canvas.setTool(tool);
        brushSize.setVisibility(tool == CensorCanvasView.Tool.BRUSH ? View.VISIBLE : View.GONE);
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
            com.doubleangels.redact.ui.ReviewPrompt.askIfDue(this);
            return;
        }
        if (state == CensorViewModel.State.SAVE_FAILED) {
            Haptics.reject(saveButton);
            viewModel.acknowledgeSaveResult();
            return;
        }
        boolean open = state != CensorViewModel.State.NONE;
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
        zoomControls.setVisibility(state == CensorViewModel.State.READY
                || state == CensorViewModel.State.SAVING ? View.VISIBLE : View.GONE);
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
                .setPositiveButton(R.string.censor_discard, (d, w) -> onDiscard.run())
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
