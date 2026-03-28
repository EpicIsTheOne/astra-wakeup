package com.astra.wakeup.ui

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AstraQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        refreshTile()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(AstraPanelLauncher.pendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(AstraPanelLauncher.intent(this))
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val connected = getSharedPreferences("astra", MODE_PRIVATE).getBoolean("gateway_connected", false)
        tile.label = "Astra"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (connected) "Tap to talk" else "Connect phone first"
        }
        tile.state = if (connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
