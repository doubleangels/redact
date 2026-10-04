package com.doubleangels.redact.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Privacy-safe output naming for gallery saves.
 */
public final class MediaFileNames {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_NAME_LENGTH = 12;
    private static final Pattern RANDOM_NAME = Pattern.compile("[A-Za-z0-9]{" + RANDOM_NAME_LENGTH + "}");

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

    /**
     * True if {@code fileName} (with or without extension) looks like a name produced by
     * {@link #generateShortRandomName()}, i.e. it carries no information about the original file.
     */
    public static boolean isRandomName(@Nullable String fileName) {
        if (fileName == null) {
            return false;
        }
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return RANDOM_NAME.matcher(name).matches();
    }
}
