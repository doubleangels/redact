package com.doubleangels.redact.media;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import com.doubleangels.redact.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Displays one line per selected file name on the Convert screen.
 */
public final class ConvertFileAdapter extends RecyclerView.Adapter<ConvertFileAdapter.Holder> {

    private final List<MediaItem> items = new ArrayList<>();
    private final Activity activity;

    public ConvertFileAdapter(Activity activity) {
        this.activity = activity;
    }

    public void setItems(@NonNull List<MediaItem> newItems) {
        items.clear();
        items.addAll(newItems);
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
        MediaItem item = items.get(position);
        holder.text.setText(item.fileName());

        Glide.with(activity)
                .load(item.uri())
                .placeholder(R.drawable.placeholder_image)
                .error(R.drawable.error_image)
                .centerCrop()
                .into(holder.thumbnail);

        holder.videoIndicator.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView text;
        final ImageView thumbnail;
        final ImageView videoIndicator;

        Holder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.convertItemName);
            thumbnail = itemView.findViewById(R.id.convertItemThumbnail);
            videoIndicator = itemView.findViewById(R.id.convertVideoIndicator);
        }
    }
}
