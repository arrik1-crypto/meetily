package com.meetily.mobile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap from anywhere to open the recording screen
 * (which enforces the consent gate and mic permission itself). While a
 * recording is running the tile shows active and reopens that session.
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { tile ->
            tile.state =
                if (RecordingService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        // During a recording this reaches the screen showing it rather than
        // stacking a second one (see RecordingActivity in the manifest).
        val intent = Intent(this, RecordingActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
