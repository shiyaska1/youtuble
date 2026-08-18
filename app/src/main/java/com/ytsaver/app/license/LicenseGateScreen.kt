package com.ytsaver.app.license

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Full-screen block shown once the 1-month trial has expired. Displays this
 * device's Device ID so it can be sent to the vendor, and unlocks only with
 * the matching device-bound key (see [LicenseManager.expectedKeyFor]).
 */
@Composable
fun LicenseGateScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val deviceId = remember { LicenseManager.deviceId(context) }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Your 1-month trial has ended",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Send this Device ID to the vendor to get your activation key.", textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = deviceId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    error = false
                },
                label = { Text("Activation key") },
                singleLine = true,
                isError = error
            )
            if (error) {
                Spacer(modifier = Modifier.height(4.dp))
                Text("Invalid key for this device, try again.", color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                if (LicenseManager.tryUnlock(context, key)) onUnlocked() else error = true
            }) {
                Text("Unlock")
            }
        }
    }
}
