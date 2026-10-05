package com.doubleangels.redact.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.R;
import com.doubleangels.redact.metadata.MetadataDisplayer;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;

/**
 * List for Scan tab metadata displaying categorized section headers and interactive rows, with a red
 * tint and "Location" badge on location metadata.
 */
public final class ScanMetadataAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final class Entry {
        public static final int VIEW_TYPE_HEADER = 0;
        public static final int VIEW_TYPE_ROW = 1;

        public final int viewType;
        @Nullable
        public final String sectionId;
        @Nullable
        public final String headerTitle;
        public final int headerIconRes;
        public final int itemCount;
        @Nullable
        public final String key;
        @Nullable
        public final String value;
        /** True for location metadata (GPS coordinates and the like); shown with a red "Location" badge. */
        public final boolean isLocation;

        private Entry(int viewType, @Nullable String sectionId, @Nullable String headerTitle,
                      int headerIconRes, int itemCount, @Nullable String key, @Nullable String value,
                      boolean isLocation) {
            this.viewType = viewType;
            this.sectionId = sectionId;
            this.headerTitle = headerTitle;
            this.headerIconRes = headerIconRes;
            this.itemCount = itemCount;
            this.key = key;
            this.value = value;
            this.isLocation = isLocation;
        }

        public static Entry header(@Nullable String sectionId, @NonNull String title, int iconRes, int itemCount) {
            return new Entry(VIEW_TYPE_HEADER, sectionId, title, iconRes, itemCount, null, null, false);
        }

        public static Entry row(@Nullable String key, @Nullable String value) {
            return new Entry(VIEW_TYPE_ROW, null, null, 0, 0, key, value, isLocationMetadata(null, key));
        }

        public static Entry row(@Nullable String sectionId, @Nullable String key, @Nullable String value) {
            return new Entry(VIEW_TYPE_ROW, sectionId, null, 0, 0, key, value, isLocationMetadata(sectionId, key));
        }

        public static Entry row(@Nullable String sectionId, @Nullable String key, @Nullable String value, boolean isLocation) {
            return new Entry(VIEW_TYPE_ROW, sectionId, null, 0, 0, key, value, isLocation);
        }

