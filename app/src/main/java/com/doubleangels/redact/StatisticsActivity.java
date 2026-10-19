package com.doubleangels.redact;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.doubleangels.redact.stats.StatsSnapshot;
import com.doubleangels.redact.stats.StatsStore;
import com.doubleangels.redact.ui.StatsBarChart;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

/**
 * Settings › Statistics: what Redact has removed and shared on this device, with a chart of the
 * last 30 days and a way to clear it all. Every figure comes from {@link StatsStore}.
 */
public class StatisticsActivity extends AppCompatActivity {

    private TextView heroValue;
    private TextView heroLabel;
    private TextView chartSummary;
    private TextView chartStart;
    private StatsBarChart chart;
    private View tileFilesCleaned;
    private View tileLocations;
    private View tilePhotos;
    private View tileVideos;
    private View tileSharedIn;
    private View tileShares;
    private View tileConverted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        com.doubleangels.redact.ui.SecureWindow.apply(this);
        setContentView(R.layout.activity_statistics);

        MaterialToolbar toolbar = findViewById(R.id.statsToolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        heroValue = findViewById(R.id.statsHeroValue);
        heroLabel = findViewById(R.id.statsHeroLabel);
        chartSummary = findViewById(R.id.statsChartSummary);
        chartStart = findViewById(R.id.statsChartStart);
        chart = findViewById(R.id.statsChart);
        tileFilesCleaned = setUpTile(R.id.tileFilesCleaned, R.drawable.ic_clean, R.string.stats_files_cleaned);
        tileLocations = setUpTile(R.id.tileLocations, R.drawable.ic_map_24, R.string.stats_locations_removed);
        tilePhotos = setUpTile(R.id.tilePhotos, R.drawable.ic_image, R.string.stats_photos);
        tileVideos = setUpTile(R.id.tileVideos, R.drawable.ic_video, R.string.stats_videos);
        tileSharedIn = setUpTile(R.id.tileSharedIn, R.drawable.ic_add_photo, R.string.stats_shared_in);
        tileShares = setUpTile(R.id.tileShares, R.drawable.ic_share, R.string.stats_times_shared);
        tileConverted = setUpTile(R.id.tileConverted, R.drawable.ic_convert, R.string.stats_files_converted);

        findViewById(R.id.rowClearStats).setOnClickListener(v -> confirmClear());
        applyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A share into Redact can finish while this screen is in the background.
        render();
    }

    @NonNull
    private View setUpTile(int id, @DrawableRes int icon, @StringRes int label) {
        View tile = findViewById(id);
        ((ImageView) tile.findViewById(R.id.statIcon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.statLabel)).setText(label);
        return tile;
    }

    private void render() {
        StatsSnapshot s = StatsStore.snapshot(this);
        NumberFormat numbers = NumberFormat.getIntegerInstance();

        heroValue.setText(numbers.format(s.fieldsRemoved));
        heroLabel.setText(s.isEmpty()
                ? getString(R.string.stats_hero_empty)
                : getString(R.string.stats_hero_since,
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(s.trackingSince))));

        setTileValue(tileFilesCleaned, numbers.format(s.filesCleaned()));
        setTileValue(tileLocations, numbers.format(s.locationsRemoved));
        setTileValue(tilePhotos, numbers.format(s.imagesCleaned));
        setTileValue(tileVideos, numbers.format(s.videosCleaned));
        setTileValue(tileSharedIn, numbers.format(s.sharedIn));
        setTileValue(tileShares, numbers.format(s.shares));
        setTileValue(tileConverted, numbers.format(s.converted));

        long recent = s.cleanedInHistory();
        String summary = recent == 0
                ? getString(R.string.stats_chart_empty)
                : getResources().getQuantityString(R.plurals.stats_chart_summary,
                        (int) Math.min(Integer.MAX_VALUE, recent), numbers.format(recent));
        chartSummary.setText(summary);
        chart.setValues(s.dailyCleans());
        chart.setContentDescription(summary);

        LocalDate first = LocalDate.now().minusDays(StatsStore.HISTORY_DAYS - 1);
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd");
        chartStart.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(
                Date.from(first.atStartOfDay(ZoneId.systemDefault()).toInstant())));
    }

    private static void setTileValue(@NonNull View tile, @NonNull String value) {
        ((TextView) tile.findViewById(R.id.statValue)).setText(value);
    }

    private void confirmClear() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.stats_clear_title)
                .setMessage(R.string.stats_clear_message)
                .setPositiveButton(R.string.stats_clear_confirm, (d, w) -> {
                    StatsStore.clear(this);
                    render();
                    Toast.makeText(this, R.string.stats_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void applyInsets() {
        View root = findViewById(R.id.statsRoot);
        View content = findViewById(R.id.statsContent);
        int contentBottom = content.getPaddingBottom();
        int barsAndCutout = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets outer = insets.getInsets(barsAndCutout);
            v.setPadding(outer.left, outer.top, outer.right, 0);
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), contentBottom + outer.bottom);
            return insets;
        });
    }
}
