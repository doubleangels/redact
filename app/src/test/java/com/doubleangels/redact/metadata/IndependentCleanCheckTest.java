package com.doubleangels.redact.metadata;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.AppPreferences;
import com.doubleangels.redact.CleanPreset;
import com.doubleangels.redact.CleanPresets;
import com.doubleangels.redact.FakeMediaStoreProvider;
import com.doubleangels.redact.FileProviderTestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cleans every test photo with each Clean preset and as a share, then reads the output with
 * {@link IndependentScan}, a reader that shares no code with {@link CleanVerifier}. Anything it
 * finds that the preset does not keep fails the test, so a blind spot the cleaner and its own
 * verification share still shows up here.
 *
 * <p>Where this fails, fix the cleaner and the verifier, with a regression test. Allow an item
 * here only when metadata-extractor is provably wrong about it, with the file and the reason.
 */
@RunWith(RobolectricTestRunner.class)
public class IndependentCleanCheckTest {

    /** The photo fixtures in src/test/resources/image. */
    private static final String[] IMAGE_FIXTURES = {
            "animated.avif", "animated_two_tracks.avif", "heic_tagged.heic", "motion_photo_samsung.jpg",
            "motion_photo_tagged.jpg", "rgba16.png", "rgba16_interlaced.png", "rgba16_split.png",
            "ultra_hdr_tagged.jpg"};

    private Application app;
    private final List<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(app);
        FileProviderTestSupport.makeUsable(app);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        AppPreferences.resetAll(app);
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /** One way a photo is cleaned: a preset for the gallery, or a share, which keeps nothing. */
    private static final class Way {
        final String name;
        final CleanPreset preset; // null for a share
        final Set<IndependentScan.Kind> allowed;

        Way(String name, CleanPreset preset, Set<IndependentScan.Kind> allowed) {
            this.name = name;
            this.preset = preset;
            this.allowed = allowed;
        }
    }

    /**
     * What each preset may leave, from what it says it keeps. Read from the preset's own name and
     * description, not from the app's keep list, so a wrong keep list fails here.
     */
    private static List<Way> ways() {
        List<Way> ways = new ArrayList<>();
        ways.add(new Way("Standard", CleanPreset.STANDARD, EnumSet.noneOf(IndependentScan.Kind.class)));
        ways.add(new Way("Sending to People", CleanPreset.SENDING_TO_PEOPLE,
                EnumSet.of(IndependentScan.Kind.DATE)));
        ways.add(new Way("Keeping for Myself", CleanPreset.KEEPING_FOR_MYSELF,
                EnumSet.of(IndependentScan.Kind.DATE, IndependentScan.Kind.CAMERA,
                        IndependentScan.Kind.LOCATION)));
        ways.add(new Way("Share", null, EnumSet.noneOf(IndependentScan.Kind.class)));
        return ways;
    }

    /** Cleans {@code source} one way and returns the output, or null when the clean refused it. */
    private File clean(File source, String name, Way way) throws IOException {
        AppPreferences.resetAll(app);
        if (way.preset != null) {
            assertTrue(CleanPresets.apply(app, way.preset, true));
        }
        FakeMediaStoreProvider.reset();
        FakeMediaStoreProvider.install(app);
        MetadataStripper stripper = new MetadataStripper(app);
        if (way.preset == null) {
            CleanResult result = stripper.cleanImageForSharing(Uri.fromFile(source), name);
            return result.uri() == null ? null : result.file();
        }
        CleanResult result = stripper.cleanImage(Uri.fromFile(source), name);
        return result.uri() == null ? null : FakeMediaStoreProvider.only().file;
    }

