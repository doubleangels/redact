package com.doubleangels.redact.sentry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.regex.Pattern;

import io.sentry.SentryAttributeType;
import io.sentry.SentryEvent;
import io.sentry.SentryLogEvent;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryLogLevel;
import io.sentry.SentryMetricsEvent;
import io.sentry.protocol.Feedback;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryId;

public class SentryPrivacyScrubberTest {

    @Test
    public void scrubRedactsContentUris() {
        String input = "Failed to open content://media/external/images/media/12345";
        String scrubbed = SentryPrivacyScrubber.scrub(input);
        assertNotNull(scrubbed);
        assertTrue(scrubbed.contains("[redacted]"));
        assertTrue(!scrubbed.contains("content://"));
    }

    @Test
    public void scrubRedactsHeicNamesNamesWithSpacesAndOtherStoragePaths() {
        String[] inputs = {
                "open failed: PXL_20261006_020342821.heic",
                "photo.HEIC could not be read",
                "cannot read IMG_beach (1).jpg",
                "saved CEN_noise (censored).jpg",
                "My Trip to Paris.jpg failed",
                "raw.dng and scan.avif",
                "/sdcard/DCIM/Camera/secret.png",
                "/mnt/media_rw/1234-ABCD/DCIM/x.mp4",
        };
        String[] leaks = {"PXL_2026", "photo", "IMG_beach", "CEN_noise", "Paris", "raw", "/sdcard", "/mnt"};
        for (int i = 0; i < inputs.length; i++) {
            String scrubbed = SentryPrivacyScrubber.scrub(inputs[i]);
            assertNotNull(scrubbed);
            assertFalse(inputs[i] + " -> " + scrubbed, scrubbed.contains(leaks[i]));
        }
        assertEquals("open failed: [redacted]",
                SentryPrivacyScrubber.scrub("open failed: PXL_20261006_020342821.heic"));
    }

    /** Folder and file names with spaces and symbols, as libraries write them into messages. */
    private static final String[][] NAMES_WITH_SPACES = {
            {"/storage/emulated/0/Pictures/Divorce lawyer 2026/scan 1.jpg: open failed: EACCES (Permission denied)",
                    "Divorce", "lawyer", "2026", "scan", "1.jpg"},
            {"Failed to build unique file: /storage/emulated/0/DCIM/Trip to Kyiv/IMG_1.jpg image/jpeg",
                    "Trip", "Kyiv", "IMG_1"},
            {"Unable to decode Mom & Dad @ clinic.jpg", "Mom", "Dad", "clinic"},
    };

    @Test
    public void scrubLeavesNoPartOfAFolderOrFileNameWithSpaces() {
        for (String[] row : NAMES_WITH_SPACES) {
            String scrubbed = SentryPrivacyScrubber.scrub(row[0]);
            for (int i = 1; i < row.length; i++) {
                assertFalse(row[0] + " -> " + scrubbed, scrubbed.contains(row[i]));
            }
        }
        // The error itself is still there to debug with.
        assertEquals("[redacted]: open failed: EACCES (Permission denied)",
                SentryPrivacyScrubber.scrub(NAMES_WITH_SPACES[0][0]));
    }

    @Test
    public void scrubEventDropsALibraryExceptionsMessageAndScrubsTheAppsOwn() {
        SentryException library = new SentryException();
        library.setType("FileNotFoundException");
        library.setModule("java.io");
        library.setValue(NAMES_WITH_SPACES[0][0]);
        SentryException androidx = new SentryException();
        androidx.setType("ImageDecoder$DecodeException");
        androidx.setModule("android.graphics");
        androidx.setValue(NAMES_WITH_SPACES[2][0]);
        SentryException own = new SentryException();
        own.setType("UnsupportedMediaException");
        own.setModule("com.doubleangels.redact.media");
        own.setValue("Unsupported: /storage/emulated/0/DCIM/Trip to Kyiv/IMG_1.heic");
        SentryException untyped = new SentryException();
        untyped.setValue("open /storage/emulated/0/x.jpg");
        SentryEvent event = new SentryEvent();
        event.setExceptions(new java.util.ArrayList<>(java.util.Arrays.asList(library, androidx, own, untyped)));

        SentryPrivacyScrubber.scrubEvent(event);

        assertEquals(SentryPrivacyScrubber.MESSAGE_REMOVED, library.getValue());
        assertEquals(SentryPrivacyScrubber.MESSAGE_REMOVED, androidx.getValue());
        assertEquals(SentryPrivacyScrubber.MESSAGE_REMOVED, untyped.getValue());
        assertEquals("Unsupported: [redacted]", own.getValue());
        // The type is kept: with the stack trace, that is what the issue is debugged from.
        assertEquals("FileNotFoundException", library.getType());
    }

