package com.ytsaver.app.license

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Thin strip shown during the trial period, like a POS billing app's "trial" bar, with an early-activation shortcut. */
@Composable
fun TrialBanner(daysLeft: Int, onActivateClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val label = if (daysLeft > 0) {
            "Trial: $daysLeft day${if (daysLeft == 1) "" else "s"} left"
        } else {
            "Trial ends today"
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onActivateClick) {
            Text("Activate")
        }
    }
}
