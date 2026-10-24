package io.github.arturaragao.bancocbr

import org.junit.Assert.*
import org.junit.Test

class PendingTasksTest {
    private fun task(id: String, text: String = "t$id", voice: Boolean = false) =
        PendingTask(id, text, "2026-10-23T10:00:00Z", voice)

    @Test fun appendKeepsOrderAndFields() {
        val first = PendingTasks.append(PendingTasks.EMPTY, task("a"))!!
        val second = PendingTasks.append(first, task("b", "consulta dia 30/10 11 horas", true))!!
        val parsed = PendingTasks.parse(second)!!
        assertEquals(listOf("a", "b"), parsed.map { it.id })
        assertEquals("consulta dia 30/10 11 horas", parsed[1].text)
        assertTrue(parsed[1].voice)
        assertFalse(parsed[0].voice)
    }

    @Test fun removeByIdsKeepsTheOthers() {
        val queue = PendingTasks.toJson(listOf(task("a"), task("b"), task("c")))
        val left = PendingTasks.parse(PendingTasks.remove(queue, """["a","c","zzz"]"""))!!
        assertEquals(listOf("b"), left.map { it.id })
    }

    @Test fun invalidJsonNeverLosesTheQueue() {
        val queue = PendingTasks.toJson(listOf(task("a")))
        assertEquals(queue, PendingTasks.remove(queue, "isso não é json"))
        assertEquals("{quebrado", PendingTasks.remove("{quebrado", """["a"]"""))
        assertNull(PendingTasks.append("{quebrado", task("b")))
        assertNull(PendingTasks.parse("lixo"))
    }

    @Test fun ideaKindSurvivesTheQueueAndCommonTasksHaveNone() {
        val queue = PendingTasks.append(PendingTasks.EMPTY, PendingTasks.create("app de laudo", true, "ideia"))!!
        val both = PendingTasks.append(queue, task("b"))!!
        assertTrue(both.contains("\"tipo\":\"ideia\""))
        assertEquals(listOf("ideia", ""), PendingTasks.parse(both)!!.map { it.kind })
    }

    @Test fun createTrimsTextAndGeneratesUniqueIds() {
        val one = PendingTasks.create("  lavar carro  ", true)
        val two = PendingTasks.create("lavar carro", true)
        assertEquals("lavar carro", one.text)
        assertNotEquals(one.id, two.id)
    }
}
