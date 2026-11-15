package com.doubleangels.redact.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Check for updates asks GitHub for nothing but the newest release's version, and only when the
 * user taps Check for Updates.
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateCheckerTest {

    private final List<String> requested = new ArrayList<>();

    private UpdateChecker checker(String json, String current) {
        return new UpdateChecker(url -> {
            requested.add(url);
            return json;
        }, current);
    }

    private static String release(String tag) {
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,"
                + "\"assets\":[],\"html_url\":\"https://github.com/doubleangels/redact/releases/tag/" + tag + "\"}";
    }

    /**
     * The app never asks on its own: the only code that makes a checker is the Settings button's
     * handler. A start-up or background check added anywhere else fails here.
     */
    @Test
    public void onlyTheSettingsButton_startsACheck() throws IOException {
        List<String> users = new ArrayList<>();
        try (Stream<Path> files = Files.walk(new File("src/main/java").toPath())) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                if (source.contains("new UpdateChecker(") && !file.endsWith("UpdateChecker.java")) {
                    users.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("SettingsFragment.java"), users);
    }

    @Test
    public void aNewerRelease_isReported_onlyTheReleaseUrlIsAsked() {
        UpdateChecker.Result result = checker(release("v1.11.0"), "1.10.0").checkNow();
        assertEquals(UpdateChecker.Result.Status.NEWER, result.status);
        assertEquals("1.11.0", result.version);
        assertEquals(List.of(UpdateChecker.LATEST_RELEASE_URL), requested);
    }

    @Test
    public void theSameOrAnOlderRelease_isUpToDate() {
        assertEquals(UpdateChecker.Result.Status.UP_TO_DATE,
                checker(release("v1.10.0"), "1.10.0").checkNow().status);
        UpdateChecker.Result older = checker(release("v1.9.2"), "1.10.0").checkNow();
        assertEquals(UpdateChecker.Result.Status.UP_TO_DATE, older.status);
        assertNull(older.version);
    }

    @Test
    public void noConnection_orNoVersion_isAFailure() {
        UpdateChecker offline = new UpdateChecker(url -> {
            throw new IOException("offline");
        }, "1.10.0");
        assertEquals(UpdateChecker.Result.Status.FAILED, offline.checkNow().status);
        assertEquals(UpdateChecker.Result.Status.FAILED, checker("not json", "1.10.0").checkNow().status);
        assertEquals(UpdateChecker.Result.Status.FAILED, checker(release("nightly"), "1.10.0").checkNow().status);
    }

    /**
     * Answers one HTTP request on a local port with {@code status} and {@code body}, and hands
     * back the request's header lines.
     */
    private static java.util.concurrent.Future<List<String>> serveOnce(
            java.net.ServerSocket server, int status, String body) {
        return java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
            List<String> headers = new ArrayList<>();
            try (java.net.Socket socket = server.accept()) {
                java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                        socket.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1));
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    headers.add(line);
                }
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String head = "HTTP/1.1 " + status + " X\r\nContent-Length: " + bytes.length
                        + "\r\nConnection: close\r\n\r\n";
                java.io.OutputStream out = socket.getOutputStream();
                out.write(head.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                out.write(bytes);
                out.flush();
            }
            return headers;
        });
    }

    /** A local server stands in for GitHub: the request carries only the fixed headers. */
    @Test
    public void httpGet_readsTheBody_andSendsNothingButFixedHeaders() throws Exception {
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1,
                java.net.InetAddress.getByName("127.0.0.1"))) {
            String base = "http://127.0.0.1:" + server.getLocalPort();

            java.util.concurrent.Future<List<String>> ok = serveOnce(server, 200, release("v1.11.0"));
            assertEquals("1.11.0", UpdateChecker.latestVersion(UpdateChecker.httpGet(base + "/ok")));
            List<String> headers = ok.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(headers.toString(), headers.contains("User-Agent: Redact-update-check"));
            for (String header : headers) {
                assertFalse(header, header.toLowerCase(java.util.Locale.ROOT).startsWith("cookie"));
            }

            java.util.concurrent.Future<List<String>> missing = serveOnce(server, 404, "");
            try {
                UpdateChecker.httpGet(base + "/missing");
                org.junit.Assert.fail("a 404 is a failure");
            } catch (IOException expected) {
                // reported as a failed check, quietly
            }
            missing.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    public void versionsCompareNumberByNumber() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.2"));
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.99"));
        assertTrue(UpdateChecker.isNewer("1.10.1", "1.10"));
        assertFalse(UpdateChecker.isNewer("1.10", "1.10.0"));
        assertFalse(UpdateChecker.isNewer("1.9.10", "1.10.0"));
        // A debug build's suffix compares by its numbers.
        assertFalse(UpdateChecker.isNewer("1.10.0", "1.10.0-debug"));
    }

    @Test
    public void onlyAPublishedPlainVersionCounts() {
        assertEquals("1.11.0", UpdateChecker.latestVersion(release("v1.11.0")));
        assertEquals("1.11.0", UpdateChecker.latestVersion(release("1.11.0")));
        assertNull(UpdateChecker.latestVersion(release("v1.11.0-beta")));
        assertNull(UpdateChecker.latestVersion(release("nightly")));
        assertNull(UpdateChecker.latestVersion("{\"tag_name\":\"v2.0.0\",\"draft\":true}"));
        assertNull(UpdateChecker.latestVersion("{\"tag_name\":\"v2.0.0\",\"prerelease\":true}"));
        assertNull(UpdateChecker.latestVersion("{}"));
        assertNull(UpdateChecker.latestVersion("["));
    }
}
