package com.doubleangels.redact;

import org.robolectric.annotation.Config;

/** Below Android 13 the picker needs storage access, so tabs lock until it is granted. */
@Config(sdk = 31)
public class FragmentPermissionFlowsApi31Test extends FragmentPermissionFlowsBase {
}
