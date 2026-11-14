package com.doubleangels.redact.update;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;

import com.doubleangels.redact.BuildConfig;
import com.doubleangels.redact.sentry.SentryManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Asks GitHub for the newest release's version number, and says whether it is newer than this
 * one. GitHub's releases carry no APK: the version is all that is read, and the update itself
 * comes from wherever the app was installed.
 *
 * <p>Only ever run when the user taps Check for updates in Settings: the app never asks on its
 * own. One request, with nothing in it but a fixed User-Agent: no identifier, setting, or
 * anything about the user's files.
 */
public final class UpdateChecker {

    static final String LATEST_RELEASE_URL =
            "https://api.github.com/repos/doubleangels/redact/releases/latest";

    /** More than a release's JSON ever needs; a larger answer is not read. */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int TIMEOUT_MS = 10_000;

    /** A plain version: digits separated by dots, after an optional "v" (v1.10.0). */
    private static final Pattern VERSION = Pattern.compile("v?\\d{1,6}(\\.\\d{1,6}){0,3}");

    /** What a check found. */
    public static final class Result {
        public enum Status {
            /** A newer version is out: {@link #version}. */
            NEWER,
            /** This is the newest version. */
            UP_TO_DATE,
            /** GitHub could not be reached, or did not name a version. */
            FAILED
        }

        @NonNull
        public final Status status;
        /** The newer version, for {@link Status#NEWER}; otherwise null. */
        @Nullable
        public final String version;

        @VisibleForTesting
        public Result(@NonNull Status status, @Nullable String version) {
            this.status = status;
            this.version = version;
        }
    }

    /** Fetches the latest release's JSON. Swapped in tests; the real one is {@link #httpGet}. */
    @VisibleForTesting
    public interface Fetcher {
        @NonNull
        String fetch(@NonNull String url) throws IOException;
    }

    private final Fetcher fetcher;
    private final String currentVersion;

    public UpdateChecker() {
        this(UpdateChecker::httpGet, BuildConfig.VERSION_NAME);
    }

    @VisibleForTesting
    public UpdateChecker(@NonNull Fetcher fetcher, @NonNull String currentVersion) {
        this.fetcher = fetcher;
        this.currentVersion = currentVersion;
    }

    /** Asks GitHub now. Blocks on the network, so call it off the main thread. */
    @WorkerThread
    @NonNull
    public Result checkNow() {
        try {
            String latest = latestVersion(fetcher.fetch(LATEST_RELEASE_URL));
            if (latest == null) {
                return new Result(Result.Status.FAILED, null);
            }
            return isNewer(latest, currentVersion)
                    ? new Result(Result.Status.NEWER, latest)
                    : new Result(Result.Status.UP_TO_DATE, null);
        } catch (IOException | RuntimeException e) {
            // Offline is normal; only the kind of failure is noted, for a crash report.
            SentryManager.log("The update check failed: " + e.getClass().getSimpleName() + ".");
            return new Result(Result.Status.FAILED, null);
        }
    }

    /**
     * The version a release's JSON names, without its "v"; null for a draft, a pre-release, or a
     * tag that is not a plain version.
     */
    @VisibleForTesting
    @Nullable
    static String latestVersion(@NonNull String json) {
        try {
            JSONObject release = new JSONObject(json);
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
                return null;
            }
            String tag = release.optString("tag_name", "").trim();
            if (!VERSION.matcher(tag).matches()) {
                return null;
            }
            return tag.startsWith("v") ? tag.substring(1) : tag;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Whether {@code candidate} is a later version than {@code current}, comparing each number in
     * turn (1.10.0 is later than 1.9.2). A current version that is not plain, such as a debug
     * build's suffix, compares by its leading numbers.
     */
    @VisibleForTesting
    static boolean isNewer(@NonNull String candidate, @NonNull String current) {
        int[] a = parts(candidate);
        int[] b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parts(@NonNull String version) {
        String v = version.startsWith("v") ? version.substring(1) : version;
        String[] pieces = v.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            StringBuilder digits = new StringBuilder();
            for (char c : pieces[i].toCharArray()) {
                if (c < '0' || c > '9') {
                    break;
                }
                digits.append(c);
            }
            try {
                out[i] = digits.length() == 0 ? 0 : Integer.parseInt(digits.toString());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** The real {@link Fetcher}: one HTTPS GET with no cookies, no identifiers and short timeouts. */
    @VisibleForTesting
    @NonNull
    static String httpGet(@NonNull String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            // GitHub refuses requests without one; it names the app, not the user or device.
            connection.setRequestProperty("User-Agent", "Redact-update-check");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (out.size() + read > MAX_RESPONSE_BYTES) {
                        throw new IOException("Response too large");
                    }
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }
}
