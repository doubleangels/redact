package com.doubleangels.redact.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
}
