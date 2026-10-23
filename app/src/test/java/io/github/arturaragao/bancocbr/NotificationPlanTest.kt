package io.github.arturaragao.bancocbr

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime

class NotificationPlanTest {
    private val today = LocalDate.of(2026, 10, 23)
    private val urgent = TaskItem("1", "Revisar tórax", "estudo", false, true)
    private val shift = DayInfo("BR", listOf("Reunião X"), "", 20, 10, "estudo")

    private fun summary(tasks: List<TaskItem> = listOf(urgent), days: Map<LocalDate, DayInfo> = mapOf(today to shift)) =
        Summary("2026-10-23T08:00:00Z", today, tasks, emptyList(), days, YearMonth.from(today))

    @Test fun morningCombinesShiftUrgencyAndEvents() {
        assertEquals("Hoje: plantão BR · 1 urgente · Reunião X",
            NotificationPlan.text(summary(), NoticeKind.MORNING, today))
        assertEquals("Plantão BR hoje", NotificationPlan.text(summary(), NoticeKind.SHIFT, today))
    }

    @Test fun completedUrgenciesAreRemovedAndDoNotRepeatTomorrow() {
        val done = summary(listOf(urgent.copy(done = true)), emptyMap())
        assertNull(NotificationPlan.text(done, NoticeKind.URGENT, today))
        assertNull(NotificationPlan.text(done, NoticeKind.MORNING, today))
        assertNull(NotificationPlan.text(summary(), NoticeKind.URGENT, today.plusDays(1)))
    }

    @Test fun eveUsesTheFollowingDayAndIncludesAgenda() {
        val data = summary(emptyList(), mapOf(today.plusDays(1) to shift.copy(night = "HSI", agenda = "Consulta")))
        assertEquals("Amanhã: sobreaviso HSI · Reunião X · Consulta", NotificationPlan.text(data, NoticeKind.EVE, today))
        assertNull(NotificationPlan.text(data, NoticeKind.SHIFT, today))
    }

    @Test fun nextSkipsPastTimesAndKeepsOnlyOneAlarmPerKind() {
        val data = summary(days = mapOf(today to shift, today.plusDays(1) to shift))
        val now = ZonedDateTime.parse("2026-10-23T13:01:00-03:00[America/Sao_Paulo]")
        val next = NotificationPlan.next(data, now)
        assertEquals(3, next.size)
        assertTrue(next.all { it.at.isAfter(now) })
        assertFalse(next.any { it.kind == NoticeKind.URGENT })
        assertEquals(today.plusDays(1), next.single { it.kind == NoticeKind.MORNING }.at.toLocalDate())
        assertEquals(today, next.single { it.kind == NoticeKind.EVE }.at.toLocalDate())
        assertEquals(0, NotificationPlan.next(null, now).size)
    }

    @Test fun parsesContractAndKeepsGeneralTasksSeparate() {
        val parsed = Summary.parse("""
            {"geradoEm":"2026-10-23T08:00:00Z","hoje":"2026-10-23",
             "tarefasHoje":[{"id":"1","texto":"Revisar tórax","tipo":"estudo","feito":false,"urg":true}],
             "tarefasSem":[{"id":"2","texto":"Comprar livro","tipo":"tarefas","feito":false,"urg":false}],
             "dias":{"2026-10-23":{"noite":"BR","ev":["Reunião X"],"agenda":"","meta":20,"feito":10,"tipoDia":"estudo"}},
             "mes":"2026-10"}
        """.trimIndent())
        assertEquals(summary().todayTasks, parsed.todayTasks)
        assertEquals(1, parsed.generalTasks.size)
        assertEquals(10, parsed.days[today]?.completed)
        assertTrue(parsed.tasksOn(today.plusDays(1)).isEmpty())
    }

    @Test fun lateInexactAlarmSurvivesPeriodicRefreshUntilConsumed() {
        val now = ZonedDateTime.parse("2026-10-23T07:30:00-03:00[America/Sao_Paulo]")
        val pending = mapOf(NoticeKind.MORNING to today)
        val late = NotificationPlan.next(summary(), now, pending)
        assertEquals(7, late.single { it.kind == NoticeKind.MORNING }.at.hour)
        val consumed = NotificationPlan.next(summary(), now, pending, pending)
        assertFalse(consumed.any { it.kind == NoticeKind.MORNING })
    }

    @Test(expected = Exception::class) fun rejectsInvalidSummary() {
        Summary.parse("{\"hoje\":\"ontem\"}")
    }
}
