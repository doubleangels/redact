package com.doubleangels.redact.media;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
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

    public ConvertFileAdapter() {
    }

    /**
     * Updates the adapter's data set with a new list of media items.
     *
     * <p>Uses DiffUtil (matching {@link MediaAdapter#updateItems}) instead of
     * {@code notifyDataSetChanged()} so unaffected rows keep their already-loaded Glide
     * thumbnail instead of reloading/flickering on every edit to the selection.
     */
    public void setItems(@NonNull List<MediaItem> newItems) {
        final List<MediaItem> oldItems = new ArrayList<>(items);

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldItems.size();
            }

            @Override
            public int getNewListSize() {
                return newItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldItems.get(oldItemPosition).uri().equals(
                        newItems.get(newItemPosition).uri());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return oldItems.get(oldItemPosition).equals(newItems.get(newItemPosition));
            }
        });

        items.clear();
        items.addAll(newItems);
        diffResult.dispatchUpdatesTo(this);
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

        int thumbPx = thumbnailSizePx(holder.thumbnail);
        Glide.with(holder.thumbnail)
                .load(item.uri())
                .override(thumbPx, thumbPx)
                .placeholder(R.drawable.placeholder_image)
                .error(R.drawable.error_image)
                .centerCrop()
                .into(holder.thumbnail);

        holder.videoIndicator.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onViewRecycled(@NonNull Holder holder) {
        super.onViewRecycled(holder);
        Glide.with(holder.thumbnail).clear(holder.thumbnail);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private int thumbnailSizePx(@NonNull ImageView target) {
        int w = target.getWidth();
        if (w > 0) {
            return w;
        }
        return target.getResources().getDimensionPixelSize(R.dimen.media_thumbnail_px);
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
