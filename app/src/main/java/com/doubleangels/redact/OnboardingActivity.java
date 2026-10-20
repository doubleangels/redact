package com.doubleangels.redact;

import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.doubleangels.redact.sentry.SentryManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * The first-start introduction: what metadata is, that everything stays on the phone, and why
 * Redact asks for each permission. {@link MainActivity} opens it on a fresh install before the
 * first-launch permission prompts, and Settings › Show Introduction opens it again with
 * {@link #EXTRA_REPLAY}, which never leads to the prompts. It always finishes with RESULT_OK.
 */
public class OnboardingActivity extends AppCompatActivity {

    /** Set when opened from Settings: the last button reads Done and the text drops "next". */
    public static final String EXTRA_REPLAY = "replay";

    /** One point on a page: an icon and a sentence. */
    @VisibleForTesting
    record Point(@DrawableRes int icon, @StringRes int text) {}

    /** One page of the introduction. */
    @VisibleForTesting
    record Page(@DrawableRes int icon, @StringRes int title, @StringRes int body,
            @NonNull List<Point> points) {}

    private ViewPager2 pager;
    private MaterialButton next;
    private LinearLayout dots;
    private List<Page> pages;
    private boolean replay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        com.doubleangels.redact.ui.SecureWindow.apply(this);
        setContentView(R.layout.activity_onboarding);

        replay = getIntent().getBooleanExtra(EXTRA_REPLAY, false);
        pages = pages(replay, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU);

        pager = findViewById(R.id.onboardingPager);
        next = findViewById(R.id.onboardingNext);
        dots = findViewById(R.id.onboardingDots);
        pager.setAdapter(new PageAdapter(pages));
        buildDots();

        findViewById(R.id.onboardingSkip).setOnClickListener(v -> finishIntroduction());
        next.setOnClickListener(v -> {
            int current = pager.getCurrentItem();
            if (current < pages.size() - 1) {
                pager.setCurrentItem(current + 1);
            } else {
                finishIntroduction();
            }
        });
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateControls(position);
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                int current = pager.getCurrentItem();
                if (current > 0) {
                    pager.setCurrentItem(current - 1);
                } else {
                    finishIntroduction();
                }
            }
        });
        updateControls(pager.getCurrentItem());
        applyInsets();
        SentryManager.logEvent("lifecycle", "The introduction was shown.");
    }

    /** The pages, in order. The notifications point only shows where Android asks for it. */
    @VisibleForTesting
    @NonNull
    static List<Page> pages(boolean replay, boolean asksForNotifications) {
        List<Point> permissions = new ArrayList<>();
        permissions.add(new Point(R.drawable.ic_image, R.string.onboarding_permission_media));
        permissions.add(new Point(R.drawable.ic_map_24, R.string.onboarding_permission_location));
        if (asksForNotifications) {
            permissions.add(new Point(R.drawable.ic_notification,
                    R.string.onboarding_permission_notifications));
        }
        return List.of(
                new Page(R.drawable.ic_scan, R.string.onboarding_metadata_title,
                        R.string.onboarding_metadata_body, List.of(
                                new Point(R.drawable.ic_map_24, R.string.onboarding_metadata_location),
                                new Point(R.drawable.ic_event, R.string.onboarding_metadata_time),
                                new Point(R.drawable.ic_camera_24, R.string.onboarding_metadata_device))),
                new Page(R.drawable.ic_lock, R.string.onboarding_private_title,
                        R.string.onboarding_private_body, List.of(
                                new Point(R.drawable.ic_image, R.string.onboarding_private_offline),
                                new Point(R.drawable.ic_person, R.string.onboarding_private_no_ads),
                                new Point(R.drawable.ic_info_outline_20,
                                        R.string.onboarding_private_open_source))),
                new Page(R.drawable.ic_settings, R.string.onboarding_permissions_title,
                        replay ? R.string.onboarding_permissions_body_replay
                                : R.string.onboarding_permissions_body,
                        List.copyOf(permissions)));
    }

    private void finishIntroduction() {
        setResult(RESULT_OK);
        finish();
    }

    private void buildDots() {
        int size = getResources().getDimensionPixelSize(R.dimen.onboarding_dot_size);
        int gap = getResources().getDimensionPixelSize(R.dimen.onboarding_dot_gap);
        for (int i = 0; i < pages.size(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.bg_onboarding_dot);
            dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            dots.addView(dot);
        }
    }

    private void updateControls(int position) {
        boolean last = position == pages.size() - 1;
        next.setText(!last ? R.string.onboarding_next
                : replay ? R.string.onboarding_done : R.string.onboarding_get_started);
        int selected = MaterialColors.getColor(this,
                androidx.appcompat.R.attr.colorPrimary, 0);
        int unselected = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOutlineVariant, 0);
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setBackgroundTintList(
                    ColorStateList.valueOf(i == position ? selected : unselected));
        }
        dots.setContentDescription(getString(R.string.onboarding_page_indicator,
                position + 1, pages.size()));
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

    private static final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        private final List<Page> pages;

        PageAdapter(@NonNull List<Page> pages) {
            this.pages = pages;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding_page, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Page page = pages.get(position);
            holder.icon.setImageResource(page.icon());
            holder.title.setText(page.title());
            holder.body.setText(page.body());
            holder.points.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(holder.itemView.getContext());
            for (Point point : page.points()) {
                View row = inflater.inflate(R.layout.item_onboarding_point, holder.points, false);
                ((ImageView) row.findViewById(R.id.onboardingPointIcon)).setImageResource(point.icon());
                ((TextView) row.findViewById(R.id.onboardingPointText)).setText(point.text());
                holder.points.addView(row);
            }
        }

        @Override
        public int getItemCount() {
            return pages.size();
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView body;
            final ViewGroup points;

            Holder(@NonNull View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.onboardingIcon);
                title = itemView.findViewById(R.id.onboardingTitle);
                body = itemView.findViewById(R.id.onboardingBody);
                points = itemView.findViewById(R.id.onboardingPoints);
            }
        }
    }
}
