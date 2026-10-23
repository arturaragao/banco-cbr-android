package io.github.arturaragao.bancocbr

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

enum class NoticeKind(val hour: Int) {
    MORNING(7), SHIFT(17), EVE(20), URGENT(13)
}

data class PlannedNotice(val kind: NoticeKind, val at: ZonedDateTime)

object NotificationPlan {
    // Keep only the next alarm of each kind. Receipt arms the following one.
    fun next(
        summary: Summary?,
        now: ZonedDateTime,
        pendingDates: Map<NoticeKind, LocalDate> = emptyMap(),
        consumedDates: Map<NoticeKind, LocalDate> = emptyMap()
    ): List<PlannedNotice> {
        if (summary == null) return emptyList()
        return NoticeKind.entries.mapNotNull { kind ->
            // An inexact alarm can be late. A periodic refresh must not erase it.
            val pendingDate = pendingDates[kind]
            if (pendingDate != null && pendingDate == now.toLocalDate() && consumedDates[kind] != pendingDate &&
                text(summary, kind, pendingDate) != null) {
                val pendingAt = pendingDate.atTime(kind.hour, 0).atZone(now.zone)
                if (!pendingAt.isAfter(now)) return@mapNotNull PlannedNotice(kind, pendingAt)
            }
            (0L..35L).firstNotNullOfOrNull { offset ->
                val date = now.toLocalDate().plusDays(offset)
                val at = date.atTime(LocalTime.of(kind.hour, 0)).atZone(now.zone)
                if (at.isAfter(now) && text(summary, kind, date) != null) PlannedNotice(kind, at) else null
            }
        }
    }

    fun text(summary: Summary, kind: NoticeKind, alarmDate: LocalDate): String? {
        val date = if (kind == NoticeKind.EVE) alarmDate.plusDays(1) else alarmDate
        val day = summary.days[date]
        val urgent = summary.urgentOn(date)
        return when (kind) {
            NoticeKind.SHIFT -> when (day?.night) {
                "BR" -> "Plantão BR hoje" + (day.agenda.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                "HSI" -> "Sobreaviso HSI hoje" + (day.agenda.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                else -> null
            }
            NoticeKind.URGENT -> if (urgent.isEmpty()) null else
                "${urgent.size} urgente${if (urgent.size == 1) "" else "s"}: " + urgent.joinToString(" · ") { it.text }.take(500)
            NoticeKind.MORNING, NoticeKind.EVE -> {
                if (day?.hasActivity != true && (kind == NoticeKind.EVE || urgent.isEmpty())) return null
                val pieces = buildList {
                    if (!day?.night.isNullOrEmpty()) add(if (day?.night == "HSI") "sobreaviso HSI" else "plantão ${day?.night}")
                    if (kind == NoticeKind.MORNING && urgent.isNotEmpty()) {
                        add("${urgent.size} urgente${if (urgent.size == 1) "" else "s"}")
                    }
                    addAll(day?.allEvents.orEmpty())
                }
                (if (kind == NoticeKind.EVE) "Amanhã: " else "Hoje: ") + pieces.joinToString(" · ").take(500)
            }
        }
    }
}
