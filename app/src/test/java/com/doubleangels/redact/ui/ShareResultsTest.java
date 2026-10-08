package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ShareResultsTest {

    /** Reports the MIME type from the last path segment's extension, e.g. 1.jpg. */
    public static class TypeProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) {
            String name = uri.getLastPathSegment();
            if (name.endsWith(".jpg")) return "image/jpeg";
            if (name.endsWith(".png")) return "image/png";
            return "video/mp4";
        }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { return null; }
        @Override public int delete(Uri u, String s, String[] a) { return 0; }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
    }

    private static final Uri JPG = Uri.parse("content://test/1.jpg");
    private static final Uri PNG = Uri.parse("content://test/2.png");
    private static final Uri MP4 = Uri.parse("content://test/3.mp4");

    @Before
    public void registerProvider() {
        ShadowContentResolver.registerProviderInternal("test", new TypeProvider());
    }

    @Test
    public void commonType_singleExactFamilyOrAny() {
        Context context = RuntimeEnvironment.getApplication();
        assertEquals("image/jpeg", ShareResults.commonType(context, List.of(JPG)));
        assertEquals("image/*", ShareResults.commonType(context, List.of(JPG, PNG)));
        assertEquals("*/*", ShareResults.commonType(context, List.of(JPG, MP4)));
    }
}
