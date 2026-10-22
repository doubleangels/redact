package com.doubleangels.redact.media;


import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.doubleangels.redact.AppPreferences;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Privacy-safe output naming for gallery saves.
 */
public final class MediaFileNames {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int RANDOM_NAME_LENGTH = 12;
    /** Leaves room for the extension and a " (1)" the system adds on a clash, within 255 bytes. */
    private static final int MAX_BASE_NAME_LENGTH = 100;

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
     * The name, without extension, for a file saved to the output folder: the original's own name
     * when Keep Original File Names is on, otherwise a random one. The system adds " (1)" if a
     * file with that name already exists.
     */
    @NonNull
    public static String outputBaseName(@NonNull Context context, @Nullable String originalFileName) {
        return outputBaseName(context, originalFileName, 0);
    }

    /**
     * Like {@link #outputBaseName(Context, String)}, but a kept name goes through
     * {@code keptNameFormat}, a string resource such as "%1$s (censored)", so the copy is told
     * apart from the original. Zero keeps the name as it is.
     */
    @NonNull
    public static String outputBaseName(@NonNull Context context, @Nullable String originalFileName,
                                        @StringRes int keptNameFormat) {
        if (AppPreferences.isKeepOriginalFileNames(context)) {
            String base = baseNameOf(originalFileName);
            if (!base.isEmpty()) {
                return keptNameFormat != 0 ? context.getString(keptNameFormat, base) : base;
            }
        }
        return generateShortRandomName();
    }

    /**
     * The name a copy saved under {@link #outputBaseName} was meant to have, when the system gave
     * it a " (1)" suffix because {@code originalFileName} was taken; otherwise null.
     */
    @Nullable
    static String nameBeforeClash(@NonNull String currentName, @Nullable String originalFileName) {
        String base = baseNameOf(originalFileName);
        if (base.isEmpty()) {
            return null;
        }
        int dot = currentName.lastIndexOf('.');
        String stem = dot >= 0 ? currentName.substring(0, dot) : currentName;
        String extension = dot >= 0 ? currentName.substring(dot) : "";
        if (!stem.matches(Pattern.quote(base) + " \\(\\d+\\)")) {
            return null;
        }
        return base + extension;
    }

    /** The file name without its folder or extension, with characters a file name cannot hold replaced. */
    @NonNull
    static String baseNameOf(@Nullable String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.substring(fileName.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceFirst("^[.\\s]+", "")
                .trim();
        return name.length() > MAX_BASE_NAME_LENGTH ? name.substring(0, MAX_BASE_NAME_LENGTH).trim() : name;
    }
}
