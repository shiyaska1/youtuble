package com.mobicareapp.ui.handwriting

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.reminder.NoteReminder
import com.mobicareapp.reminder.NoteReminders
import com.mobicareapp.reminder.ReminderStore
import java.text.DateFormat as JavaDateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date

/** A short description of a reminder, e.g. "Every day at 08:00" or "Once: 15 Oct 2026, 16:30". */
fun describeReminder(context: android.content.Context, reminder: NoteReminder): String {
    val time = LocalTime.of(reminder.hour, reminder.minute)
    val timeText = DateFormat.getTimeFormat(context).format(Date(time.atDate(LocalDate.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()))
    return if (reminder.daily) {
        "Every day at $timeText"
    } else {
        "Once: " + JavaDateFormat.getDateTimeInstance(JavaDateFormat.MEDIUM, JavaDateFormat.SHORT).format(Date(reminder.onceAt))
    }
}

/**
 * Set, change, stop or remove a note's reminder. A reminder either happens once on a chosen date and
 * time, or every day at a chosen time. Each time it's due, the notification (titled with the note's
 * title) comes up to 4 times, 5 minutes apart, then stops by itself; Stop on the notification ends
 * that round early (a daily reminder still comes back the next day).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderDialog(noteId: Long, noteTitle: String, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val existing = remember { ReminderStore.get(context, noteId) }

    var daily by remember { mutableStateOf(existing?.daily ?: false) }
    // A new once-only reminder starts an hour from now, rounded to the next quarter.
    val suggested = remember {
        java.time.LocalDateTime.now().plusHours(1).withSecond(0).withNano(0).let { it.withMinute((it.minute / 15) * 15) }
    }
    var day by remember {
        mutableStateOf(
            if (existing != null && !existing.daily) Date(existing.onceAt).toInstant().atZone(zone).toLocalDate() else suggested.toLocalDate()
        )
    }
    var hour by remember { mutableIntStateOf(existing?.hour ?: suggested.hour) }
    var minute by remember { mutableIntStateOf(existing?.minute ?: suggested.minute) }
    var pickingDay by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    if (pickingDay) {
        DayPickerDialog(initial = day, onPicked = { day = it; pickingDay = false }, onDismiss = { pickingDay = false })
    }
    if (pickingTime) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = { TextButton(onClick = { hour = state.hour; minute = state.minute; pickingTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }

    val timeText = DateFormat.getTimeFormat(context)
        .format(Date(LocalTime.of(hour, minute).atDate(LocalDate.now()).atZone(zone).toInstant().toEpochMilli()))
    val dayText = JavaDateFormat.getDateInstance(JavaDateFormat.MEDIUM)
        .format(Date(day.atStartOfDay(zone).toInstant().toEpochMilli()))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"$noteTitle\"", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !daily, onClick = { daily = false }, label = { Text("Once") })
                    FilterChip(selected = daily, onClick = { daily = true }, label = { Text("Every day") })
                }
                if (!daily) {
                    OutlinedButton(onClick = { pickingDay = true }, modifier = Modifier.fillMaxWidth()) { Text("Date: $dayText") }
                }
                OutlinedButton(onClick = { pickingTime = true }, modifier = Modifier.fillMaxWidth()) { Text("Time: $timeText") }
                Text(
                    (if (daily) "Every day at $timeText" else "On $dayText at $timeText") +
                        ", the notification comes up to ${NoteReminders.MAX_NOTIFICATIONS} times, 5 minutes apart, then stops by itself. " +
                        if (daily) "If you stop it, it stops for that day and comes again the next day." else "If you stop it, it does not come again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!NoteReminders.canScheduleExactAlarms(context)) {
                    Text(
                        "Allow exact alarms so the reminder comes right on time (otherwise it can be a few minutes late).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            AppLockManager.suppressNextLock()
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    }) { Text("Allow exact alarms") }
                }
                TextButton(onClick = {
                    if (NoteReminders.notificationsAllowed(context)) {
                        NoteReminders.sendTest(context, noteId, noteTitle)
                        Toast.makeText(context, "A test notification was sent. Pull down the notification bar.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Notifications are switched off for this app.", Toast.LENGTH_LONG).show()
                        AppLockManager.suppressNextLock()
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }) { Text("Send a test notification now") }
                if (existing != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            NoteReminders.stop(context, noteId)
                            Toast.makeText(context, "Notifications stopped", Toast.LENGTH_SHORT).show()
                            onChanged()
                            onDismiss()
                        }) { Text("Stop notifications now") }
                        TextButton(onClick = {
                            NoteReminders.remove(context, noteId)
                            Toast.makeText(context, "Reminder removed", Toast.LENGTH_SHORT).show()
                            onChanged()
                            onDismiss()
                        }) { Text("Remove") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val reminder = if (daily) {
                    NoteReminder(noteId, daily = true, onceAt = 0L, hour = hour, minute = minute)
                } else {
                    val at = LocalTime.of(hour, minute).atDate(day).atZone(zone).toInstant().toEpochMilli()
                    if (at <= System.currentTimeMillis()) {
                        Toast.makeText(context, "Choose a date and time in the future", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    NoteReminder(noteId, daily = false, onceAt = at, hour = hour, minute = minute)
                }
                NoteReminders.set(context, reminder)
                val next = JavaDateFormat.getDateTimeInstance(JavaDateFormat.MEDIUM, JavaDateFormat.SHORT).format(Date(NoteReminders.nextTime(reminder)))
                Toast.makeText(context, "Reminder set. Next notification: $next", Toast.LENGTH_LONG).show()
                if (!NoteReminders.notificationsAllowed(context)) {
                    Toast.makeText(context, "Notifications are switched off for this app. Turn them on or the reminder can't show.", Toast.LENGTH_LONG).show()
                    AppLockManager.suppressNextLock()
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } else if (!NoteReminders.canScheduleExactAlarms(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Without this permission the system may hold the reminder back by many minutes.
                    AppLockManager.suppressNextLock()
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                onChanged()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
