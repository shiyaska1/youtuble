package com.mobicareapp.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.mobicareapp.record.AudioRecordService
import com.mobicareapp.record.RecordingStatus
import com.mobicareapp.record.VoiceActivationSettings

/**
 * A Quick Settings tile (the swipe-down panel next to WiFi/Bluetooth) for starting or stopping an
 * audio recording without unlocking the phone — the panel itself is reachable from the lock
 * screen on most phones, unlike a home-screen widget or a long-pressed app shortcut, which both
 * need the phone unlocked to reach. Audio-only (matches the audio home-screen widget): video
 * recording needs the camera preview/quality picker, which isn't something a tile tap can offer.
 *
 * Starting AudioRecordService here is just starting a foreground service (no UI of its own), so
 * it works directly from a locked screen with no unlockAndRun() wrapper needed.
 */
class RecordQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val active = RecordingStatus.active.value
        if (active != null) {
            AudioRecordService.stop(this, autoTranscribe = true)
        } else {
            AudioRecordService.start(this, VoiceActivationSettings.isEnabled(this))
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isRecording = RecordingStatus.active.value != null
        tile.state = if (isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isRecording) "Stop recording" else "Record audio"
        tile.icon = Icon.createWithResource(
            this,
            if (isRecording) android.R.drawable.presence_video_busy else android.R.drawable.presence_audio_online
        )
        tile.updateTile()
    }

    companion object {
        /** Called from YtSaverApp's RecordingStatus collector so the tile stays in sync even while the panel is already open, the same way the widgets do. */
        fun requestUpdate(context: Context) {
            requestListeningState(context, ComponentName(context, RecordQuickTileService::class.java))
        }
    }
}