    @Test
    public void everyPhotoFixture_afterEveryPreset_carriesNothingThePresetDoesNotKeep() throws IOException {
        Map<String, File> sources = new LinkedHashMap<>();
        for (String fixture : IMAGE_FIXTURES) {
            sources.put(fixture, fixture(fixture));
        }
        sources.put("tagged.jpg", taggedJpeg());
        sources.put("tagged.png", taggedPng());

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, File> source : sources.entrySet()) {
            String name = source.getKey();
            for (Way way : ways()) {
                File out = clean(source.getValue(), name, way);
                if (out == null) {
                    continue; // refusing a file is failing closed; CleanVerifier's tests cover why
                }
                IndependentScan.Report report = IndependentScan.scan(out);
                if (!report.readable) {
                    problems.add(name + " (" + way.name + "): metadata-extractor could not read the output");
                    continue;
                }
                checked++;
                for (IndependentScan.Finding f : report.notAllowed(way.allowed)) {
                    problems.add(name + " (" + way.name + "): " + f);
                }
            }
        }
        assertTrue("Metadata-extractor found what the clean should have removed:\n  "
                + String.join("\n  ", problems), problems.isEmpty());
        assertTrue("only " + checked + " cleans could be checked", checked >= 2 * ways().size());
    }

    /**
     * The check above is only worth something if the reader sees what a source carries. The
     * tagged files are made to hold every kind, so it must find each in them.
     */
    @Test
    public void theReader_findsEveryKindInTheTaggedSources() throws IOException {
        for (File source : new File[] {taggedJpeg()}) {
            IndependentScan.Report report = IndependentScan.scan(source);
            assertTrue(report.readable);
            for (IndependentScan.Kind kind : new IndependentScan.Kind[] {IndependentScan.Kind.LOCATION,
                    IndependentScan.Kind.DATE, IndependentScan.Kind.CAMERA, IndependentScan.Kind.IDENTIFYING}) {
                assertTrue("no " + kind + " found in " + source.getName(), report.has(kind));
            }
        }
        IndependentScan.Report png = IndependentScan.scan(taggedPng());
        assertTrue("the PNG's text chunks are found", png.has(IndependentScan.Kind.IDENTIFYING));
        assertTrue("the fixture HEIC's tags are found",
                IndependentScan.scan(fixture("heic_tagged.heic")).has(IndependentScan.Kind.IDENTIFYING));
    }

    @Test
    public void theReader_failsClosed_onATagItHasNoNameFor() throws IOException {
        File jpeg = taggedJpeg();
        IndependentScan.Report report = IndependentScan.scan(jpeg);
        List<IndependentScan.Finding> strict = report.notAllowed(EnumSet.allOf(IndependentScan.Kind.class));
        assertTrue("allowing every kind leaves nothing", strict.isEmpty());
        assertFalse(report.notAllowed(EnumSet.noneOf(IndependentScan.Kind.class)).isEmpty());
    }

    // ---- sources --------------------------------------------------------------------------------

    private File fixture(String name) throws IOException {
        String suffix = name.substring(name.lastIndexOf('.'));
        File f = File.createTempFile("indep_", suffix, app.getFilesDir());
        files.add(f);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("image/" + name)) {
            Files.write(f.toPath(), in.readAllBytes());
        }
        return f;
    }

    private File temp(String suffix, byte[] content) throws IOException {
        File f = File.createTempFile("indep_", suffix, app.getFilesDir());
        files.add(f);
        Files.write(f.toPath(), content);
        return f;
    }

    private static byte[] compressed(Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF3366AA);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(format, 90, out);
        return out.toByteArray();
    }

    /** A JPEG with a location, dates, the camera, every EXIF text tag, a comment and XMP. */
    private File taggedJpeg() throws IOException {
        File f = temp(".jpg", compressed(Bitmap.CompressFormat.JPEG));
        ExifInterface exif = new ExifInterface(f.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ARTIST, "Indep Artist");
        exif.setAttribute(ExifInterface.TAG_COPYRIGHT, "Indep Copyright");
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "Indep Description");
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Indep Software");
        exif.setAttribute(ExifInterface.TAG_USER_COMMENT, "Indep Comment");
        exif.setAttribute(ExifInterface.TAG_CAMERA_OWNER_NAME, "Indep Owner");
        exif.setAttribute(ExifInterface.TAG_BODY_SERIAL_NUMBER, "Indep Serial");
        exif.setAttribute(ExifInterface.TAG_IMAGE_UNIQUE_ID, "0123456789abcdef");
        exif.setAttribute(ExifInterface.TAG_MAKE, "IndepMake");
        exif.setAttribute(ExifInterface.TAG_MODEL, "IndepModel");
        exif.setAttribute(ExifInterface.TAG_F_NUMBER, "2.8");
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2019:03:04 05:06:07");
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2019:03:04 05:06:07");
        exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+02:00");
        exif.setLatLong(40.6892, -74.0445);
        exif.saveAttributes();

        byte[] withExif = Files.readAllBytes(f.toPath());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(withExif, 0, 2); // SOI
        segment(out, 0xFE, ascii("Indep comment segment"));
        segment(out, 0xE1, cat(ascii("http://ns.adobe.com/xap/1.0/\0"), ascii(
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                        + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                        + " dc:format=\"image/jpeg\"><dc:creator><rdf:Seq><rdf:li>Indep Creator</rdf:li>"
                        + "</rdf:Seq></dc:creator></rdf:Description></rdf:RDF></x:xmpmeta>")));
        out.write(withExif, 2, withExif.length - 2);
        Files.write(f.toPath(), out.toByteArray());
        return f;
    }

    /** A PNG with text chunks and a modification time. */
    private File taggedPng() throws IOException {
        byte[] plain = compressed(Bitmap.CompressFormat.PNG);
        int afterIhdr = 8 + 4 + 4 + 13 + 4;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, afterIhdr);
        ExifFixtures.writePngChunk(out, "tEXt", cat(ascii("Author\0"), ascii("Indep Author")));
        ExifFixtures.writePngChunk(out, "tIME", new byte[] {0x07, (byte) 0xE3, 3, 4, 5, 6, 7});
        out.write(plain, afterIhdr, plain.length - afterIhdr);
        return temp(".png", out.toByteArray());
    }

    private static void segment(ByteArrayOutputStream out, int marker, byte[] payload) {
        int length = payload.length + 2;
        out.write(0xFF);
        out.write(marker);
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] cat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
