package com.doubleangels.redact.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;

import com.doubleangels.redact.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Collections;

import javax.xml.parsers.DocumentBuilderFactory;

/** Tell a Friend hands over plain text with an untracked Play link, and nothing about a file. */
@RunWith(RobolectricTestRunner.class)
public class TellAFriendTest {

    private static final String LINK = "%1$s";

    private Application app;

    @Before
    public void setUp() {
        app = RuntimeEnvironment.getApplication();
        Notice.resetForTests();
    }

    @After
    public void tearDown() {
        Notice.resetForTests();
    }

    @Test
    public void intent_isPlainTextEndingWithTheUntrackedPlayLink() {
        Intent send = TellAFriend.buildIntent(app);
        assertEquals(Intent.ACTION_SEND, send.getAction());
        assertEquals("text/plain", send.getType());
        String text = send.getStringExtra(Intent.EXTRA_TEXT);
        assertNotNull(text);
        String link = StoreLinks.playListingUrl(app);
        assertTrue(text.endsWith(link));
        Uri url = Uri.parse(link);
        assertEquals(Collections.singleton("id"), url.getQueryParameterNames());
        assertEquals(app.getPackageName(), url.getQueryParameter("id"));
        assertEquals(app.getString(R.string.app_name), send.getStringExtra(Intent.EXTRA_TITLE));
    }

    @Test
    public void intent_carriesNoFileOrGrant() {
        Intent send = TellAFriend.buildIntent(app);
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM));
        assertNull(send.getClipData());
        assertNull(send.getData());
        int grants = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        assertEquals(0, send.getFlags() & grants);
    }

    @Test
    public void share_startsAChooserAroundTheText() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TellAFriend.share(activity);
        Intent chooser = shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        assertEquals(app.getString(R.string.tell_friend_chooser_title),
                chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString());
        @SuppressWarnings("deprecation")
        Intent inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull(inner);
        assertEquals(Intent.ACTION_SEND, inner.getAction());
        assertFalse(chooser.hasExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER));
    }

    @Test
    public void share_withNoShareTarget_showsANoticeInsteadOfCrashing() {
        Context noTargets = new ContextWrapper(app) {
            @Override
            public void startActivity(Intent intent) {
                throw new ActivityNotFoundException();
            }
        };
        TellAFriend.share(noTargets);
        assertEquals(app.getString(R.string.settings_link_unavailable), Notice.lastShownForTests());
    }

    @Test
    public void everyLocale_hasOneLinkPlaceholderAtTheEndAndAShortMessage() throws Exception {
        File res = new File("src/main/res");
        if (!res.isDirectory()) {
            res = new File("app/src/main/res");
        }
        File[] dirs = res.listFiles((d, n) -> n.equals("values") || n.startsWith("values-"));
        assertNotNull(dirs);
        int checked = 0;
        for (File dir : dirs) {
            File strings = new File(dir, "strings.xml");
            if (!strings.isFile()) {
                continue;
            }
            NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(strings).getElementsByTagName("string");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element e = (Element) nodes.item(i);
                if (!"tell_friend_message".equals(e.getAttribute("name"))) {
                    continue;
                }
                String text = e.getTextContent().trim();
                String name = dir.getName();
                assertEquals(name, text.indexOf(LINK), text.lastIndexOf(LINK));
                assertTrue(name, text.endsWith(LINK));
                assertFalse(name, text.contains("http"));
                assertTrue(name, text.substring(0, text.length() - LINK.length()).trim().length() <= 250);
                checked++;
            }
        }
        assertEquals(13, checked);
    }
}
