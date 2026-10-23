package com.doubleangels.redact.media;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

/**
 * Empty marker so Glide's annotation processor generates a merged module and manifest
 * parsing can be turned off. Without this, the first Glide.get() call (the first thumbnail
 * load, not app launch itself, since Glide initializes lazily) scans the manifest via
 * PackageManager and reflection looking for legacy GlideModule declarations -- overhead
 * this app has no use for, since it declares none.
 */
@GlideModule
public final class RedactGlideModule extends AppGlideModule {
    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }

    /**
     * Thumbnails stay in memory only. Glide's disk cache would keep a copy of every photo picked in
     * Clean, Scan or Convert in the app's cache, where neither the temp cleanup nor Clear Temporary
     * Files reached it; and the files are local, so caching them saves little.
     */
    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setDefaultRequestOptions(new RequestOptions().diskCacheStrategy(DiskCacheStrategy.NONE));
    }
}
