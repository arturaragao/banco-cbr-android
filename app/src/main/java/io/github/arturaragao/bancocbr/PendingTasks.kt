package io.github.arturaragao.bancocbr

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class PendingTask(val id: String, val text: String, val createdAt: String, val voice: Boolean)

/** Lógica pura da fila de tarefas pendentes (JSON em String); testável na JVM. */
object PendingTasks {
    const val EMPTY = "[]"

    /** null = JSON inválido (quem chama nunca sobrescreve a fila nesse caso). */
    fun parse(json: String): List<PendingTask>? = try {
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            PendingTask(item.getString("id"), item.getString("texto"), item.optString("criadoEm"), item.optBoolean("voz"))
        }
    } catch (_: JSONException) {
        null
    }

    fun toJson(tasks: List<PendingTask>): String = JSONArray().apply {
        tasks.forEach { task ->
            put(JSONObject().put("id", task.id).put("texto", task.text).put("criadoEm", task.createdAt).put("voz", task.voice))
        }
    }.toString()

    fun create(text: String, voice: Boolean): PendingTask =
        PendingTask(UUID.randomUUID().toString(), text.trim(), Instant.now().toString(), voice)

    /** Acrescenta ao fim; null se a fila recebida for inválida. */
    fun append(queue: String, task: PendingTask): String? = parse(queue)?.let { toJson(it + task) }

    /** Remove por ids (JSON array de strings). Ids ou fila inválidos devolvem a fila como veio. */
    fun remove(queue: String, idsJson: String): String {
        val tasks = parse(queue) ?: return queue
        val ids = try {
            val array = JSONArray(idsJson)
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (_: JSONException) {
            return queue
        }
        return toJson(tasks.filterNot { it.id in ids })
    }
}

/** Fila em SharedPreferences privadas; thread-safe por trava única. */
object PendingStore {
    private val lock = Any()
    private const val PREFS = "pending"

    fun read(context: Context): String = synchronized(lock) { readLocked(context) }

    fun tasks(context: Context): List<PendingTask> = PendingTasks.parse(read(context)).orEmpty()

    fun add(context: Context, text: String, voice: Boolean) {
        synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = readLocked(context)
            val task = PendingTasks.create(text, voice)
            val editor = prefs.edit()
            val next = PendingTasks.append(current, task) ?: run {
                editor.putString("corrompido", current) // Fila ilegível: guarda a cópia crua e recomeça.
                PendingTasks.toJson(listOf(task))
            }
            check(editor.putString("json", next).commit())
        }
    }

    fun remove(context: Context, idsJson: String) {
        synchronized(lock) {
            val current = readLocked(context)
            val next = PendingTasks.remove(current, idsJson)
            if (next != current) {
                check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", next).commit())
            }
        }
    }

    private fun readLocked(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", PendingTasks.EMPTY) ?: PendingTasks.EMPTY
}
