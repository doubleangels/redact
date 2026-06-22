package com.doubleangels.redact.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaSelectorTest {

    @Test
    public void isVideoFromMimeAndNameDetectsVideoMime() {
        assertTrue(MediaSelector.isVideoFromMimeAndName("video/mp4", "clip.mp4"));
    }

    @Test
    public void isVideoFromMimeAndNameDetectsImageMime() {
        assertFalse(MediaSelector.isVideoFromMimeAndName("image/jpeg", "photo.jpg"));
    }

    @Test
    public void isAnimatedImageFileDetectsGif() {
        assertTrue(MediaSelector.isAnimatedImageFile("animation.gif", "image/gif"));
        assertFalse(MediaSelector.isAnimatedImageFile("still.jpg", "image/jpeg"));
    }
}
