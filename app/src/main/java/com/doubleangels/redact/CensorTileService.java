package com.doubleangels.redact;

import androidx.annotation.NonNull;

/** Quick Settings tile that opens Redact on the Censor tab. */
public class CensorTileService extends TabTileService {

    @NonNull
    @Override
    protected String tab() {
        return MainActivity.TAG_CENSOR;
    }
}
