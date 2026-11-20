package com.doubleangels.redact.whatsnew;

import android.content.Context;
import android.content.res.Resources;

import androidx.annotation.NonNull;

import com.doubleangels.redact.BuildConfig;
import com.doubleangels.redact.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The release notes shipped in {@code res/values/whats_new.xml} and its translations: written by
 * {@code /whatsnew}
 * before a release, for one {@code versionCode}. They work offline; nothing is fetched. Notes for
 * another version are never shown, so a release that forgot to write them shows nothing rather
 * than an older release's notes.
 */
public final class WhatsNew {

    public static final String KIND_NEW = "new";
    public static final String KIND_IMPROVED = "improved";
    public static final String KIND_FIXED = "fixed";

    /** One item: what changed, why it matters and where to find it. */
    public record Item(@NonNull String kind, @NonNull String icon, @NonNull String title,
                       @NonNull String body) {
    }

    private WhatsNew() {
    }

    /** Whether the shipped notes are for the installed version. */
    public static boolean isForThisVersion(@NonNull Context context) {
        return context.getResources().getInteger(R.integer.whats_new_version_code)
                == BuildConfig.VERSION_CODE;
    }

    /** Whether this release opens the screen by itself: a minor or major one, not fixes only. */
    public static boolean announces(@NonNull Context context) {
        return context.getResources().getBoolean(R.bool.whats_new_announce);
    }

    /**
     * The items, in the order written, or none when they aren't for this version or the arrays
     * don't line up (a translation that dropped an item): better nothing than a wrong pairing.
     */
    @NonNull
    public static List<Item> load(@NonNull Context context) {
        if (!isForThisVersion(context)) {
            return Collections.emptyList();
        }
        Resources res = context.getResources();
        String[] kinds = res.getStringArray(R.array.whats_new_kinds);
        String[] icons = res.getStringArray(R.array.whats_new_icons);
        String[] titles = res.getStringArray(R.array.whats_new_titles);
        String[] bodies = res.getStringArray(R.array.whats_new_bodies);
        int n = kinds.length;
        if (icons.length != n || titles.length != n || bodies.length != n) {
            return Collections.emptyList();
        }
        List<Item> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            items.add(new Item(kinds[i], icons[i], titles[i], bodies[i]));
        }
        return items;
    }

    /** The items of one kind, in order. */
    @NonNull
    public static List<Item> ofKind(@NonNull List<Item> items, @NonNull String kind) {
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            if (item.kind().equals(kind)) {
                out.add(item);
            }
        }
        return out;
    }
}
