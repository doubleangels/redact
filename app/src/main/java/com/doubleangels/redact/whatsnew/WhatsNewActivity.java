package com.doubleangels.redact.whatsnew;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.BuildConfig;
import com.doubleangels.redact.R;
import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.ui.SecureWindow;
import com.google.android.material.color.DynamicColors;

import java.util.List;

/**
 * What changed in the installed version, in plain words, under New, Improved and Fixed. Opens by
 * itself once after an update ({@link WhatsNewGate}) and any time from Settings › About. Got It,
 * back and predictive back all close it.
 */
public class WhatsNewActivity extends AppCompatActivity {

    /** Set when Settings › About opened it, for the breadcrumb. */
    public static final String EXTRA_FROM_SETTINGS = "from_settings";

    /** Opens the screen from Settings › About. */
    @NonNull
    public static Intent fromSettings(@NonNull Context context) {
        return new Intent(context, WhatsNewActivity.class).putExtra(EXTRA_FROM_SETTINGS, true);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        SecureWindow.apply(this);
        List<WhatsNew.Item> items = WhatsNew.load(this);
        if (items.isEmpty()) {
            // Notes for another version, or a translation that lost an item: show nothing.
            finish();
            return;
        }
        setContentView(R.layout.activity_whats_new);
        SentryManager.reportFullyDisplayedOnDraw(getWindow().getDecorView());

        if (savedInstanceState == null) {
            // Stored on opening, not closing, so a crash here can't bring it back on every start.
            AppPreferences.setWhatsNewSeenVersion(this, BuildConfig.VERSION_CODE);
            boolean fromSettings = getIntent().getBooleanExtra(EXTRA_FROM_SETTINGS, false);
            SentryManager.logEvent("whatsnew", fromSettings
                    ? "What's New was opened from Settings."
                    : "What's New opened after an update.");
        }

        ((TextView) findViewById(R.id.whatsNewVersion))
                .setText(getString(R.string.whats_new_version, BuildConfig.VERSION_NAME));
        ViewGroup list = findViewById(R.id.whatsNewItems);
        addSection(list, R.string.whats_new_section_new, WhatsNew.ofKind(items, WhatsNew.KIND_NEW));
        addSection(list, R.string.whats_new_section_improved,
                WhatsNew.ofKind(items, WhatsNew.KIND_IMPROVED));
        addSection(list, R.string.whats_new_section_fixed, WhatsNew.ofKind(items, WhatsNew.KIND_FIXED));

        findViewById(R.id.whatsNewDone).setOnClickListener(v -> finish());
        applyInsets();
    }

    /** A header and its items; nothing at all for a kind this release has none of. */
    private void addSection(@NonNull ViewGroup list, @StringRes int header,
                            @NonNull List<WhatsNew.Item> items) {
        if (items.isEmpty()) {
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        TextView title = (TextView) inflater.inflate(R.layout.item_whats_new_section, list, false);
        title.setText(header);
        list.addView(title);
        for (WhatsNew.Item item : items) {
            View row = inflater.inflate(R.layout.item_whats_new, list, false);
            ((ImageView) row.findViewById(R.id.whatsNewItemIcon))
                    .setImageResource(WhatsNewIcons.forKey(item.icon()));
            ((TextView) row.findViewById(R.id.whatsNewItemTitle)).setText(item.title());
            ((TextView) row.findViewById(R.id.whatsNewItemBody)).setText(item.body());
            list.addView(row);
        }
    }

    private void applyInsets() {
        View root = findViewById(R.id.whatsNewRoot);
        int barsAndCutout = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets outer = insets.getInsets(barsAndCutout);
            v.setPadding(outer.left, outer.top, outer.right, outer.bottom);
            return insets;
        });
    }
}
