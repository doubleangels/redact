package com.doubleangels.redact.media;

import android.net.Uri;

import java.util.Objects;

/**
 * Represents a media item (image or video) within the Redact application.
 * <p>
 * This class encapsulates the basic information about a selected media file,
 * including its URI, type (video or image), and file name. It serves as a
 * data model for media processing operations.
 * <p>
 * Implemented as a plain class (not a record) for compatibility with Android runtimes
 * that have had issues resolving record classes at class load time.
 */
public final class MediaItem {

    private final Uri uri;
    private final boolean isVideo;
    private final String fileName;

    public MediaItem(Uri uri, boolean isVideo, String fileName) {
        this.uri = uri;
        this.isVideo = isVideo;
        this.fileName = fileName;
    }

    public Uri uri() {
        return uri;
    }

    public boolean isVideo() {
        return isVideo;
    }

    public String fileName() {
        return fileName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        MediaItem mediaItem = (MediaItem) o;
        return isVideo == mediaItem.isVideo
                && Objects.equals(uri, mediaItem.uri)
                && Objects.equals(fileName, mediaItem.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(uri, isVideo, fileName);
    }
}
