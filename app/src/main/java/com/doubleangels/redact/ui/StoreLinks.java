package com.doubleangels.redact.ui;

import android.content.Context;

import androidx.annotation.NonNull;

/** Links to Redact's Google Play listing, written once for Rate and Tell a Friend. */
public final class StoreLinks {

    private StoreLinks() {
    }

    /**
     * The listing's web address, with the package as its only parameter: no referrer or tracking
     * parameter, so an install can never be traced back to whoever shared the link.
     */
    @NonNull
    public static String playListingUrl(@NonNull Context context) {
        return "https://play.google.com/store/apps/details?id=" + context.getPackageName();
    }

    /** The same listing for the Play Store app. */
    @NonNull
    static String marketUrl(@NonNull Context context) {
        return "market://details?id=" + context.getPackageName();
    }
}
