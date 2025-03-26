package com.doubleangels.redact.media;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.doubleangels.redact.R;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for displaying media items (images and videos) in a grid or list.
 *
 * This adapter handles the visualization of media items, showing thumbnails for all media
 * and displaying a special indicator for videos. It uses Glide for efficient image loading
 * and implements DiffUtil for optimized list updates.
 */
public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {
    /** The collection of media items to display */
    private final List<MediaItem> mediaItems;

    /**
     * Creates a new MediaAdapter instance.
     *
     * @param mediaItems Initial list of media items to display
     */
    public MediaAdapter(List<MediaItem> mediaItems) {
        this.mediaItems = mediaItems;
    }

    /**
     * Creates new ViewHolder instances for the RecyclerView.
     *
     * @param parent The ViewGroup into which the new View will be added
     * @param viewType The view type of the new View
     * @return A new MediaViewHolder that holds a view of the given view type
     */
    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_selected_media, parent, false);
        return new MediaViewHolder(view);
    }

    /**
     * Binds data to an existing ViewHolder.
     *
     * Loads the thumbnail image using Glide and shows/hides the video indicator
     * based on whether the item is a video.
     *
     * @param holder The ViewHolder to bind data to
     * @param position The position of the item in the data set
     */
    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        MediaItem item = mediaItems.get(position);

        // Load thumbnail with Glide
        Glide.with(holder.itemView.getContext())
                .load(item.getUri())
                .placeholder(R.drawable.placeholder_image)
                .error(R.drawable.error_image)
                .centerCrop()
                .into(holder.thumbnail);

        // Show video indicator only for video items
        holder.videoIndicator.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
    }

    /**
     * Returns the total number of items in the data set.
     *
     * @return The total number of media items
     */
    @Override
    public int getItemCount() {
        return mediaItems.size();
    }

    /**
     * Updates the adapter's data set with a new list of media items.
     *
     * Uses DiffUtil to calculate the difference between the old and new lists,
     * which allows for efficient updates with proper animations.
     *
     * @param newItems New list of media items to display
     */
    public void updateItems(List<MediaItem> newItems) {
        final List<MediaItem> oldList = new ArrayList<>(mediaItems);

        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return oldList.size();
            }

            @Override
            public int getNewListSize() {
                return newItems.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return oldList.get(oldItemPosition).getUri().equals(
                        newItems.get(newItemPosition).getUri());
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                MediaItem oldItem = oldList.get(oldItemPosition);
                MediaItem newItem = newItems.get(newItemPosition);
                return oldItem.equals(newItem);
            }
        });

        mediaItems.clear();
        mediaItems.addAll(newItems);

        // Apply calculated updates to the adapter
        diffResult.dispatchUpdatesTo(this);
    }

    /**
     * ViewHolder class for media items.
     *
     * Holds references to the views within each media item layout, specifically
     * the thumbnail image and the video indicator icon.
     */
    public static class MediaViewHolder extends RecyclerView.ViewHolder {
        /** ImageView for displaying the media thumbnail */
        ImageView thumbnail;

        /** ImageView for the video play indicator (only visible for video items) */
        ImageView videoIndicator;

        /**
         * Creates a new MediaViewHolder instance.
         *
         * @param itemView The view for this view holder
         */
        MediaViewHolder(@NonNull View itemView) {
            super(itemView);
            thumbnail = itemView.findViewById(R.id.mediaItemThumbnail);
            videoIndicator = itemView.findViewById(R.id.videoIndicator);
        }
    }
}
