package io.github.liuanxin.scrollshot

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class CaptureTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            subtitle = if (CaptureService.instance == null) { "首次使用请授权" } else { "从当前画面开始" }
            updateTile()
        }
    }

    override fun onClick() {
        if (isLocked) {
            unlockAndRun { launch() }
        } else {
            launch()
        }
    }

    private fun launch() {
        val target = if (CaptureService.instance == null) { SetupActivity::class.java } else { CaptureEntryActivity::class.java }
        val intent = Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }
}
