package com.doubleangels.redact.media;

import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.module.AppGlideModule;

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
}
