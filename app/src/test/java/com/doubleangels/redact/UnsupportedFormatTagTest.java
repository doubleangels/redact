package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;

import com.doubleangels.redact.sentry.SentryPrivacyScrubber;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The fixed values the unsupported-format and other technical metrics send. */
@RunWith(RobolectricTestRunner.class)
public class UnsupportedFormatTagTest {

    @Test
    public void mimeTypeTag_keepsOnlyAPlainType() {
        assertEquals("image/heic", ShareHandlerActivity.mimeTypeTag("image/heic"));
        assertEquals("application/pdf", ShareHandlerActivity.mimeTypeTag("Application/PDF; charset=binary"));
        assertEquals("image/svg+xml", ShareHandlerActivity.mimeTypeTag("image/svg+xml"));
        assertEquals("unknown", ShareHandlerActivity.mimeTypeTag(null));
        assertEquals("unknown", ShareHandlerActivity.mimeTypeTag(""));
        assertEquals("unknown", ShareHandlerActivity.mimeTypeTag("image/../../secret"));
        assertEquals("unknown", ShareHandlerActivity.mimeTypeTag("holiday photo.jpg"));
        // Wildcards are how most apps share; a sender's own text in their place is not kept.
        assertEquals("image/*", ShareHandlerActivity.mimeTypeTag("image/*"));
        assertEquals("*/*", ShareHandlerActivity.mimeTypeTag("*/*"));
        assertEquals("unknown", ShareHandlerActivity.mimeTypeTag("image/Trip to Kyiv"));
        assertEquals("unknown", ShareHandlerActivity.mimeTypeTag("/storage/emulated/0/DCIM/a.jpg"));
    }

    /** Every metric value goes through the scrubber; it must not mangle the fixed values. */
    @Test
    public void theScrubber_leavesTheseValuesAlone() {
        for (String value : new String[] {"image/heic", "application/pdf", "video/mp4", "low_storage",
                "advisory", "downscaled", "full_size", "saved_copy_differs", "fast_failed", "rebuild_failed",
                "1080p", "video/avc", "video/hevc", "copy", "ProviderCreatedNothing", "SecurityException",
                "ERROR_CODE_ENCODING_FAILED", "STALLED", "not_found", "timed_out", "overwrite_failed",
                "unlink_failed", "motion_video", "gain_map", "recording_date", "webm_kept", "decode_retry",
                "censor_preview_scale"}) {
            assertEquals(value, SentryPrivacyScrubber.scrub(value));
        }
    }
}
