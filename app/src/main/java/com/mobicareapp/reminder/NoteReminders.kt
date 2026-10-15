package com.mobicareapp.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mobicareapp.MainActivity
import com.mobicareapp.handwriting.HandwritingNoteStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * A reminder for a saved note. [daily] false = once, at [onceAt] (epoch millis); true = every day at [hour]:[minute].
 * The notification's title is the note's own title.
 */
data class NoteReminder(val noteId: Long, val daily: Boolean, val onceAt: Long, val hour: Int, val minute: Int)

/** The reminders, one per note, kept in a small file so they survive the app being closed and are rebuilt after a reboot. */
object ReminderStore {
    private const val FILE_NAME = "note_reminders.json"

    @Synchronized
    fun loadAll(context: Context): List<NoteReminder> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                NoteReminder(o.getLong("noteId"), o.getBoolean("daily"), o.optLong("onceAt"), o.optInt("hour"), o.optInt("minute"))
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun saveAll(context: Context, reminders: List<NoteReminder>) {
        val array = JSONArray()
        reminders.forEach { r ->
            array.put(
                JSONObject().put("noteId", r.noteId).put("daily", r.daily).put("onceAt", r.onceAt).put("hour", r.hour).put("minute", r.minute)
            )
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }

    fun get(context: Context, noteId: Long): NoteReminder? = loadAll(context).firstOrNull { it.noteId == noteId }

    fun put(context: Context, reminder: NoteReminder) = saveAll(context, loadAll(context).filterNot { it.noteId == reminder.noteId } + reminder)

    fun remove(context: Context, noteId: Long) = saveAll(context, loadAll(context).filterNot { it.noteId == noteId })
}

/**
 * Schedules and shows the reminder notifications.
 *
 * Each time a reminder is due, the notification comes up to [MAX_NOTIFICATIONS] times, [REPEAT_MS]
 * (5 minutes) apart, and then stops by itself. Tapping "Stop" on the notification (or in the app)
 * ends that round. A once-only reminder is then finished; a daily one is untouched, so it comes
 * again at the same time the next day.
 */
object NoteReminders {
    const val MAX_NOTIFICATIONS = 4
    const val REPEAT_MS = 5 * 60 * 1000L
    private const val CHANNEL_ID = "note_reminders"
    internal const val ACTION_FIRE = "com.mobicareapp.reminder.FIRE"
    internal const val ACTION_STOP = "com.mobicareapp.reminder.STOP"
    internal const val EXTRA_NOTE_ID = "noteId"
    internal const val EXTRA_INDEX = "index"

    // --- setting reminders -------------------------------------------------------------------

    /** Saves [reminder] and schedules its next round, replacing whatever that note had before. */
    fun set(context: Context, reminder: NoteReminder) {
        cancelRound(context, reminder.noteId, fromIndex = 0)
        ReminderStore.put(context, reminder)
        scheduleNext(context, reminder)
    }

    /** Removes the note's reminder completely. */
    fun remove(context: Context, noteId: Long) {
        cancelRound(context, noteId, fromIndex = 0)
        dismissNotification(context, noteId)
        ReminderStore.remove(context, noteId)
    }

    /** Stops the notifications that are coming right now. A daily reminder keeps tomorrow's; a one-time one is finished. */
    fun stop(context: Context, noteId: Long) {
        dismissNotification(context, noteId)
        val reminder = ReminderStore.get(context, noteId) ?: return
        if (reminder.daily) {
            // Only the repeats of this round (2nd to 4th); alarm 0 is already tomorrow's first one.
            cancelRound(context, noteId, fromIndex = 1)
        } else {
            remove(context, noteId)
        }
    }

    /** After a restart or an app update every alarm is gone; rebuild them from the saved reminders. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        ReminderStore.loadAll(context).forEach { reminder ->
            if (reminder.daily || reminder.onceAt > now) scheduleNext(context, reminder)
            else ReminderStore.remove(context, reminder.noteId) // a one-time reminder whose moment has passed
        }
    }

    /** When the first notification of the next round is due. */
    fun nextTrigger(reminder: NoteReminder, from: Long = System.currentTimeMillis()): Long {
        if (!reminder.daily) return reminder.onceAt
        val calendar = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, reminder.hour)
            set(Calendar.MINUTE, reminder.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (calendar.timeInMillis <= from) calendar.add(Calendar.DAY_OF_YEAR, 1)
        return calendar.timeInMillis
    }

