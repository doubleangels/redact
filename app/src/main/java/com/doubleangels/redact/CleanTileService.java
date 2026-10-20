package com.doubleangels.redact;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.NonNull;

/** Quick Settings tile that opens Redact on the Clean tab, like the launcher shortcut. */
public class CleanTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile != null) {
            // A shortcut, not a toggle: it never shows as on.
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) {
            unlockAndRun(this::openClean);
        } else {
            openClean();
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openClean() {
        Intent intent = cleanIntent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    @NonNull
    private Intent cleanIntent() {
        return new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAG_CLEAN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }
}
