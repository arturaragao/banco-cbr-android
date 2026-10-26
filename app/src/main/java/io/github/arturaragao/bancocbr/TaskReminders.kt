package io.github.arturaragao.bancocbr

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.ZoneId

object TaskReminders {
    private const val CHANNEL = "lembretes"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Lembretes", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun alarm(context: Context, id: String, reminder: String? = null): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java).apply {
            data = Uri.Builder().scheme("bancocbr").authority("lembrete").appendPath(id).build()
            putExtra("tarefa", id)
            putExtra("lembrete", reminder)
        }
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        val prefs = context.getSharedPreferences("task_reminders", Context.MODE_PRIVATE)
        val tasks = NativeTaskStore.read(context).filter { !it.done && it.reminder != null }
        val byId = tasks.associateBy { it.id }
        val ids = tasks.map { it.id }.toSet()
        val previous = prefs.getStringSet("ids", emptySet()).orEmpty().toSet()
        val editor = prefs.edit()
        val now = Instant.now().toEpochMilli()
        (previous + ids).forEach { id ->
            manager.cancel(alarm(context, id))
            val reminder = byId[id]?.reminder
            val next = reminder?.toString()
            if (next == null || prefs.getString("at:$id", null) != next) {
                notifications.cancel("lembrete:$id", 1)
                editor.remove("sent:$id")
                editor.remove("armed:$id")
            }
            if (next == null) editor.remove("at:$id") else editor.putString("at:$id", next)
            if (reminder != null && reminder.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() > now) {
                editor.putString("armed:$id", next)
            }
        }
        // Remember identities before arming, so a restart can also cancel obsolete alarms.
        check(editor.putStringSet("ids", ids).commit())
        tasks.forEach { task ->
            val reminder = requireNotNull(task.reminder)
            val at = reminder.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (prefs.getString("sent:${task.id}", null) == reminder.toString()) return@forEach
            // Restore an overdue alarm previously armed (reboot/inexact delivery), but do not
            // notify for an old reminder imported for the first time.
            if (at <= now && prefs.getString("armed:${task.id}", null) != reminder.toString()) return@forEach
            val pending = alarm(context, task.id, reminder.toString())
            val exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
            if (exact) {
                try {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                } catch (_: SecurityException) {
                    // Access can be revoked between canScheduleExactAlarms() and scheduling.
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                }
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        }
    }

    fun deliver(context: Context, intent: Intent) {
        val id = intent.getStringExtra("tarefa") ?: return
        val expected = intent.getStringExtra("lembrete") ?: return
        val task = NativeTaskStore.read(context).firstOrNull { it.id == id && !it.done } ?: return
        val reminder = task.reminder ?: return
        if (reminder.toString() != expected || reminder.atZone(ZoneId.systemDefault()).toInstant().isAfter(Instant.now())) return
        val prefs = context.getSharedPreferences("task_reminders", Context.MODE_PRIVATE)
        if (prefs.getString("sent:$id", null) == expected) return
        check(prefs.edit().putString("sent:$id", expected).remove("armed:$id").commit())
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        createChannel(context)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setContentTitle(task.title)
            .setContentText(context.getString(R.string.task_reminder))
            .setStyle(Notification.BigTextStyle().bigText(task.title))
            .setContentIntent(AppIntents.pending(context))
            .setAutoCancel(true)
            .build()
        manager.notify("lembrete:$id", 1, notification)
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        RefreshCoordinator.enqueue(context, before = { TaskReminders.deliver(context, intent) }, finished = { pending.finish() })
    }
}
