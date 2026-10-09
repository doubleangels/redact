package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
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

    @Test
    public void share_oneFile_sendsItWithReadAccess() {
        Activity app = Robolectric.buildActivity(Activity.class).setup().get();
        ShareResults.share(app, List.of(JPG));

        Intent send = sentIntent(app);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("image/jpeg", send.getType());
        assertEquals(JPG, send.getParcelableExtra(Intent.EXTRA_STREAM));
        assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(1, send.getClipData().getItemCount());
    }

    @Test
    public void share_severalFiles_sendsAllOfThem() {
        Activity app = Robolectric.buildActivity(Activity.class).setup().get();
        ShareResults.share(app, List.of(JPG, PNG, MP4));

        Intent send = sentIntent(app);
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.getAction());
        assertEquals("*/*", send.getType());
        assertEquals(List.of(JPG, PNG, MP4), send.getParcelableArrayListExtra(Intent.EXTRA_STREAM));
        // Every file needs a clip item, or receivers only get read access to the first.
        assertEquals(3, send.getClipData().getItemCount());
        assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test
    public void share_nothing_opensNothing() {
        Activity app = Robolectric.buildActivity(Activity.class).setup().get();
        ShareResults.share(app, List.of());
        assertEquals(null, shadowOf(app).getNextStartedActivity());
    }

    /** The share intent inside the chooser that {@link ShareResults#share} started. */
    private static Intent sentIntent(Activity app) {
        Intent chooser = shadowOf(app).getNextStartedActivity();
        assertNotNull(chooser);
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(send);
        return send;
    }
}
