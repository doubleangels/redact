package com.doubleangels.redact;

import android.media.MediaExtractor;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaExtractor;

/**
 * Robolectric's {@link ShadowMediaExtractor} supports a single selected track and throws when asked
 * to unselect any other track, whereas the real extractor treats that as a no-op. Production code
 * unselects every track before selecting the one it remuxes, so tests that exercise it use this.
 */
@Implements(MediaExtractor.class)
public class LenientMediaExtractorShadow extends ShadowMediaExtractor {

    private int selected = -1;

    @Implementation
    @Override
    protected void selectTrack(int index) {
        if (selected != -1 && selected != index) {
            super.unselectTrack(selected);
        }
        super.selectTrack(index);
        selected = index;
    }

    @Implementation
    @Override
    protected void unselectTrack(int index) {
        if (index == selected) {
            super.unselectTrack(index);
            selected = -1;
        }
    }
}
