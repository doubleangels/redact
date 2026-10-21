package com.doubleangels.redact;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.doubleangels.redact.notifications.LocalNotifications;
import com.doubleangels.redact.permission.PermissionManager;
import com.doubleangels.redact.permission.PermissionStatusHelper;
import com.doubleangels.redact.permission.PermissionStatusHelper.Status;
import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;

/**
 * The first-start introduction. Three pages explain what Redact does, then each permission gets
 * its own page that says what it is for and asks for it right there, and a last page sums up what
 * was chosen. {@link MainActivity} opens it on a fresh install in place of the old run of cold
 * permission prompts; Settings › Show Introduction opens it again with {@link #EXTRA_REPLAY}.
 * It finishes with RESULT_OK once the user reaches the end, or RESULT_CANCELED when they back out
 * of the first page.
 */
public class OnboardingActivity extends AppCompatActivity {

    /** Set when opened from Settings: the last button reads Done instead of Start Using Redact. */
    public static final String EXTRA_REPLAY = "replay";

    private static final String STATE_ASKED = "asked";
    private static final String STATE_PENDING = "pending_step";
    /** Long enough to see the "allowed" line appear before the next page slides in. */
    private static final long ADVANCE_AFTER_GRANT_MS = 700;

    @VisibleForTesting
    enum Step { WELCOME, HIDDEN_DETAILS, PRIVATE, CHOICES, MEDIA, LOCATION, NOTIFICATIONS, DONE }

    private static final Step[] STEPS = Step.values();

    /** One point on a page: an icon and a sentence. */
    private record Point(@DrawableRes int icon, @NonNull CharSequence text) {}

