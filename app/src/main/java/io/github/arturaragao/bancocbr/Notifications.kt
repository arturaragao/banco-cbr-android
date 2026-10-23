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
import java.time.LocalDate
import java.time.ZonedDateTime

object Notifications {
    private const val CHANNEL = "avisos"
    private const val MIDNIGHT = 100

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun schedule(context: Context, summary: Summary?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val now = ZonedDateTime.now()
        val scheduled = context.getSharedPreferences("scheduled_alarms", Context.MODE_PRIVATE)
        val consumed = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
        fun date(value: String?) = value?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val pendingDates = NoticeKind.entries.mapNotNull { kind ->
            date(scheduled.getString(kind.name, null))?.let { kind to it }
        }.toMap()
        val consumedDates = NoticeKind.entries.mapNotNull { kind ->
            date(consumed.getString(kind.name, null))?.let { kind to it }
        }.toMap()
        val plannedNotices = NotificationPlan.next(summary, now, pendingDates, consumedDates)
        // Stable identities replace old alarms when a task is completed or a day changes.
        NoticeKind.entries.forEach { alarmManager.cancel(alarm(context, it.ordinal)) }
        val edit = scheduled.edit().clear()
        plannedNotices.forEach { planned ->
            val pending = alarm(context, planned.kind.ordinal, planned.at.toLocalDate().toString())
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, planned.at.toInstant().toEpochMilli(), pending)
            edit.putString(planned.kind.name, planned.at.toLocalDate().toString())
        }
        edit.apply()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, midnight.toInstant().toEpochMilli(), alarm(context, MIDNIGHT))
    }

    private fun alarm(context: Context, id: Int, date: String? = null): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            data = Uri.parse("bancocbr://alarme/$id")
            putExtra("id", id)
            if (date != null) putExtra("date", date)
        }
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun deliver(context: Context, intent: Intent) {
        val kind = NoticeKind.entries.getOrNull(intent.getIntExtra("id", MIDNIGHT)) ?: return
        val date = intent.getStringExtra("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return
        if (date != LocalDate.now()) return // Do not show a stale alarm delayed across days.
        val summary = SummaryStore.read(context) ?: return
        val text = NotificationPlan.text(summary, kind, date) ?: return
        val sent = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
        if (sent.getString(kind.name, null) == date.toString()) return
        // Consume even if permission was denied, so a late alarm is not rearmed forever.
        sent.edit().putString(kind.name, date.toString()).apply()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo)
            .setColor(0xff60a5fa.toInt())
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(AppIntents.pending(context))
            .setAutoCancel(true)
            .build()
        manager.notify(kind.ordinal + 1, notification)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        RefreshCoordinator.enqueue(context, before = { Notifications.deliver(context, intent) }, finished = { pending.finish() })
    }
}

class SystemRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        RefreshCoordinator.enqueue(context, finished = { pending.finish() })
    }
}
