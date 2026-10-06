package com.doubleangels.redact.media;

import androidx.annotation.NonNull;

import java.security.SecureRandom;

/**
 * Privacy-safe output naming for gallery saves.
 */
public final class MediaFileNames {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_NAME_LENGTH = 12;

    private MediaFileNames() {
    }

    @NonNull
    public static String generateShortRandomName() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(RANDOM_NAME_LENGTH);
        for (int i = 0; i < RANDOM_NAME_LENGTH; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
