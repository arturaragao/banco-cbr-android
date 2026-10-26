package io.github.arturaragao.bancocbr

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NativeTasksTest {
    private val today = LocalDate.of(2026, 10, 26)

    private fun task(id: String, priority: String, date: LocalDate = today, done: Boolean = false) =
        NativeTask(id, "Tarefa $id", date, "", null, done, priority)

    @Test fun todayHidesDoneAndOtherDatesAndSortsPrioritiesStably() {
        val tasks = listOf(task("b", "baixa"), task("m", "média"), task("a", "alta"),
            task("u1", "U"), task("u2", "U"), task("done", "U", done = true),
            task("tomorrow", "U", today.plusDays(1)), task("yesterday", "U", today.minusDays(1)))
        assertEquals(listOf("u1", "u2", "a", "m", "b"), NativeTasks.today(tasks, today).map { it.id })
    }

    @Test fun parsesReminderAsLocalTimeAndAcceptsNullReminderAndUndatedTasks() {
        val parsed = NativeTasks.parse("""[
            {"id":"a","titulo":"Revisão","data":"2026-10-26","hora":"09:30","lembrete":"2026-10-26T09:15","feita":false,"prioridade":"U"},
            {"id":"b","titulo":"Outra","data":null,"hora":"","lembrete":null,"feita":true,"prioridade":"baixa"}
        ]""")!!
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 15), parsed[0].reminder)
        assertEquals("09:30", parsed[0].time)
        assertNull(parsed[1].date)
        assertNull(parsed[1].reminder)
        assertTrue(parsed[1].done)
        assertTrue(NativeTasks.parse("[]")!!.isEmpty())
    }

    @Test fun rejectsInvalidSnapshotsIncludingImpossibleDatesAndDuplicateIds() {
        val valid = """{"id":"a","titulo":"Tarefa","data":"2026-10-26","hora":"09:00","lembrete":"2026-10-26T08:45","feita":false,"prioridade":"alta"}"""
        assertNull(NativeTasks.parse("[$valid,$valid]"))
        assertNull(NativeTasks.parse("[$valid]".replace("2026-10-26T08:45", "2026-02-30T08:45")))
        assertNull(NativeTasks.parse("[$valid]".replace("09:00", "25:00")))
        assertNull(NativeTasks.parse("[$valid]".replace("2026-10-26\"", "2026-02-30\"")))
        assertNull(NativeTasks.parse("{broken"))
    }
}
