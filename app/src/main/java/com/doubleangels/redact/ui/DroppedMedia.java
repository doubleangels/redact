package com.doubleangels.redact.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.view.DragEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.doubleangels.redact.media.InboundUriPolicy;
import com.doubleangels.redact.media.MediaPickerContracts;
import com.doubleangels.redact.sentry.SentryManager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Photos and videos dragged onto a tab from another app (split screen, a tablet, ChromeOS) or
 * pasted into it. They arrive as another app's URIs, so they get the same check a share does
 * ({@link InboundUriPolicy}): only content from another app's provider, never a file path or one
 * of Redact's own providers.
 */
public final class DroppedMedia {

    /** How much a tab fades while something it would take is held over it. */
    private static final float DRAG_HOVER_ALPHA = 0.6f;

    private DroppedMedia() {
    }

    /** Whether a drag or clip offers a photo or a video. */
    public static boolean offersMedia(@Nullable ClipDescription description) {
        return description != null
                && (description.hasMimeType("image/*") || description.hasMimeType("video/*"));
    }

    /**
     * The media URIs in {@code clip} that another app may hand Redact, at most
     * {@link MediaPickerContracts#MAX_PICK_ITEMS}.
     */
    @NonNull
    @VisibleForTesting
    static List<Uri> acceptedUris(@NonNull Context context, @Nullable ClipData clip) {
        List<Uri> uris = new ArrayList<>();
        if (clip == null) {
            return uris;
        }
        for (int i = 0; i < clip.getItemCount() && uris.size() < MediaPickerContracts.MAX_PICK_ITEMS; i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (InboundUriPolicy.isForeignContentUri(context, uri)) {
                uris.add(uri);
            }
        }
        return uris;
    }

    /**
     * Lets {@code target} take photos and videos dropped on it, handing them to {@code onMedia}.
     * The drop's read permission is asked for from {@code activity} before they are handed on.
     */
    public static void acceptDrops(@NonNull Activity activity, @NonNull View target,
                                   @NonNull Consumer<List<Uri>> onMedia) {
        target.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return offersMedia(event.getClipDescription());
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.setAlpha(DRAG_HOVER_ALPHA);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1f);
                    return true;
                case DragEvent.ACTION_DROP: {
                    v.setAlpha(1f);
                    // Without this the URIs can't be read once the drop returns.
                    if (activity.requestDragAndDropPermissions(event) == null) {
                        SentryManager.log("A drop came without permission to read it.");
                        return false;
                    }
                    List<Uri> uris = acceptedUris(activity, event.getClipData());
                    if (uris.isEmpty()) {
                        return false;
                    }
                    onMedia.accept(uris);
                    return true;
                }
                default:
                    return true;
            }
        });
    }

    /**
     * Whether the clipboard looks like it holds a photo or a video. Reads only its description,
     * which Android does not count as reading the clipboard.
     */
    public static boolean clipboardOffersMedia(@NonNull Context context) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        return clipboard != null && clipboard.hasPrimaryClip()
                && offersMedia(clipboard.getPrimaryClipDescription());
    }

    /** The photos and videos on the clipboard, read only when the person asks to paste. */
    @NonNull
    public static List<Uri> fromClipboard(@NonNull Context context) {
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null || !clipboardOffersMedia(context)) {
            return new ArrayList<>();
        }
        return acceptedUris(context, clipboard.getPrimaryClip());
    }
}
