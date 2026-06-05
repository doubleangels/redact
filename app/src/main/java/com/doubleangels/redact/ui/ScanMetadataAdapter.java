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
public final class ScanMetadataAdapter extends RecyclerView.Adapter<ScanMetadataAdapter.RowHolder> {

    public static final class Entry {
        @Nullable
        public final String key;
        @Nullable
        public final String value;

        private Entry(@Nullable String key, @Nullable String value) {
            this.key = key;
            this.value = value;
        }

        public static Entry row(@Nullable String key, String value) {
            return new Entry(key, value);
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    public void setEntries(@NonNull List<Entry> newEntries) {
        entries.clear();
        entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    public void clear() {
        if (entries.isEmpty()) {
            return;
        }
        entries.clear();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_scan_metadata_row, parent, false);
        return new RowHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowHolder rowHolder, int position) {
        Entry e = entries.get(position);
        if (e.key == null || e.key.isEmpty()) {
            rowHolder.keyView.setVisibility(View.GONE);
        } else {
            rowHolder.keyView.setVisibility(View.VISIBLE);
            rowHolder.keyView.setText(e.key);
        }
        rowHolder.valueView.setText(e.value != null ? e.value : "");
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView keyView;
        final TextView valueView;

        RowHolder(@NonNull View itemView) {
            super(itemView);
            keyView = itemView.findViewById(R.id.metadataFieldKey);
            valueView = itemView.findViewById(R.id.metadataFieldValue);
        }
    }
}
