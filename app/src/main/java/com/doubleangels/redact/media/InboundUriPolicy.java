package com.doubleangels.redact.media;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Which media another app may hand Redact: a share, an "Open with", or a share to Scan, Convert
 * or Censor. Redact can read files the sender cannot (its own private folders and providers), so
 * a URI naming one would let the sender have Redact read it, and maybe re-share it to whatever
 * app the user picks. MainActivity and ShareHandlerActivity both ask here, so the rules are the
 * same in every entry point.
 */
public final class InboundUriPolicy {

    private InboundUriPolicy() {
    }

    /**
     * Whether {@code uri} is content from another app's provider. Refused: any other scheme, no
     * authority, an authority under Redact's package name (its FileProvider among them), and
     * anything {@link MediaUriResolver#isOwnProvider} says is Redact's, which also catches a
     * {@code userId@} prefix that Android drops before it finds the provider.
     */
    public static boolean isForeignContentUri(@NonNull Context context, @Nullable Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String authority = uri.getAuthority();
        if (authority == null) {
            return false;
        }
        String ownPrefix = context.getPackageName().toLowerCase(Locale.ROOT);
        return !authority.toLowerCase(Locale.ROOT).startsWith(ownPrefix)
                && !MediaUriResolver.isOwnProvider(context, uri);
    }
}