    /** In a minified build the app's classes are renamed into the root package. */
    @Test
    public void isOwnExceptionMatchesTheRuntimeNamesOfTheAppsExceptionClasses() {
        SentryException renamed = new SentryException();
        renamed.setType(com.doubleangels.redact.media.ProcessingCancelledException.class.getName());
        assertTrue(SentryPrivacyScrubber.isOwnException(renamed));
        SentryException lookalike = new SentryException();
        lookalike.setType("IOException");
        lookalike.setModule("com.doubleangels.redactor");
        assertFalse(SentryPrivacyScrubber.isOwnException(lookalike));
    }

    @Test
    public void scrubCodeIdentifierKeepsPackagesThatLookLikeMediaFileNames() {
        assertEquals("androidx.media3.extractor.mp4.Mp4Extractor",
                SentryPrivacyScrubber.scrubCodeIdentifier("androidx.media3.extractor.mp4.Mp4Extractor"));
        assertEquals("com.bumptech.glide.integration.webp.decoder.WebpDecoder",
                SentryPrivacyScrubber.scrubCodeIdentifier("com.bumptech.glide.integration.webp.decoder.WebpDecoder"));
        assertFalse(SentryPrivacyScrubber.scrubCodeIdentifier("load content://media/external/images/media/1")
                .contains("content://"));
    }

    @Test
    public void scrubRedactsGpsCoordinatePairs() {
        String input = "Location: 39.7392,-104.9903";
        String scrubbed = SentryPrivacyScrubber.scrub(input);
        assertNotNull(scrubbed);
        assertTrue(scrubbed.contains("[redacted]"));
        assertTrue(!scrubbed.contains("39.7392"));
    }

    @Test
    public void scrubLeavesNonGpsNumericPairs() {
        String input = "Dimensions: 1920.0,1080.0";
        String scrubbed = SentryPrivacyScrubber.scrub(input);
        assertEquals(input, scrubbed);
    }

