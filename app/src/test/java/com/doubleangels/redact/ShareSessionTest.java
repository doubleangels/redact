package com.doubleangels.redact;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import com.doubleangels.redact.metadata.MetadataStripper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * A second share can replace the first while its worker is still running. The failure counts
 * belong to each share, so the old worker resetting or adding to its own counts can't change what
 * the new share reports.
 */
@RunWith(RobolectricTestRunner.class)
public class ShareSessionTest {

    @Test
    public void failureCounts_belongToTheirOwnShare() {
        Application app = RuntimeEnvironment.getApplication();
        ShareHandlerActivity.ShareSession replaced =
                new ShareHandlerActivity.ShareSession(1, new MetadataStripper(app));
        ShareHandlerActivity.ShareSession current =
                new ShareHandlerActivity.ShareSession(2, new MetadataStripper(app));

        current.unsupportedRejectCount.incrementAndGet();
        current.snapshotFailCount.incrementAndGet();
        // The replaced share's worker carries on with its own counts.
        replaced.unsupportedRejectCount.set(0);
        replaced.snapshotFailCount.set(0);
        replaced.snapshotFailCount.incrementAndGet();
        replaced.snapshotFailCount.incrementAndGet();

        assertEquals(1, current.unsupportedRejectCount.get());
        assertEquals(1, current.snapshotFailCount.get());
        assertEquals(2, replaced.snapshotFailCount.get());
    }
}
