package com.doubleangels.redact;

import org.robolectric.annotation.Config;

/** From Android 13 the system picker works without storage access. */
@Config(sdk = 34)
public class FragmentPermissionFlowsApi34Test extends FragmentPermissionFlowsBase {
}
