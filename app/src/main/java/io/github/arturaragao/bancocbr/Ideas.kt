package io.github.arturaragao.bancocbr

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Lógica pura (testada): formato das linhas de ideias/caixa.md. "- [ ]" = nova; o Claude no PC marca "[x]". */
object Ideas {
    const val HEADER = "# Caixa de ideias do celular\n\n" +
        "Gravadas pelo widget Ideia do app Banco CBR. \"- [ ]\" = nova; o Claude no PC oferece e marca \"[x]\".\n\n"
    private val formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun line(text: String, at: LocalDateTime): String =
        "- [ ] ${at.format(formato)} — ${text.trim().replace(Regex("\\s+"), " ")}"

    fun appendTo(existing: String?, lines: List<String>): String {
        val base = if (existing.isNullOrBlank()) HEADER else existing.trimEnd('\n', '\r') + "\n"
        return base + lines.joinToString("\n") + "\n"
    }

    /** localStorage.getItem via evaluateJavascript devolve um literal JSON ("\"abc\"" ou "null"). */
    fun tokenFromJs(result: String?): String? = try {
        (JSONTokener(result ?: "null").nextValue() as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

/** Fila local de ideias (linhas prontas) e a chave do GitHub copiada da WebView. */
object IdeaStore {
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences("ideias", Context.MODE_PRIVATE)

    fun token(context: Context): String? = prefs(context).getString("token", null)

    fun saveToken(context: Context, token: String) {
        if (token(context) != token) prefs(context).edit().putString("token", token).apply()
    }

    fun lines(context: Context): List<String> = synchronized(lock) {
        prefs(context).getString("fila", "").orEmpty().split('\n').filter { it.isNotBlank() }
    }

    fun add(context: Context, text: String) = synchronized(lock) {
        val next = lines(context) + Ideas.line(text, LocalDateTime.now())
        check(prefs(context).edit().putString("fila", next.joinToString("\n")).commit())
    }

    /** Tira só as linhas enviadas (as que chegaram depois ficam). */
    fun removeSent(context: Context, sent: List<String>) = synchronized(lock) {
        val rest = lines(context).toMutableList().apply { sent.forEach { remove(it) } }
        check(prefs(context).edit().putString("fila", rest.joinToString("\n")).commit())
    }
}

object IdeaUpload {
    private const val URL_ARQ = "https://api.github.com/repos/arturaragao/banco-cbr-app/contents/ideias/caixa.md"

    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<IdeaWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("ideias", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** true = enviou (ou nada a enviar); false = tentar de novo; sem chave lança IllegalStateException. */
    fun send(context: Context): Boolean {
        val lines = IdeaStore.lines(context)
        if (lines.isEmpty()) return true
        val token = IdeaStore.token(context) ?: throw IllegalStateException("sem chave")
        repeat(2) { // 2ª volta só se o arquivo mudou no meio (409/422).
            val (existing, sha) = read(token)
            val body = JSONObject()
                .put("message", "ideia do celular")
                .put("content", Base64.getEncoder().encodeToString(Ideas.appendTo(existing, lines).toByteArray()))
            if (sha != null) body.put("sha", sha)
            val code = request(token, "PUT", body.toString()).first
            if (code == 200 || code == 201) {
                IdeaStore.removeSent(context, lines)
                return true
            }
            if (code != 409 && code != 422) return false
        }
        return false
    }

    private fun read(token: String): Pair<String?, String?> {
        val (code, text) = request(token, "GET", null)
        if (code == 404) return null to null
        check(code == 200) { "GET $code" }
        val json = JSONObject(text)
        val content = String(Base64.getMimeDecoder().decode(json.getString("content")))
        return content to json.getString("sha")
    }

    private fun request(token: String, method: String, body: String?): Pair<Int, String> {
        val c = URL(URL_ARQ).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }
}

class IdeaWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        if (IdeaUpload.send(applicationContext)) Result.success() else Result.retry()
    } catch (_: IllegalStateException) {
        // Sem chave ou GET com erro: a fila espera; o app reenvia ao abrir e pegar a chave (MainActivity).
        if (IdeaStore.token(applicationContext) == null) Result.success() else Result.retry()
    } catch (_: Exception) {
        Result.retry()
    }
}

/** Widget "Ideia": 1 toque abre o reconhecedor de voz do Android. Independente dos outros widgets. */
class IdeaWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_idea)
        views.setOnClickPendingIntent(R.id.widget_root, AppIntents.idea(context))
        manager.updateAppWidget(ids, views)
    }
}

/** Transparente: voz → confirmar/editar → fila local → envio ao GitHub em segundo plano. */
class IdeaActivity : ComponentActivity() {
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = if (result.resultCode == RESULT_OK) QuickAdd.spokenText(result.data) else null
        if (spoken == null) finish() else confirm(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        listen("")
    }

    private fun listen(typed: String) {
        try {
            speech.launch(QuickAdd.speechIntent())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_recognizer, Toast.LENGTH_SHORT).show()
            confirm(typed)
        }
    }

    private fun confirm(text: String) {
        val input = EditText(this).apply {
            setText(text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.idea_title)
            .setView(input)
            .setPositiveButton(R.string.quick_save) { _, _ -> save(input.text.toString()) }
            .setNeutralButton(R.string.idea_again) { _, _ -> listen(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun save(text: String) {
        if (text.isBlank()) return finish()
        IdeaStore.add(this, text)
        IdeaUpload.enqueue(applicationContext)
        val msg = if (IdeaStore.token(this) == null) R.string.idea_no_token else R.string.idea_saved
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
