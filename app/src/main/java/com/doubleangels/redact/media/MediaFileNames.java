package com.doubleangels.redact.media;

import androidx.annotation.NonNull;

import java.security.SecureRandom;

/**
 * Privacy-safe output naming for gallery saves.
 */
public final class MediaFileNames {

    private static final SecureRandom RANDOM = new SecureRandom();

    private MediaFileNames() {
    }

    @NonNull
    public static String generateShortRandomName() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
