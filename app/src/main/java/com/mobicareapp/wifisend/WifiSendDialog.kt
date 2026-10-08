package com.mobicareapp.wifisend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Shows the state of [WifiSendService] for [file], starting it if it isn't already running for
 * this file. Dismissing this dialog (back gesture, tapping outside, "Hide") only hides the UI —
 * the transfer keeps running in the background via its own notification, since the whole point is
 * being able to switch away to the receiving device's browser without losing it. "Stop sharing"
 * is the only thing that actually ends it.
 */
@Composable
fun WifiSendDialog(file: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by WifiSendService.state.collectAsState()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(file) {
        WifiSendService.start(context, file)
    }

    val runningUrls = (state as? WifiSendServer.TransferState.Running)?.urls.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send over Wi-Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = state) {
                    is WifiSendServer.TransferState.Starting -> Text("Starting…")
                    is WifiSendServer.TransferState.Running -> {
                        Text("Connect your laptop to this phone's Wi-Fi hotspot, then open this link in a browser:")
                        if (s.urls.isEmpty()) {
                            Text(
                                "Couldn't find a Wi-Fi address — make sure the hotspot (or Wi-Fi) is turned on.",
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            s.urls.forEach { url -> Text(url, style = MaterialTheme.typography.bodyMedium) }
                        }
                        Text(
                            "You can close this or switch apps — the transfer keeps running until you tap \"Stop sharing\" (or Stop on the notification).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is WifiSendServer.TransferState.Transferring -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator()
                            Text("Sending…")
                        }
                    }
                    is WifiSendServer.TransferState.Done -> Text("Sent. This link still works if you need it on another device.")
                    is WifiSendServer.TransferState.Failed -> Text(
                        "Couldn't start the local server: ${s.message}",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(runningUrls.first())) },
                enabled = runningUrls.isNotEmpty()
            ) { Text("Copy link") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) { Text("Hide") }
                TextButton(onClick = {
                    WifiSendService.stop(context)
                    onDismiss()
                }) { Text("Stop sharing") }
            }
        }
    )
}