        /** Location metadata: everything in the Location section, plus any GPS/location key outside it. */
        public static boolean isLocationMetadata(@Nullable String sectionId, @Nullable String key) {
            if (sectionId != null && MetadataDisplayer.SECTION_LOCATION.equalsIgnoreCase(sectionId)) {
                return true;
            }
            return MetadataDisplayer.isLocationMetadataKey(key);
        }
    }

    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> displayedEntries = new ArrayList<>();
    @Nullable
    private String currentFilterSection = null;

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    public void setEntries(@NonNull List<Entry> newEntries) {
        allEntries.clear();
        allEntries.addAll(newEntries);
        applyFilter();
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    public void filterBySection(@Nullable String sectionId) {
        currentFilterSection = sectionId;
        applyFilter();
    }

    private void applyFilter() {
        displayedEntries.clear();
        if (currentFilterSection == null || currentFilterSection.isEmpty()) {
            displayedEntries.addAll(allEntries);
        } else {
            for (Entry entry : allEntries) {
                if (currentFilterSection.equals(entry.sectionId)) {
                    displayedEntries.add(entry);
                }
            }
        }
        notifyDataSetChanged();
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    public void clear() {
        if (allEntries.isEmpty() && displayedEntries.isEmpty()) {
            return;
        }
        allEntries.clear();
        displayedEntries.clear();
        currentFilterSection = null;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return displayedEntries.get(position).viewType;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == Entry.VIEW_TYPE_HEADER) {
            View view = inflater.inflate(R.layout.item_scan_metadata_header, parent, false);
            return new HeaderHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_scan_metadata_row, parent, false);
            return new RowHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = displayedEntries.get(position);
        if (holder instanceof HeaderHolder headerHolder) {
            headerHolder.titleView.setText(entry.headerTitle != null ? entry.headerTitle : "");
            boolean isLocationHeader = MetadataDisplayer.SECTION_LOCATION.equalsIgnoreCase(entry.sectionId);
            int headerColor = isLocationHeader
                    ? MaterialColors.getColor(headerHolder.itemView, androidx.appcompat.R.attr.colorError,
                            ContextCompat.getColor(headerHolder.itemView.getContext(), R.color.permission_status_denied))
                    : MaterialColors.getColor(headerHolder.itemView, androidx.appcompat.R.attr.colorPrimary,
                            ContextCompat.getColor(headerHolder.itemView.getContext(), R.color.primary));

            headerHolder.titleView.setTextColor(headerColor);
            if (entry.headerIconRes != 0) {
                headerHolder.iconView.setImageResource(entry.headerIconRes);
                headerHolder.iconView.setImageTintList(ColorStateList.valueOf(headerColor));
                headerHolder.iconView.setVisibility(View.VISIBLE);
            } else {
                headerHolder.iconView.setVisibility(View.GONE);
            }
            if (entry.itemCount > 0) {
                headerHolder.countView.setText(String.valueOf(entry.itemCount));
                if (isLocationHeader) {
                    headerHolder.countView.setBackgroundTintList(ColorStateList.valueOf(
                            MaterialColors.getColor(headerHolder.itemView, com.google.android.material.R.attr.colorErrorContainer,
                                    ContextCompat.getColor(headerHolder.itemView.getContext(), R.color.permission_status_denied))));
                    headerHolder.countView.setTextColor(
                            MaterialColors.getColor(headerHolder.itemView, com.google.android.material.R.attr.colorOnErrorContainer,
                                    ContextCompat.getColor(headerHolder.itemView.getContext(), R.color.black)));
                } else {
                    headerHolder.countView.setBackgroundTintList(ColorStateList.valueOf(
                            MaterialColors.getColor(headerHolder.itemView, com.google.android.material.R.attr.colorSurfaceContainerHigh,
                                    ContextCompat.getColor(headerHolder.itemView.getContext(), R.color.primary))));
                    headerHolder.countView.setTextColor(
                            MaterialColors.getColor(headerHolder.itemView, com.google.android.material.R.attr.colorOnSurfaceVariant,
                                    ContextCompat.getColor(headerHolder.itemView.getContext(), R.color.black)));
                }
                headerHolder.countView.setVisibility(View.VISIBLE);
            } else {
                headerHolder.countView.setVisibility(View.GONE);
            }
        } else if (holder instanceof RowHolder rowHolder) {
            int primaryColor = MaterialColors.getColor(rowHolder.itemView, androidx.appcompat.R.attr.colorPrimary,
                    ContextCompat.getColor(rowHolder.itemView.getContext(), R.color.primary));
            int errorColor = MaterialColors.getColor(rowHolder.itemView, androidx.appcompat.R.attr.colorError,
                    ContextCompat.getColor(rowHolder.itemView.getContext(), R.color.permission_status_denied));

            if (rowHolder.tintOverlay != null) {
                rowHolder.tintOverlay.setVisibility(entry.isLocation ? View.VISIBLE : View.GONE);
            }
            if (rowHolder.riskIndicator != null) {
                rowHolder.riskIndicator.setVisibility(entry.isLocation ? View.VISIBLE : View.GONE);
            }

            if (entry.key == null || entry.key.isEmpty()) {
                rowHolder.keyView.setVisibility(View.GONE);
            } else {
                rowHolder.keyView.setVisibility(View.VISIBLE);
                rowHolder.keyView.setText(entry.key);
                rowHolder.keyView.setTextColor(entry.isLocation ? errorColor : primaryColor);
            }
            rowHolder.valueView.setText(entry.value != null ? entry.value : "");

            View.OnClickListener copyListener = v -> {
                if (entry.value != null && !entry.value.isEmpty()) {
                    String label = entry.key != null && !entry.key.isEmpty()
                            ? entry.key
                            : v.getContext().getString(R.string.scan_metadata);
                    if (SensitiveClipboard.copy(v.getContext(), label, entry.value)) {
                        Toast.makeText(v.getContext(), R.string.scan_copied_to_clipboard, Toast.LENGTH_SHORT).show();
                    }
                }
            };
            rowHolder.itemView.setOnClickListener(copyListener);
            if (rowHolder.copyButton != null) {
                rowHolder.copyButton.setOnClickListener(copyListener);
            }

            boolean isLastInGroup = position == getItemCount() - 1
                    || getItemViewType(position + 1) == Entry.VIEW_TYPE_HEADER;
            if (rowHolder.divider != null) {
                rowHolder.divider.setVisibility(isLastInGroup ? View.GONE : View.VISIBLE);
            }
        }
    }

    @Override
    public int getItemCount() {
        return displayedEntries.size();
    }

    public static final class HeaderHolder extends RecyclerView.ViewHolder {
        public final ImageView iconView;
        public final TextView titleView;
        public final TextView countView;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            iconView = itemView.findViewById(R.id.sectionHeaderIcon);
            titleView = itemView.findViewById(R.id.sectionHeaderTitle);
            countView = itemView.findViewById(R.id.sectionHeaderCount);
        }
    }

    public static final class RowHolder extends RecyclerView.ViewHolder {
        @Nullable
        public final View tintOverlay;
        public final TextView keyView;
        public final TextView valueView;
        @Nullable
        public final TextView riskIndicator;
        @Nullable
        public final View copyButton;
        @Nullable
        public final View divider;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            tintOverlay = itemView.findViewById(R.id.rowTintOverlay);
            keyView = itemView.findViewById(R.id.metadataFieldKey);
            valueView = itemView.findViewById(R.id.metadataFieldValue);
            riskIndicator = itemView.findViewById(R.id.metadataRiskIndicator);
            copyButton = itemView.findViewById(R.id.metadataCopyButton);
            divider = itemView.findViewById(R.id.rowDivider);
        }
    }
}
