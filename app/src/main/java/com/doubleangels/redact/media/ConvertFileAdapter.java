package com.doubleangels.redact.media;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.doubleangels.redact.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays one line per selected file name on the Convert screen.
 */
public final class ConvertFileAdapter extends RecyclerView.Adapter<ConvertFileAdapter.Holder> {

    private final List<String> names = new ArrayList<>();

    public void setFileNames(@NonNull List<String> items) {
        names.clear();
        names.addAll(items);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_convert_file_row, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.text.setText(names.get(position));
    }

    @Override
    public int getItemCount() {
        return names.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView text;

        Holder(@NonNull View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }
    }
}
