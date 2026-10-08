package com.mobicareapp.record

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Resumes "Always listen in background" after a restart, since the foreground service (and its mic access) doesn't survive a reboot on its own. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && AlwaysListenSettings.isEnabled(context)) {
            AudioRecordService.start(context, voiceActivated = true, continuousListening = true)
        }
    }
}
