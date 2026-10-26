package io.github.arturaragao.bancocbr

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class NativeTask(
    val id: String,
    val title: String,
    val date: LocalDate?,
    val time: String,
    val reminder: LocalDateTime?,
    val done: Boolean,
    val priority: String
) {
    val rank: Int get() = when (priority.lowercase(Locale.ROOT)) {
        "u" -> 0
        "alta" -> 1
        "média", "media" -> 2
        else -> 3
    }
}

object NativeTasks {
    private val reminderFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm")
        .withResolverStyle(java.time.format.ResolverStyle.STRICT)

    /** Reject the entire invalid snapshot, preserving the last usable one. */
    fun parse(json: String): List<NativeTask>? = runCatching {
        require(json.toByteArray(Charsets.UTF_8).size <= 1024 * 1024)
        val array = JSONArray(json)
        val tasks = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.getString("id")
            require(id.isNotBlank())
            val date = if (item.isNull("data") || item.optString("data").isEmpty()) null
                else LocalDate.parse(item.getString("data"))
            val time = item.optString("hora", "")
            if (time.isNotEmpty()) LocalTime.parse(time)
            val reminder = if (item.isNull("lembrete")) null
                else LocalDateTime.parse(item.getString("lembrete"), reminderFormat)
            NativeTask(id, item.getString("titulo"), date, time, reminder,
                item.getBoolean("feita"), item.optString("prioridade", "baixa"))
        }
        require(tasks.map { it.id }.toSet().size == tasks.size)
        tasks
    }.getOrNull()

    fun today(tasks: List<NativeTask>, today: LocalDate = LocalDate.now()): List<NativeTask> =
        tasks.filter { it.date == today && !it.done }.sortedBy { it.rank }
}

object NativeTaskStore {
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("native_tasks", Context.MODE_PRIVATE)

    fun hasSnapshot(context: Context): Boolean = prefs(context).contains("json")

    fun save(context: Context, json: String) {
        val tasks = NativeTasks.parse(json) ?: return
        synchronized(lock) {
            // Keep a local completion hidden while the site still sends its old snapshot.
            val unfinished = tasks.filterNot { it.done }.map { it.id }.toSet()
            val completed = prefs(context).getStringSet("completed", emptySet()).orEmpty().intersect(unfinished)
            check(prefs(context).edit().putString("json", json).putStringSet("completed", completed).commit())
        }
    }

    fun read(context: Context): List<NativeTask> = synchronized(lock) {
        val stored = prefs(context).getString("json", null)
        val tasks = if (stored != null) NativeTasks.parse(stored).orEmpty() else {
            // Compatibility until the site sends tarefas() for the first time.
            SummaryStore.read(context)?.tasksOn(LocalDate.now()).orEmpty().map {
                NativeTask(it.id, it.text, LocalDate.now(), "", null, it.done, if (it.urgent) "U" else "baixa")
            }
        }
        val completed = prefs(context).getStringSet("completed", emptySet()).orEmpty() +
            PendingStore.tasks(context).filter { it.kind == "concluir" }.map { it.taskId }
        tasks.map { if (it.id in completed) it.copy(done = true) else it }
    }

    fun conclude(context: Context, id: String) = synchronized(lock) {
        if (NativeTasks.today(read(context)).none { it.id == id }) return@synchronized
        // Persist the queue first; read() also masks queued completions after an interrupted write.
        PendingStore.conclude(context, id)
        val completed = prefs(context).getStringSet("completed", emptySet()).orEmpty() + id
        check(prefs(context).edit().putStringSet("completed", completed).commit())
    }
}
