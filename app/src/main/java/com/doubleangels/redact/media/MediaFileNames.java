package com.doubleangels.redact.media;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;

import com.doubleangels.redact.AppPreferences;

import java.security.SecureRandom;

/**
 * Privacy-safe output naming for gallery saves.
 */
public final class MediaFileNames {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_NAME_LENGTH = 12;

    private MediaFileNames() {
    }

    /** MediaStore RELATIVE_PATH for cleaned/converted images, e.g. {@code Pictures/Redact}. */
    @NonNull
    public static String picturesOutputPath(@NonNull Context context) {
        return Environment.DIRECTORY_PICTURES + "/" + AppPreferences.getOutputFolder(context);
    }

    /** MediaStore RELATIVE_PATH for cleaned/converted videos, e.g. {@code Movies/Redact}. */
    @NonNull
    public static String moviesOutputPath(@NonNull Context context) {
        return Environment.DIRECTORY_MOVIES + "/" + AppPreferences.getOutputFolder(context);
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