    private ViewPager2 pager;
    private PageAdapter adapter;
    private LinearProgressIndicator progress;
    private MaterialButton back;
    private MaterialButton skip;
    private MaterialButton primary;
    private MaterialButton secondary;
    private boolean replay;
    /** Which permission pages have already shown the system prompt, so they stop offering it. */
    private boolean[] asked = new boolean[STEPS.length];
    /** The page whose permission prompt is open, or -1. */
    private int pendingStep = -1;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> onPermissionResult());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        com.doubleangels.redact.ui.SecureWindow.apply(this);
        setContentView(R.layout.activity_onboarding);

        replay = getIntent().getBooleanExtra(EXTRA_REPLAY, false);
        if (savedInstanceState != null) {
            boolean[] saved = savedInstanceState.getBooleanArray(STATE_ASKED);
            if (saved != null && saved.length == STEPS.length) {
                asked = saved;
            }
            pendingStep = savedInstanceState.getInt(STATE_PENDING, -1);
        }

        pager = findViewById(R.id.onboardingPager);
        progress = findViewById(R.id.onboardingProgress);
        back = findViewById(R.id.onboardingBack);
        skip = findViewById(R.id.onboardingSkip);
        primary = findViewById(R.id.onboardingPrimary);
        secondary = findViewById(R.id.onboardingSecondary);

        adapter = new PageAdapter();
        pager.setAdapter(adapter);
        // Pages move only with the buttons, so nobody swipes past a permission by accident.
        pager.setUserInputEnabled(false);
        pager.setOffscreenPageLimit(1);
        pager.setPageTransformer(OnboardingActivity::transformPage);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateControls();
                announcePage(position);
            }
        });

        back.setOnClickListener(v -> goBack());
        skip.setOnClickListener(v -> goTo(Step.CHOICES));
        primary.setOnClickListener(v -> onPrimary());
        secondary.setOnClickListener(v -> onSecondary());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });

        updateControls();
        applyInsets();
        if (savedInstanceState == null) {
            SentryManager.logEvent("lifecycle", "The introduction was shown.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The user may have changed a permission in system Settings and come back.
        refresh();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBooleanArray(STATE_ASKED, asked);
        outState.putInt(STATE_PENDING, pendingStep);
    }

    @NonNull
    private Step current() {
        return STEPS[pager.getCurrentItem()];
    }

    private void goTo(@NonNull Step step) {
        pager.setCurrentItem(step.ordinal(), true);
    }

    private void goNext() {
        int next = pager.getCurrentItem() + 1;
        if (next < STEPS.length) {
            pager.setCurrentItem(next, true);
        } else {
            finishIntroduction(RESULT_OK);
        }
    }

    private void goBack() {
        int previous = pager.getCurrentItem() - 1;
        if (previous >= 0) {
            pager.setCurrentItem(previous, true);
        } else {
            finishIntroduction(replay ? RESULT_OK : RESULT_CANCELED);
        }
    }

    private void finishIntroduction(int resultCode) {
        if (resultCode == RESULT_OK) {
            SentryManager.logEvent("lifecycle", "The introduction finished.");
        }
        setResult(resultCode);
        finish();
    }

    // ---- Permission state ----

    private static boolean isPermissionStep(@NonNull Step step) {
        return step == Step.MEDIA || step == Step.LOCATION || step == Step.NOTIFICATIONS;
    }

    @VisibleForTesting
    @NonNull
    Status status(@NonNull Step step) {
        return switch (step) {
            case MEDIA -> PermissionStatusHelper.getMediaAccessStatus(this);
            case LOCATION -> PermissionStatusHelper.getLocationStatus(this);
            // Both Android's permission and Redact's own notification setting must be on.
            case NOTIFICATIONS -> LocalNotifications.canPostNotifications(this)
                    ? Status.GRANTED : Status.DENIED;
            default -> Status.NOT_REQUIRED;
        };
    }

    private static boolean isAllowed(@NonNull Status status) {
        return status == Status.GRANTED || status == Status.PARTIAL || status == Status.NOT_REQUIRED;
    }

    /** Whether this permission page still offers its Allow button. */
    private boolean canAsk(@NonNull Step step) {
        return status(step) == Status.DENIED && !asked[step.ordinal()];
    }

    private void requestPermission(@NonNull Step step) {
        String[] permissions = switch (step) {
            case MEDIA -> PermissionManager.collectMissingRuntimePermissions(this).toArray(new String[0]);
            case LOCATION -> new String[] {Manifest.permission.ACCESS_MEDIA_LOCATION};
            case NOTIFICATIONS -> needsNotificationPermission()
                    ? new String[] {Manifest.permission.POST_NOTIFICATIONS} : new String[0];
            default -> new String[0];
        };
        if (permissions.length == 0) {
            // Nothing for Android to ask: on older versions notifications only need Redact's setting.
            pendingStep = step.ordinal();
            onPermissionResult();
            return;
        }
        pendingStep = step.ordinal();
        permissionLauncher.launch(permissions);
    }

    private void onPermissionResult() {
        if (pendingStep < 0) {
            return;
        }
        Step step = STEPS[pendingStep];
        pendingStep = -1;
        asked[step.ordinal()] = true;
        if (step == Step.NOTIFICATIONS && hasNotificationPermission()) {
            // Allowing notifications here means the user wants to hear when Clean and Convert finish.
            AppPreferences.setNotificationsEnabled(this, true);
            AppPreferences.setCleanNotificationsEnabled(this, true);
            AppPreferences.setConvertNotificationsEnabled(this, true);
        }
        refresh();
        if (isAllowed(status(step))) {
            pager.postDelayed(() -> {
                if (!isFinishing() && current() == step) {
                    goNext();
                }
            }, ADVANCE_AFTER_GRANT_MS);
        }
    }

    private static boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    @android.annotation.SuppressLint("InlinedApi") // guarded by needsNotificationPermission()
    private boolean hasNotificationPermission() {
        return !needsNotificationPermission() || ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void openSystemSettings(@NonNull Step step) {
        Intent intent;
        if (step == Step.NOTIFICATIONS && hasNotificationPermission()) {
            intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            SentryManager.recordException(e);
        }
    }

    // ---- Buttons ----

    private void onPrimary() {
        Step step = current();
        if (isPermissionStep(step) && canAsk(step)) {
            requestPermission(step);
        } else {
            goNext();
        }
    }

    private void onSecondary() {
        Step step = current();
        if (isPermissionStep(step) && !canAsk(step) && status(step) == Status.DENIED) {
            openSystemSettings(step);
        } else {
            goNext();
        }
    }

    private void refresh() {
        adapter.notifyItemRangeChanged(0, STEPS.length);
        updateControls();
    }

    private void updateControls() {
        Step step = current();
        int position = step.ordinal();

        back.setVisibility(position > 0 ? View.VISIBLE : View.INVISIBLE);
        skip.setVisibility(position < Step.CHOICES.ordinal() ? View.VISIBLE : View.INVISIBLE);
        progress.setProgressCompat((position + 1) * 100 / STEPS.length, true);
        progress.setContentDescription(getString(R.string.onboarding_progress,
                position + 1, STEPS.length));

        @StringRes int primaryText;
        @StringRes int secondaryText = 0;
        if (isPermissionStep(step)) {
            Status status = status(step);
            if (canAsk(step)) {
                primaryText = allowLabel(step);
                secondaryText = R.string.onboarding_not_now;
            } else {
                primaryText = R.string.onboarding_continue;
                if (status == Status.DENIED) {
                    secondaryText = R.string.onboarding_open_settings;
                }
            }
        } else {
            primaryText = switch (step) {
                case WELCOME -> R.string.onboarding_get_started;
                case CHOICES -> R.string.onboarding_continue;
                case DONE -> replay ? R.string.onboarding_done : R.string.onboarding_start;
                default -> R.string.onboarding_next;
            };
        }
        primary.setText(primaryText);
        secondary.setVisibility(secondaryText != 0 ? View.VISIBLE : View.INVISIBLE);
        if (secondaryText != 0) {
            secondary.setText(secondaryText);
        }
    }

    @StringRes
    private static int allowLabel(@NonNull Step step) {
        return switch (step) {
            case MEDIA -> R.string.onboarding_media_allow;
            case LOCATION -> R.string.onboarding_location_allow;
            default -> R.string.onboarding_notifications_allow;
        };
    }

    // ---- Page content ----

    private void bind(@NonNull PageAdapter.Holder holder, @NonNull Step step) {
        @DrawableRes int icon;
        @StringRes int title;
        CharSequence body;
        List<Point> points = new ArrayList<>();
        switch (step) {
            case WELCOME -> {
                icon = R.drawable.ic_shield;
                title = R.string.onboarding_welcome_title;
                body = getText(R.string.onboarding_welcome_body);
                points.add(point(R.drawable.ic_clean, R.string.onboarding_welcome_clean));
                points.add(point(R.drawable.ic_scan, R.string.onboarding_welcome_scan));
                points.add(point(R.drawable.ic_convert, R.string.onboarding_welcome_convert));
            }
            case HIDDEN_DETAILS -> {
                icon = R.drawable.ic_scan;
                title = R.string.onboarding_hidden_title;
                body = getText(R.string.onboarding_hidden_body);
                points.add(point(R.drawable.ic_map_24, R.string.onboarding_hidden_location));
                points.add(point(R.drawable.ic_event, R.string.onboarding_hidden_time));
                points.add(point(R.drawable.ic_camera_24, R.string.onboarding_hidden_device));
            }
            case PRIVATE -> {
                icon = R.drawable.ic_lock;
                title = R.string.onboarding_private_title;
                body = getText(R.string.onboarding_private_body);
                points.add(point(R.drawable.ic_shield, R.string.onboarding_private_offline));
                points.add(point(R.drawable.ic_person, R.string.onboarding_private_no_ads));
                points.add(point(R.drawable.ic_info_outline_20, R.string.onboarding_private_open_source));
            }
            case CHOICES -> {
                icon = R.drawable.ic_settings;
                title = R.string.onboarding_choices_title;
                body = getText(R.string.onboarding_choices_body);
                points.add(point(R.drawable.ic_image, R.string.onboarding_choices_media));
                points.add(point(R.drawable.ic_map_24, R.string.onboarding_choices_location));
                points.add(point(R.drawable.ic_notification, R.string.onboarding_choices_notifications));
            }
            case MEDIA -> {
                icon = R.drawable.ic_image;
                title = R.string.onboarding_media_title;
                body = getText(R.string.onboarding_media_body);
                points.add(point(R.drawable.ic_check_circle, R.string.onboarding_media_point_pick));
                points.add(point(R.drawable.ic_lock, R.string.onboarding_media_point_private));
                points.add(point(R.drawable.ic_settings,
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                                ? R.string.onboarding_media_point_partial
                                : R.string.onboarding_media_point_change));
            }
            case LOCATION -> {
                icon = R.drawable.ic_map_24;
                title = R.string.onboarding_location_title;
                body = getText(R.string.onboarding_location_body);
                points.add(point(R.drawable.ic_info_outline_20, R.string.onboarding_location_point_optional));
                points.add(point(R.drawable.ic_lock, R.string.onboarding_location_point_private));
                points.add(point(R.drawable.ic_settings, R.string.onboarding_location_point_change));
            }
            case NOTIFICATIONS -> {
                icon = R.drawable.ic_notification;
                title = R.string.onboarding_notifications_title;
                body = getText(R.string.onboarding_notifications_body);
                points.add(point(R.drawable.ic_info_outline_20,
                        R.string.onboarding_notifications_point_optional));
                points.add(point(R.drawable.ic_check_circle,
                        R.string.onboarding_notifications_point_finished));
                points.add(point(R.drawable.ic_person, R.string.onboarding_notifications_point_no_ads));
            }
            default -> {
                icon = R.drawable.ic_check_circle;
                title = R.string.onboarding_done_title;
                body = getText(R.string.onboarding_done_body);
                points.addAll(summary());
            }
        }

        holder.icon.setImageResource(icon);
        holder.title.setText(title);
        holder.body.setText(body);
        bindStatus(holder, step);
        holder.points.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (Point point : points) {
            View row = inflater.inflate(R.layout.item_onboarding_point, holder.points, false);
            ((ImageView) row.findViewById(R.id.onboardingPointIcon)).setImageResource(point.icon());
            ((TextView) row.findViewById(R.id.onboardingPointText)).setText(point.text());
            holder.points.addView(row);
        }
    }

    @NonNull
    private Point point(@DrawableRes int icon, @StringRes int text) {
        return new Point(icon, getText(text));
    }

    /** The last page: one sentence per permission saying what the user chose. */
    @NonNull
    private List<Point> summary() {
        List<Point> rows = new ArrayList<>();
        Status media = status(Step.MEDIA);
        rows.add(summaryRow(isAllowed(media), media == Status.PARTIAL
                ? R.string.onboarding_done_media_partial
                : isAllowed(media) ? R.string.onboarding_done_media_granted
                        : R.string.onboarding_done_media_denied));
        boolean location = status(Step.LOCATION) == Status.GRANTED;
        rows.add(summaryRow(location, location ? R.string.onboarding_done_location_granted
                : R.string.onboarding_done_location_denied));
        boolean notifications = status(Step.NOTIFICATIONS) == Status.GRANTED;
        rows.add(summaryRow(notifications, notifications
                ? R.string.onboarding_done_notifications_granted
                : R.string.onboarding_done_notifications_denied));
        return rows;
    }

    @NonNull
    private Point summaryRow(boolean allowed, @StringRes int text) {
        return point(allowed ? R.drawable.ic_check_circle : R.drawable.ic_info_outline_20, text);
    }

    /** The line under a permission page's explanation saying where that permission stands. */
    private void bindStatus(@NonNull PageAdapter.Holder holder, @NonNull Step step) {
        if (!isPermissionStep(step)) {
            holder.status.setVisibility(View.GONE);
            return;
        }
        Status status = status(step);
        @StringRes int text;
        boolean positive = isAllowed(status);
        if (status == Status.PARTIAL) {
            text = R.string.onboarding_status_partial;
        } else if (positive) {
            text = step == Step.NOTIFICATIONS ? R.string.onboarding_status_notifications_on
                    : R.string.onboarding_status_granted;
        } else if (status == Status.BLOCKED) {
            text = R.string.onboarding_status_blocked;
        } else if (asked[step.ordinal()]) {
            text = R.string.onboarding_status_denied;
        } else {
            holder.status.setVisibility(View.GONE);
            return;
        }
        int background = MaterialColors.getColor(this, positive
                ? com.google.android.material.R.attr.colorPrimaryContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHigh, 0);
        int foreground = MaterialColors.getColor(this, positive
                ? com.google.android.material.R.attr.colorOnPrimaryContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant, 0);
        holder.status.setCardBackgroundColor(background);
        holder.statusText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                positive ? R.drawable.ic_check_circle : R.drawable.ic_info_outline_20, 0, 0, 0);
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                holder.statusText, ColorStateList.valueOf(foreground));
        holder.statusText.setTextColor(foreground);
        holder.statusText.setText(text);
        holder.status.setVisibility(View.VISIBLE);
    }

    // ---- Motion and accessibility ----

    /** Pages fade and drift slightly as they slide, rather than moving as one flat sheet. */
    private static void transformPage(@NonNull View page, float position) {
        float offset = Math.abs(position);
        page.setAlpha(offset >= 1f ? 0f : 1f - offset);
        page.setTranslationX(-position * page.getWidth() * 0.25f);
        float scale = 1f - Math.min(offset, 1f) * 0.04f;
        page.setScaleX(scale);
        page.setScaleY(scale);
    }

    /** Moves screen-reader focus to the new page's title, so the change is announced. */
    private void announcePage(int position) {
        pager.post(() -> {
            RecyclerView recycler = (RecyclerView) pager.getChildAt(0);
            RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position);
            if (holder instanceof PageAdapter.Holder page) {
                page.title.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
            }
        });
    }

    private void applyInsets() {
        View root = findViewById(R.id.onboardingRoot);
        int barsAndCutout = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets outer = insets.getInsets(barsAndCutout);
            v.setPadding(outer.left, outer.top, outer.right, outer.bottom);
            return insets;
        });
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            bind(holder, STEPS[position]);
        }

        @Override
        public int getItemCount() {
            return STEPS.length;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView body;
            final MaterialCardView status;
            final TextView statusText;
            final ViewGroup points;

            Holder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.onboardingIcon);
                title = itemView.findViewById(R.id.onboardingTitle);
                body = itemView.findViewById(R.id.onboardingBody);
                status = itemView.findViewById(R.id.onboardingStatus);
                statusText = itemView.findViewById(R.id.onboardingStatusText);
                points = itemView.findViewById(R.id.onboardingPoints);
                icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(itemView,
                        com.google.android.material.R.attr.colorOnPrimaryContainer)));
            }
        }
    }

    /** For tests: the page currently showing. */
    @VisibleForTesting
    @NonNull
    Step currentStep() {
        return current();
    }

    /** For tests: whether a permission page has already shown its prompt. */
    @VisibleForTesting
    boolean wasAsked(@NonNull Step step) {
        return asked[step.ordinal()];
    }

}
