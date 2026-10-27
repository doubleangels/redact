package com.doubleangels.redact;

import androidx.annotation.NonNull;

/** Quick Settings tile that opens Redact on the Clean tab. */
public class CleanTileService extends TabTileService {

    @NonNull
    @Override
    protected String tab() {
        return MainActivity.TAG_CLEAN;
    }
}
