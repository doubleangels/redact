package com.doubleangels.redact.media;

import android.net.Uri;

/**
 * Represents a media item (image or video) within the Redact application.
 *
 * This class encapsulates the basic information about a selected media file,
 * including its URI, type (video or image), and file name. It serves as a
 * data model for media processing operations.
 */
public class MediaItem {
    /** URI pointing to the content location of this media item */
    private final Uri uri;

    /** Flag indicating whether this item is a video (true) or image (false) */
    private final boolean isVideo;

    /** The file name of this media item */
    private final String fileName;

    /**
     * Creates a new MediaItem with the specified properties.
     *
     * @param uri The content URI of the media item
     * @param isVideo True if this is a video file, false if it's an image
     * @param fileName The name of the media file
     */
    public MediaItem(Uri uri, boolean isVideo, String fileName) {
        this.uri = uri;
        this.isVideo = isVideo;
        this.fileName = fileName;
    }

    /**
     * Returns the content URI of this media item.
     *
     * @return The URI pointing to the media content
     */
    public Uri getUri() {
        return uri;
    }

    /**
     * Determines if this media item is a video.
     *
     * @return True if this is a video file, false if it's an image
     */
    public boolean isVideo() {
        return isVideo;
    }

    /**
     * Returns the file name of this media item.
     *
     * @return The name of the media file
     */
    public String getFileName() {
        return fileName;
    }
}
