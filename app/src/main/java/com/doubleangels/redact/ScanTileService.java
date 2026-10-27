package com.doubleangels.redact;

import androidx.annotation.NonNull;

/** Quick Settings tile that opens Redact on the Scan tab. */
public class ScanTileService extends TabTileService {

    @NonNull
    @Override
    protected String tab() {
        return MainActivity.TAG_SCAN;
    }
}
