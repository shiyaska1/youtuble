package com.mobicareapp.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.mobicareapp.record.AudioRecordService
import com.mobicareapp.record.RecordingStatus
import com.mobicareapp.record.VoiceActivationSettings

/**
 * Watches the volume buttons system-wide — something only an Accessibility Service can do on
 * Android, since the OS delivers ordinary key events only to whichever app is in the foreground —
 * so pressing Volume Up 3 times quickly toggles audio recording even with the screen off or a
 * different app open. The volume change itself still goes through untouched (onKeyEvent returns
 * false), so this never interferes with actually adjusting the volume.
 *
 * A shake-to-start trigger used to live here too, removed after it proved too easily set off by
 * ordinary handling (walking, a pocket, a bag) — unlike a deliberate volume-button press, an
 * accelerometer spike can't reliably tell "the user meant this" apart from "the phone got jostled."
 *
 * This service does nothing with screen content — [onAccessibilityEvent] is unused — the
 * `canRequestFilterKeyEvents` flag in accessibility_service_config.xml is what's actually being
 * used it for.
 */
class VolumeTriggerService : AccessibilityService() {

    private val pressTimestamps = ArrayDeque<Long>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            registerPress()
        }
        return false
    }

    private fun registerPress() {
        val now = System.currentTimeMillis()
        pressTimestamps.addLast(now)
        while (pressTimestamps.isNotEmpty() && now - pressTimestamps.first() > PATTERN_WINDOW_MS) {
            pressTimestamps.removeFirst()
        }
        if (pressTimestamps.size >= PRESSES_NEEDED) {
            pressTimestamps.clear()
            toggleRecording()
        }
    }

    private fun toggleRecording() {
        if (RecordingStatus.active.value != null) {
            // No screen/dialog is available from this trigger, so let the widget/tile path's
            // background auto-transcribe run instead of silently dropping that option.
            AudioRecordService.stop(this, autoTranscribe = true)
        } else {
            AudioRecordService.start(this, VoiceActivationSettings.isEnabled(this))
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private const val PRESSES_NEEDED = 3
        private const val PATTERN_WINDOW_MS = 1_500L

        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val myService = ComponentName(context, VolumeTriggerService::class.java).flattenToString()
            return enabledServices.split(':').any { it.equals(myService, ignoreCase = true) }
        }
    }
}
