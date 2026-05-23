package com.doubleangels.redact.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Virtualized list for Scan tab metadata rows (replaces unbounded LinearLayout inflation).
 */
public final class ScanMetadataAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_LOCATION_HEADER = 0;
    private static final int TYPE_ROW = 1;

    public static final class Entry {
        public final int type;
        @Nullable
        public final String key;
        @Nullable
        public final String value;

        private Entry(int type, @Nullable String key, @Nullable String value) {
            this.type = type;
            this.key = key;
            this.value = value;
        }

        public static Entry locationHeader() {
            return new Entry(TYPE_LOCATION_HEADER, null, null);
        }

        public static Entry row(@Nullable String key, String value) {
            return new Entry(TYPE_ROW, key, value);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public void setEntries(@NonNull List<Entry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    public void clear() {
        if (entries.isEmpty()) {
            return;
        }
        entries.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_LOCATION_HEADER) {
            View v = inflater.inflate(
                    R.layout.item_scan_metadata_location_section_header, parent, false);
            return new HeaderHolder(v);
        }
        View v = inflater.inflate(R.layout.item_scan_metadata_row, parent, false);
        return new RowHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof RowHolder rowHolder) {
            Entry e = entries.get(position);
            if (e.key == null || e.key.isEmpty()) {
                rowHolder.keyView.setVisibility(View.GONE);
            } else {
                rowHolder.keyView.setVisibility(View.VISIBLE);
                rowHolder.keyView.setText(e.key);
            }
            rowHolder.valueView.setText(e.value != null ? e.value : "");
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        HeaderHolder(@NonNull View itemView) {
            super(itemView);
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView keyView;
        final TextView valueView;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            keyView = itemView.findViewById(R.id.metadataFieldKey);
            valueView = itemView.findViewById(R.id.metadataFieldValue);
        }
    }
}
