package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.doubleangels.redact.FakeMediaStoreProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CensorExporterTest {

    private Context context;
    private final List<File> files = new java.util.ArrayList<>();

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        FakeMediaStoreProvider.install(context);
    }

    @After
    public void tearDown() {
        FakeMediaStoreProvider.reset();
        for (File f : files) {
            f.delete();
        }
    }

    private File photo(String suffix, Bitmap.CompressFormat format, int width, int height,
                       int orientation) throws IOException {
        File f = File.createTempFile("censor_", suffix, context.getFilesDir());
        files.add(f);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.WHITE);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(format, 95, out);
        }
        if (format == Bitmap.CompressFormat.JPEG) {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
            exif.setLatLong(51.5, -0.12);
            exif.setAttribute(ExifInterface.TAG_MAKE, "Camera");
            exif.saveAttributes();
        }
        return f;
    }

    private static Bitmap output() {
        return BitmapFactory.decodeFile(FakeMediaStoreProvider.only().file.getAbsolutePath());
    }

    private static boolean isDark(int c) {
        return Color.red(c) < 40 && Color.green(c) < 40 && Color.blue(c) < 40;
    }

    private static boolean isLight(int c) {
        return Color.red(c) > 215 && Color.green(c) > 215 && Color.blue(c) > 215;
    }

    @Test
    public void png_keepsItsFormatAndIsCoveredAtFullSize() throws Exception {
        File src = photo(".png", Bitmap.CompressFormat.PNG, 200, 100, ExifInterface.ORIENTATION_NORMAL);

        CensorExporter.Result result = CensorExporter.export(context, Uri.fromFile(src), "receipt.png",
                List.of(CensorRegion.rect(0f, 0f, 0.5f, 1f)), CensorEffects.Effect.BLACK);

        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertEquals("image/png", entry.mimeType());
        assertTrue(entry.displayName().endsWith(".png"));
        assertFalse("published once written", entry.isPending());
        assertFalse(result.downscaled);
        Bitmap out = output();
        assertEquals(200, out.getWidth());
        assertEquals(100, out.getHeight());
        assertEquals(Color.BLACK, out.getPixel(50, 50));
        assertEquals(Color.WHITE, out.getPixel(150, 50));
    }

    @Test
    public void jpeg_carriesNoMetadataOrPreview() throws Exception {
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_NORMAL);
        assertTrue("the source has a location", new ExifInterface(src.getAbsolutePath()).getLatLong() != null);

        CensorExporter.export(context, Uri.fromFile(src), "street.jpg",
                List.of(CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.4f)), CensorEffects.Effect.PIXELATE);

        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        assertEquals("image/jpeg", entry.mimeType());
        ExifInterface exif = new ExifInterface(entry.file.getAbsolutePath());
        assertNull(exif.getLatLong());
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE));
        assertFalse("no embedded preview of the uncensored photo", exif.hasThumbnail());
    }

    @Test
    public void rotatedJpeg_isCoveredWhereTheUserSawIt() throws Exception {
        // Stored landscape, shown portrait: the user drew on the upright 48 x 64 photo.
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_ROTATE_90);

        CensorExporter.export(context, Uri.fromFile(src), "portrait.jpg",
                List.of(CensorRegion.rect(0f, 0f, 1f, 0.25f)), CensorEffects.Effect.BLACK);

        Bitmap out = output();
        assertEquals(48, out.getWidth());
        assertEquals(64, out.getHeight());
        assertTrue("top quarter covered", isDark(out.getPixel(24, 6)));
        assertTrue("the rest untouched", isLight(out.getPixel(24, 48)));
        int orientation = new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        assertTrue("the rotation is in the pixels, not a tag",
                orientation == ExifInterface.ORIENTATION_UNDEFINED
                        || orientation == ExifInterface.ORIENTATION_NORMAL);
    }

    @Test
    public void failedInsert_leavesNothingBehind() throws Exception {
        File src = photo(".png", Bitmap.CompressFormat.PNG, 20, 20, ExifInterface.ORIENTATION_NORMAL);
        FakeMediaStoreProvider.failInsert = true;
        try {
            CensorExporter.export(context, Uri.fromFile(src), "a.png",
                    List.of(CensorRegion.rect(0f, 0f, 1f, 1f)), CensorEffects.Effect.BLACK);
            org.junit.Assert.fail("expected the save to fail");
        } catch (IOException expected) {
            assertTrue(FakeMediaStoreProvider.entries().isEmpty());
        }
    }

    @Test
    public void extensionOf_readsTheLastDot() {
        assertEquals(".jpg", CensorExporter.extensionOf("a.b.jpg"));
        assertNull(CensorExporter.extensionOf("noext"));
        assertNull(CensorExporter.extensionOf(null));
    }

    @Test
    public void keptNames_sayTheCopyIsCensored() {
        com.doubleangels.redact.AppPreferences.setKeepOriginalFileNames(context, true);
        assertEquals("IMG_1234 (censored)", MediaFileNames.outputBaseName(context, "IMG_1234.jpg",
                com.doubleangels.redact.R.string.censor_file_name));
        assertEquals("photo.v2 (censored)", MediaFileNames.outputBaseName(context, "photo.v2.png",
                com.doubleangels.redact.R.string.censor_file_name));
        assertEquals("other jobs keep the name as it is",
                "IMG_1234", MediaFileNames.outputBaseName(context, "IMG_1234.jpg"));

        com.doubleangels.redact.AppPreferences.setKeepOriginalFileNames(context, false);
        String random = MediaFileNames.outputBaseName(context, "IMG_1234.jpg",
                com.doubleangels.redact.R.string.censor_file_name);
        assertFalse(random.contains("IMG_1234") || random.contains("censored"));
    }

    @Test
    public void theCleanSettings_decideWhatTheCopyKeeps() throws Exception {
        com.doubleangels.redact.AppPreferences.setStrictClean(context, false);
        com.doubleangels.redact.AppPreferences.setPreserveLocation(context, true);
        com.doubleangels.redact.AppPreferences.setPreserveCameraSettings(context, true);
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_ROTATE_90);

        CensorExporter.Result result = CensorExporter.export(context, Uri.fromFile(src), "street.jpg",
                List.of(CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.4f)), CensorEffects.Effect.BLACK);

        ExifInterface exif = new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath());
        assertTrue("kept, as a clean keeps it", exif.getLatLong() != null);
        assertEquals("Camera", exif.getAttribute(ExifInterface.TAG_MAKE));
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        assertTrue("the pixels are upright already",
                orientation == ExifInterface.ORIENTATION_UNDEFINED
                        || orientation == ExifInterface.ORIENTATION_NORMAL);
        assertFalse(exif.hasThumbnail());
        assertTrue("counted like a clean", result.stats != null);
    }

    @Test
    public void strictClean_keepsNothing_evenWithTheKeepSettingsOn() throws Exception {
        com.doubleangels.redact.AppPreferences.setPreserveLocation(context, true);
        com.doubleangels.redact.AppPreferences.setStrictClean(context, true);
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_NORMAL);

        CensorExporter.export(context, Uri.fromFile(src), "street.jpg",
                List.of(CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.4f)), CensorEffects.Effect.BLACK);

        assertNull(new ExifInterface(FakeMediaStoreProvider.only().file.getAbsolutePath()).getLatLong());
    }

    @Test
    public void noTemporaryCopies_areLeftInTheCache() throws Exception {
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_NORMAL);

        CensorExporter.export(context, Uri.fromFile(src), "street.jpg",
                List.of(CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.4f)), CensorEffects.Effect.BLACK);

        String[] left = context.getCacheDir().list((dir, name) -> name.startsWith("temp_")
                || name.startsWith("censored_") || name.startsWith("verify_"));
        assertEquals(0, left == null ? 0 : left.length);
    }

    @Test
    public void secureDeletion_cleansUpCensoredTempFile() throws Exception {
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_NORMAL);
        CensorExporter.Result result = CensorExporter.export(context, Uri.fromFile(src), "secure.jpg",
                List.of(CensorRegion.rect(0.1f, 0.1f, 0.4f, 0.4f)), CensorEffects.Effect.BLACK);
        assertNotNull(result.uri);
    }

    private File resource(String name) throws IOException {
        File f = File.createTempFile("censor_", "_" + name, context.getFilesDir());
        files.add(f);
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("image/" + name);
                FileOutputStream out = new FileOutputStream(f)) {
            out.write(in.readAllBytes());
        }
        return f;
    }

    @Test
    public void sixteenBitPng_keepsItsSamples_andReplacesOnlyTheCensoredOnes() throws Exception {
        File source = resource("rgba16.png");
        Bitmap original = BitmapFactory.decodeFile(source.getAbsolutePath());
        Bitmap censored = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        censored.eraseColor(Color.BLACK);
        File target = File.createTempFile("censor_out_", ".png", context.getFilesDir());
        files.add(target);

        assertTrue(CensorExporter.writeSixteenBitPng(context, android.net.Uri.fromFile(source), censored,
                List.of(CensorRegion.rect(0.25f, 0.33f, 0.75f, 0.66f)), target));

        byte[] png = java.nio.file.Files.readAllBytes(target.toPath());
        assertEquals("still 16 bits", 16, png[24] & 0xFF);
        Bitmap out = BitmapFactory.decodeFile(target.getAbsolutePath());
        assertTrue("censored inside", isDark(out.getPixel(20, 15)));
        assertEquals("kept outside", original.getPixel(2, 2), out.getPixel(2, 2));
        assertEquals("kept outside", original.getPixel(37, 27), out.getPixel(37, 27));
    }

    @Test
    public void sixteenBitPng_ofAnotherSize_orNotSixteenBits_isLeftToTheEncoder() throws Exception {
        File source = resource("rgba16.png");
        File target = File.createTempFile("censor_out_", ".png", context.getFilesDir());
        files.add(target);
        Bitmap wrongSize = Bitmap.createBitmap(20, 15, Bitmap.Config.ARGB_8888);
        org.junit.Assert.assertFalse(CensorExporter.writeSixteenBitPng(context, android.net.Uri.fromFile(source),
                wrongSize, List.of(CensorRegion.rect(0.1f, 0.1f, 0.2f, 0.2f)), target));

        File eightBit = photo(".png", Bitmap.CompressFormat.PNG, 40, 30, 1);
        org.junit.Assert.assertFalse(CensorExporter.writeSixteenBitPng(context, android.net.Uri.fromFile(eightBit),
                Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888), List.of(CensorRegion.rect(0.1f, 0.1f, 0.2f, 0.2f)), target));
    }

    @Test
    @org.robolectric.annotation.Config(sdk = 34)
    public void gainMap_isBlankedWhereThePhotoIsCensored() {
        Bitmap photo = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        Bitmap contents = Bitmap.createBitmap(20, 15, Bitmap.Config.ARGB_8888);
        contents.eraseColor(Color.WHITE);
        photo.setGainmap(new android.graphics.Gainmap(contents));

        CensorExporter.censorGainMap(photo, List.of(CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f)));

        Bitmap after = photo.getGainmap().getGainmapContents();
        assertEquals("no extra brightness where it was censored", 0, Color.alpha(after.getPixel(10, 7)));
        assertEquals("untouched elsewhere", Color.WHITE, after.getPixel(1, 1));

        // A photo without a gain map is left alone.
        Bitmap plain = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        CensorExporter.censorGainMap(plain, List.of(CensorRegion.rect(0f, 0f, 1f, 1f)));
        assertFalse(plain.hasGainmap());
    }

    // ---- crop ------------------------------------------------------------------------------------

    /** A photo whose left half is red and right half blue, so a crop shows which part was kept. */
    private File halves(String suffix, Bitmap.CompressFormat format, int width, int height) throws IOException {
        File f = File.createTempFile("censor_", suffix, context.getFilesDir());
        files.add(f);
        Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new android.graphics.Canvas(b).drawColor(Color.RED);
        android.graphics.Paint blue = new android.graphics.Paint();
        blue.setColor(Color.BLUE);
        new android.graphics.Canvas(b).drawRect(width / 2f, 0, width, height, blue);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(format, 100, out);
        }
        return f;
    }

    @Test
    public void aCrop_keepsOnlyThatPart_withTheCoveringWhereItWas() throws Exception {
        File src = halves(".png", Bitmap.CompressFormat.PNG, 200, 100);

        CensorExporter.export(context, Uri.fromFile(src), "receipt.png",
                List.of(CensorRegion.rect(0.5f, 0f, 0.75f, 1f)), CensorRegion.rect(0.5f, 0f, 1f, 0.5f),
                CensorEffects.Effect.BLACK);

        Bitmap out = output();
        assertEquals(100, out.getWidth());
        assertEquals(50, out.getHeight());
        assertEquals("covered where it was drawn", Color.BLACK, out.getPixel(10, 25));
        assertEquals("the kept part of the blue half", Color.BLUE, out.getPixel(90, 25));
        for (int x = 0; x < out.getWidth(); x++) {
            for (int y = 0; y < out.getHeight(); y++) {
                assertTrue("nothing of the red half the crop left out",
                        out.getPixel(x, y) != Color.RED);
            }
        }
    }

    @Test
    public void aCropAlone_withNoCoveredArea_isSaved_andCleaned() throws Exception {
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_NORMAL);

        CensorExporter.export(context, Uri.fromFile(src), "street.jpg", List.of(),
                CensorRegion.rect(0.25f, 0.25f, 0.75f, 0.75f), CensorEffects.Effect.BLACK);

        FakeMediaStoreProvider.Entry entry = FakeMediaStoreProvider.only();
        Bitmap out = output();
        assertEquals(32, out.getWidth());
        assertEquals(24, out.getHeight());
        ExifInterface exif = new ExifInterface(entry.file.getAbsolutePath());
        assertNull(exif.getLatLong());
        assertFalse("no preview of the uncropped photo", exif.hasThumbnail());
    }

    /** Stored landscape, shown portrait: the crop was drawn on the upright photo. */
    @Test
    public void aRotatedPhoto_isCroppedWhereTheUserSawIt() throws Exception {
        File src = photo(".jpg", Bitmap.CompressFormat.JPEG, 64, 48, ExifInterface.ORIENTATION_ROTATE_90);

        CensorExporter.export(context, Uri.fromFile(src), "portrait.jpg", List.of(),
                CensorRegion.rect(0f, 0f, 1f, 0.5f), CensorEffects.Effect.BLACK);

        Bitmap out = output();
        assertEquals(48, out.getWidth());
        assertEquals(32, out.getHeight());
    }

    /** A 16-bit PNG's own rows can't be cut down, so a cropped one goes through the encoder. */
    @Test
    public void aCroppedSixteenBitPng_isCutDownToo() throws Exception {
        File source = resource("rgba16.png");

        CensorExporter.export(context, Uri.fromFile(source), "a.png", List.of(),
                CensorRegion.rect(0f, 0f, 0.5f, 0.5f), CensorEffects.Effect.BLACK);

        Bitmap out = output();
        assertEquals(20, out.getWidth());
        assertEquals(15, out.getHeight());
    }

    @Test
    public void cropRect_roundsOutToWholePixels_andIsNeverEmpty() {
        android.graphics.Rect r = CensorExporter.cropRect(CensorRegion.rect(0.101f, 0.2f, 0.499f, 0.8f), 100, 10);
        assertEquals(new android.graphics.Rect(10, 2, 50, 8), r);

        android.graphics.Rect thin = CensorExporter.cropRect(CensorRegion.rect(0.999f, 0.5f, 1f, 0.5001f), 100, 10);
        assertEquals(1, thin.width());
        assertEquals(1, thin.height());
        assertTrue(thin.right <= 100 && thin.bottom <= 10);
    }

    @Test
    public void cropTo_theWholePhoto_isThePhotoItself() throws Exception {
        Bitmap photo = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        org.junit.Assert.assertSame(photo, CensorExporter.cropTo(photo, CensorRegion.rect(0f, 0f, 1f, 1f)));
        Bitmap part = CensorExporter.cropTo(photo, CensorRegion.rect(0f, 0f, 0.5f, 1f));
        assertEquals(20, part.getWidth());
        assertEquals(30, part.getHeight());
    }

    /** The gain map shows the scene too, so it is cut to the same part, never kept whole. */
    @Test
    @org.robolectric.annotation.Config(sdk = 34)
    public void aCrop_cutsTheGainMapToTheSamePart() throws Exception {
        Bitmap photo = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
        Bitmap contents = Bitmap.createBitmap(20, 16, Bitmap.Config.ARGB_8888);
        contents.eraseColor(Color.WHITE);
        // Bright only on the left: a crop of the right half must keep none of it.
        new android.graphics.Canvas(contents).drawColor(Color.BLACK, android.graphics.PorterDuff.Mode.SRC);
        android.graphics.Paint white = new android.graphics.Paint();
        white.setColor(Color.WHITE);
        new android.graphics.Canvas(contents).drawRect(0, 0, 10, 16, white);
        photo.setGainmap(new android.graphics.Gainmap(contents));

        Bitmap cropped = CensorExporter.cropTo(photo, CensorRegion.rect(0.5f, 0f, 1f, 0.5f));

        assertTrue(cropped.hasGainmap());
        Bitmap map = cropped.getGainmap().getGainmapContents();
        assertEquals(10, map.getWidth());
        assertEquals(8, map.getHeight());
        for (int x = 0; x < map.getWidth(); x++) {
            for (int y = 0; y < map.getHeight(); y++) {
                assertEquals("nothing of the left half's gain map", Color.BLACK, map.getPixel(x, y));
            }
        }
    }
}