    private fun scheduleNext(context: Context, reminder: NoteReminder) {
        val at = nextTrigger(reminder)
        if (at <= System.currentTimeMillis() && !reminder.daily) return
        scheduleAlarm(context, reminder.noteId, 0, at)
    }

    // --- alarms ------------------------------------------------------------------------------

    /** Exact alarms need a permission the user has to grant on newer Android; without it, the alarm still fires but the system may delay it by a few minutes. */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarms = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    }

    private fun alarmIntent(context: Context, noteId: Long, index: Int): PendingIntent {
        // A distinct address per (note, number) keeps each alarm its own PendingIntent.
        val intent = Intent(context, NoteReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .setData(Uri.parse("notereminder://$noteId/$index"))
            .putExtra(EXTRA_NOTE_ID, noteId)
            .putExtra(EXTRA_INDEX, index)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleAlarm(context: Context, noteId: Long, index: Int, atMillis: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, noteId, index)
        if (canScheduleExactAlarms(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    /** Cancels this round's alarms from [fromIndex] on (0 = all of them, including the next first one). */
    private fun cancelRound(context: Context, noteId: Long, fromIndex: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        for (index in fromIndex until MAX_NOTIFICATIONS) alarms.cancel(alarmIntent(context, noteId, index))
    }

    // --- when an alarm goes off -----------------------------------------------------------------

    internal fun onAlarm(context: Context, noteId: Long, index: Int) {
        val reminder = ReminderStore.get(context, noteId) ?: return // removed since it was scheduled
        val note = HandwritingNoteStore.loadIndex(context).firstOrNull { it.id == noteId }
        if (note == null) {
            remove(context, noteId) // the note itself was deleted
            return
        }
        showNotification(context, noteId, note.title, note.text, index)

        // A daily reminder lines up tomorrow's first notification as soon as today's round begins.
        if (index == 0 && reminder.daily) scheduleAlarm(context, noteId, 0, nextTrigger(reminder, System.currentTimeMillis() + 60_000L))
        // The rest of this round: every 5 minutes until the last of the 4.
        if (index < MAX_NOTIFICATIONS - 1) {
            scheduleAlarm(context, noteId, index + 1, System.currentTimeMillis() + REPEAT_MS)
        } else if (!reminder.daily) {
            ReminderStore.remove(context, noteId) // a one-time reminder is finished after its 4th notification
        }
    }

    // --- notification --------------------------------------------------------------------------

    private fun notificationId(noteId: Long): Int = (noteId xor (noteId ushr 32)).toInt()

    private fun showNotification(context: Context, noteId: Long, title: String, text: String, index: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Note reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Reminders for your saved handwritten notes"
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(context, NoteReminderReceiver::class.java)
            .setAction(ACTION_STOP)
            .setData(Uri.parse("notereminder://$noteId/stop"))
            .putExtra(EXTRA_NOTE_ID, noteId)
        val stop = PendingIntent.getBroadcast(context, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val body = text.replace('\n', ' ').trim().take(300).ifEmpty { "Reminder" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("Reminder ${index + 1} of $MAX_NOTIFICATIONS")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        manager.notify(notificationId(noteId), notification)
    }

    private fun dismissNotification(context: Context, noteId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(noteId))
    }
}

/** Receives the alarms and the notification's Stop button. */
class NoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra(NoteReminders.EXTRA_NOTE_ID, -1L)
        if (noteId < 0) return
        when (intent.action) {
            NoteReminders.ACTION_FIRE -> NoteReminders.onAlarm(context, noteId, intent.getIntExtra(NoteReminders.EXTRA_INDEX, 0))
            NoteReminders.ACTION_STOP -> NoteReminders.stop(context, noteId)
        }
    }
}

/** Rebuilds the reminder alarms after the phone restarts or the app is updated (Android forgets all alarms then). */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NoteReminders.rescheduleAll(context)
        }
    }
}