    @Test
    public void scrubLogRedactsBodyAndStripsUserAttributes() {
        SentryLogEvent logEvent =
                new SentryLogEvent(new SentryId(), 1.0, "placeholder", SentryLogLevel.INFO);
        logEvent.setBody("Opened content://media/external/images/media/1");
        logEvent.setAttribute(
                "user.id",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "secret-user"));
        logEvent.setAttribute(
                "operation_type",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "clean"));

        SentryPrivacyScrubber.scrubLog(logEvent);

        assertNotNull(logEvent.getBody());
        assertTrue(logEvent.getBody().contains("[redacted]"));
        assertFalse(logEvent.getAttributes().containsKey("user.id"));
        assertEquals("clean", logEvent.getAttributes().get("operation_type").getValue());
    }

    @Test
    public void scrubMetricRedactsStringAttributesAndStripsPiiKeys() {
        SentryMetricsEvent metric =
                new SentryMetricsEvent(new SentryId(), 1.0, "processing.clean.failure", null, 1.0);
        metric.setAttribute(
                "user.email",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "a@b.com"));
        metric.setAttribute(
                "error_type",
                new SentryLogEventAttributeValue(SentryAttributeType.STRING, "IOException"));

        SentryPrivacyScrubber.scrubMetric(metric);

        assertFalse(metric.getAttributes().containsKey("user.email"));
        assertEquals("IOException", metric.getAttributes().get("error_type").getValue());
    }

    @Test
    public void scrubFeedbackEventScrubsTheNoteAndDropsContactDetails() {
        Feedback feedback = new Feedback("It failed on /storage/emulated/0/DCIM/IMG_1.jpg");
        feedback.setName("Jane");
        feedback.setContactEmail("jane@example.com");
        feedback.setUrl("content://media/external/images/media/1");
        SentryEvent event = new SentryEvent();
        event.getContexts().setFeedback(feedback);

        SentryPrivacyScrubber.scrubFeedbackEvent(event);

        Feedback scrubbed = event.getContexts().getFeedback();
        assertFalse(scrubbed.getMessage().contains("/storage/"));
        assertTrue(scrubbed.getMessage().contains("[redacted]"));
        assertNull(scrubbed.getName());
        assertNull(scrubbed.getContactEmail());
        assertNull(scrubbed.getUrl());
    }

    /** Unbounded, the filename patterns took quadratic time on these: minutes, not milliseconds. */
    @Test(timeout = 2_000)
    public void scrubIsQuickOnLongRunsThatAlmostLookLikeFilenames() {
        String[] inputs = {
                "file" + "file!".repeat(40_000),
                "x".repeat(200_000),
                "a.".repeat(100_000),
        };
        for (String input : inputs) {
            assertNotNull(SentryPrivacyScrubber.scrub(input));
        }
        // A name of the longest length a file system allows is still redacted whole.
        String longName = "n".repeat(250) + ".jpg";
        assertEquals("open failed: [redacted]", SentryPrivacyScrubber.scrub("open failed: " + longName));
        assertEquals("fileName= [redacted]", SentryPrivacyScrubber.scrub("fileName=" + longName));
    }

    /** The name after "file:" and the like is found without a pattern; it ends where the pattern's did. */
    @Test
    public void scrubRedactsNamesAfterAPrefixAsThePatternDid() {
        Pattern pattern = Pattern.compile(
                "((?:file(?:Name)?|resolved processed file|item:)\\s*[:=]?)\\s*[^\\s,;]{1,255}\\.[a-zA-Z0-9]{1,8}",
                Pattern.CASE_INSENSITIVE);
        String[] inputs = {
                "fileName=report.txt",
                "FILE: notes.v2.final.txt; next",
                "file = a.b, then c",
                "item: archive.tar.gz!",
                "fileName.txt",
                "file=.cfg",
                "resolved processed file  scan.pdf and file x",
                "file noext, fileName=data.json9999999999",
                "profile:thing.cfg",
                "file\tz.xml\nfile: y.log",
                "file " + "n".repeat(300) + ".txt",
                "file!file!file.json",
        };
        for (String input : inputs) {
            String expected = pattern.matcher(input).replaceAll("$1 [redacted]");
            assertEquals(input, expected, SentryPrivacyScrubber.scrub(input));
        }
    }

    @Test
    public void scrubNeverSendsPartOfANameThatALongTextIsCutIn() {
        // Long URIs shrink to [redacted], which pulls the name across the 4000-character cut
        // into the 500 characters that are kept; the cut has to drop it whole.
        String uri = "content://" + "x".repeat(90) + " ";
        String input = uri.repeat(39) + "b".repeat(56) + " " + "IMG_secret_beach.jpg";
        String scrubbed = SentryPrivacyScrubber.scrub(input);
        assertNotNull(scrubbed);
        assertFalse(scrubbed, scrubbed.contains("IMG"));
        assertFalse(scrubbed.contains("content://"));
    }

    @Test
    public void scrubFeedbackEventToleratesAnEventWithoutFeedback() {
        SentryPrivacyScrubber.scrubFeedbackEvent(new SentryEvent());
        SentryPrivacyScrubber.scrubFeedbackEvent(null);
    }

    /**
     * The device context is an object, not a map, so it was never scrubbed: its installation ID
     * and boot time went out with every report and tied a phone's reports together (REDACT-2B).
     */
    @Test
    public void events_andTransactions_goWithoutTheInstallationIdOrBootTime() {
        SentryEvent event = new SentryEvent();
        io.sentry.protocol.Device device = new io.sentry.protocol.Device();
        device.setId("650e0fcef17d4bb29b93dd31e1beb9d4");
        device.setBootTime(new java.util.Date());
        device.setModel("A063");
        event.getContexts().setDevice(device);

        SentryPrivacyScrubber.scrubEvent(event);

        io.sentry.protocol.Device after = event.getContexts().getDevice();
        org.junit.Assert.assertNull(after.getId());
        org.junit.Assert.assertNull(after.getBootTime());
        org.junit.Assert.assertEquals("the model stays", "A063", after.getModel());

        io.sentry.protocol.SentryTransaction transaction = new io.sentry.protocol.SentryTransaction(
                new io.sentry.SentryTracer(new io.sentry.TransactionContext("t", "op"),
                        io.sentry.NoOpScopes.getInstance()));
        io.sentry.protocol.Device tDevice = new io.sentry.protocol.Device();
        tDevice.setId("650e0fcef17d4bb29b93dd31e1beb9d4");
        tDevice.setBootTime(new java.util.Date());
        transaction.getContexts().setDevice(tDevice);

        SentryPrivacyScrubber.scrubTransaction(transaction);

        org.junit.Assert.assertNull(transaction.getContexts().getDevice().getId());
        org.junit.Assert.assertNull(transaction.getContexts().getDevice().getBootTime());
    }

    /**
     * The time zone and kernel version, with the model, build and locale, can single out one phone
     * among a few hundred, so they go. The model, build and Android version stay for debugging.
     */
    @Test
    public void events_andTransactions_goWithoutTheTimeZoneOrKernelVersion() {
        SentryEvent event = new SentryEvent();
        io.sentry.protocol.Device device = new io.sentry.protocol.Device();
        device.setTimezone(java.util.TimeZone.getTimeZone("Europe/Kyiv"));
        device.setModel("A063");
        event.getContexts().setDevice(device);
        io.sentry.protocol.OperatingSystem os = new io.sentry.protocol.OperatingSystem();
        os.setKernelVersion("6.1.75-android14-11-g1234abcd #1 SMP PREEMPT");
        os.setBuild("BP2A.250605.031");
        os.setVersion("16");
        event.getContexts().setOperatingSystem(os);

        SentryPrivacyScrubber.scrubEvent(event);

        org.junit.Assert.assertNull(event.getContexts().getDevice().getTimezone());
        org.junit.Assert.assertEquals("A063", event.getContexts().getDevice().getModel());
        io.sentry.protocol.OperatingSystem afterOs = event.getContexts().getOperatingSystem();
        org.junit.Assert.assertNull(afterOs.getKernelVersion());
        org.junit.Assert.assertEquals("BP2A.250605.031", afterOs.getBuild());
        org.junit.Assert.assertEquals("16", afterOs.getVersion());

        io.sentry.protocol.SentryTransaction transaction = new io.sentry.protocol.SentryTransaction(
                new io.sentry.SentryTracer(new io.sentry.TransactionContext("t", "op"),
                        io.sentry.NoOpScopes.getInstance()));
        io.sentry.protocol.Device tDevice = new io.sentry.protocol.Device();
        tDevice.setTimezone(java.util.TimeZone.getTimeZone("Europe/Kyiv"));
        transaction.getContexts().setDevice(tDevice);
        io.sentry.protocol.OperatingSystem tOs = new io.sentry.protocol.OperatingSystem();
        tOs.setKernelVersion("6.1.75");
        transaction.getContexts().setOperatingSystem(tOs);

        SentryPrivacyScrubber.scrubTransaction(transaction);

        org.junit.Assert.assertNull(transaction.getContexts().getDevice().getTimezone());
        org.junit.Assert.assertNull(transaction.getContexts().getOperatingSystem().getKernelVersion());
    }

    /** A context with no device or OS is left alone, without a crash. */
    @Test
    public void scrubDevice_handlesMissingContexts() {
        SentryPrivacyScrubber.scrubDevice(null);
        io.sentry.protocol.Contexts empty = new io.sentry.protocol.Contexts();
        SentryPrivacyScrubber.scrubDevice(empty);
        org.junit.Assert.assertNull(empty.getDevice());
        org.junit.Assert.assertNull(empty.getOperatingSystem());
    }
}
