package io.github.arturaragao.bancocbr

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

data class TaskItem(val id: String, val text: String, val type: String, val done: Boolean, val urgent: Boolean)

data class DayInfo(
    val night: String,
    val events: List<String>,
    val agenda: String,
    val goal: Int,
    val completed: Int,
    val type: String
) {
    val allEvents: List<String> get() = (events + listOf(agenda)).filter { it.isNotBlank() }.distinct()
    val hasActivity: Boolean get() = night.isNotEmpty() || allEvents.isNotEmpty()
}

data class Summary(
    val generatedAt: String,
    val today: LocalDate,
    val todayTasks: List<TaskItem>,
    val generalTasks: List<TaskItem>,
    val days: Map<LocalDate, DayInfo>,
    val month: YearMonth
) {
    fun tasksOn(date: LocalDate): List<TaskItem> = if (date == today) todayTasks else emptyList()
    fun urgentOn(date: LocalDate): List<TaskItem> = tasksOn(date).filter { it.urgent && !it.done }

    companion object {
        fun parse(json: String): Summary {
            require(json.toByteArray(Charsets.UTF_8).size <= 1_048_576) { "Resumo muito grande" }
            val root = JSONObject(json)
            val daysJson = root.getJSONObject("dias")
            val days = buildMap {
                for (key in daysJson.keys()) {
                    val date = LocalDate.parse(key)
                    val day = daysJson.getJSONObject(key)
                    put(date, DayInfo(
                        day.optString("noite").takeIf { it == "BR" || it == "HSI" }.orEmpty(),
                        day.optJSONArray("ev")?.let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        }.orEmpty(),
                        day.optString("agenda"), day.optInt("meta").coerceAtLeast(0),
                        day.optInt("feito").coerceAtLeast(0), day.optString("tipoDia")
                    ))
                }
            }
            return Summary(
                root.getString("geradoEm"), LocalDate.parse(root.getString("hoje")),
                tasks(root.getJSONArray("tarefasHoje")), tasks(root.getJSONArray("tarefasSem")),
                days, YearMonth.parse(root.getString("mes"))
            )
        }

        private fun tasks(array: JSONArray): List<TaskItem> = (0 until array.length()).map { index ->
            val task = array.getJSONObject(index)
            TaskItem(task.getString("id"), task.getString("texto"), task.optString("tipo", "tarefas"),
                task.optBoolean("feito"), task.optBoolean("urg"))
        }
    }
}
