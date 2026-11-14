package com.doubleangels.redact.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;


/**
 * The one rule MainActivity and ShareHandlerActivity both apply to media another app hands them:
 * never a URI that would have Redact read its own files for the sender.
 */
@RunWith(RobolectricTestRunner.class)
public class InboundUriPolicyTest {

    private final Application app = RuntimeEnvironment.getApplication();

    @Test
    public void anotherAppsContent_isAccepted() {
        com.doubleangels.redact.TestShareProvider.register(app, "com.example.gallery.provider");
        assertTrue(InboundUriPolicy.isForeignContentUri(app,
                Uri.parse("content://com.example.gallery.provider/photo/1")));
    }

    /** A provider the system will not name is not vouched for as another app's. */
    @Test
    public void aProviderTheSystemCannotName_isRefused() {
        assertFalse(InboundUriPolicy.isForeignContentUri(app,
                Uri.parse("content://com.example.unknown.provider/photo/1")));
    }

    @Test
    public void redactsOwnProviders_andOtherSchemes_areRefused() {
        String pkg = app.getPackageName();
        assertFalse(InboundUriPolicy.isForeignContentUri(app, null));
        assertFalse(InboundUriPolicy.isForeignContentUri(app, Uri.parse("content://" + pkg + ".fileprovider/processed/a.jpg")));
        // Case does not hide it, and neither does another authority under the package name.
        assertFalse(InboundUriPolicy.isForeignContentUri(app,
                Uri.parse("content://" + pkg.toUpperCase(java.util.Locale.ROOT) + ".FILEPROVIDER/processed/a.jpg")));
        assertFalse(InboundUriPolicy.isForeignContentUri(app, Uri.parse("content://" + pkg + ".other/x")));
        // Android drops a userId@ prefix before it finds the provider.
        assertFalse(InboundUriPolicy.isForeignContentUri(app, Uri.parse("content://0@" + pkg + ".fileprovider/processed/a.jpg")));
        assertFalse(InboundUriPolicy.isForeignContentUri(app, Uri.parse("file:///sdcard/DCIM/a.jpg")));
        assertFalse(InboundUriPolicy.isForeignContentUri(app, Uri.parse("content:///no-authority")));
    }
}
