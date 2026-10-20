package com.doubleangels.redact;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.doubleangels.redact.sentry.SentryManager;
import com.doubleangels.redact.ui.OpenSourceLicenses;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Settings › Open-Source Licenses: one row per library in {@link OpenSourceLicenses}. Tapping a
 * row shows its license text, which ships in res/raw so it reads without a connection.
 */
public class LicensesActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        com.doubleangels.redact.ui.SecureWindow.apply(this);
        setContentView(R.layout.activity_licenses);

        MaterialToolbar toolbar = findViewById(R.id.licensesToolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        ViewGroup list = findViewById(R.id.licensesList);
        LayoutInflater inflater = getLayoutInflater();
        for (OpenSourceLicenses.Entry entry : OpenSourceLicenses.ENTRIES) {
            View row = inflater.inflate(R.layout.item_license, list, false);
            ((TextView) row.findViewById(R.id.licenseName)).setText(entry.name());
            ((TextView) row.findViewById(R.id.licenseType)).setText(entry.licenseName());
            row.setOnClickListener(v -> showLicense(entry));
            list.addView(row);
        }
        applyInsets();
    }

    private void showLicense(@NonNull OpenSourceLicenses.Entry entry) {
        View content = getLayoutInflater().inflate(R.layout.dialog_license_text, null);
        ((TextView) content.findViewById(R.id.licenseText)).setText(readLicense(entry.licenseText()));
        new MaterialAlertDialogBuilder(this)
                .setTitle(entry.name())
                .setView(content)
                .setPositiveButton(R.string.licenses_close, null)
                .setNeutralButton(R.string.licenses_project_page, (d, w) -> openUrl(entry.url()))
                .show();
    }

    @VisibleForTesting
    @NonNull
    String readLicense(@RawRes int resId) {
        // InputStream.readAllBytes needs API 33; minSdk is 31.
        try (InputStream in = getResources().openRawResource(resId);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            SentryManager.recordException(e);
            return "";
        }
    }

    private void openUrl(@NonNull String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.settings_link_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyInsets() {
        View root = findViewById(R.id.licensesRoot);
        View content = findViewById(R.id.licensesContent);
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
