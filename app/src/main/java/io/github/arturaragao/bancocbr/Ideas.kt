package io.github.arturaragao.bancocbr

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Executors

/** Lógica pura (testada): formato das linhas de ideias/caixa.md. "- [ ]" = nova; o Claude no PC marca "[x]". */
object Ideas {
    const val HEADER = "# Caixa de ideias do celular\n\n" +
        "Gravadas pelo widget Ideia do app Banco CBR. \"- [ ]\" = nova; o Claude no PC oferece e marca \"[x]\".\n\n"
    private val formato = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun line(text: String, at: LocalDateTime): String =
        "- [ ] ${at.format(formato)} — ${text.trim().replace(Regex("\\s+"), " ")}"

    fun cleanFilename(name: String): String {
        val clean = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('.', '-', '_').ifEmpty { "arquivo" }
        if (clean.length <= 60) return clean
        val dot = clean.lastIndexOf('.')
        val extension = if (dot > 0) clean.substring(dot) else ""
        // Extensões usuais ficam inteiras; reserva ao menos um caractere para o nome.
        val suffix = extension.take(59)
        return clean.take(60 - suffix.length).trimEnd('.', '-', '_').ifEmpty { "a" } + suffix
    }

    fun attachmentSuffix(files: List<String>): String =
        if (files.isEmpty()) "" else " · anexos: " + files.joinToString(", ") { "anexos/$it" }

    fun appendTo(existing: String?, lines: List<String>): String {
        val base = if (existing.isNullOrBlank()) HEADER else existing.trimEnd('\n', '\r') + "\n"
        return base + lines.joinToString("\n") + "\n"
    }

    /** Junta um trecho ditado ao texto que já existe (gravação em várias partes). */
    fun join(base: String, spoken: String): String {
        val b = base.trimEnd()
        val a = spoken.trim()
        return when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> "$b $a"
        }
    }

    /** localStorage.getItem via evaluateJavascript devolve um literal JSON ("\"abc\"" ou "null"). */
    fun tokenFromJs(result: String?): String? = try {
        (JSONTokener(result ?: "null").nextValue() as? String)?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}

internal data class IdeaAttachment(val uri: Uri, val name: String, val size: Long)
private class AttachmentTooLargeException(val uri: Uri? = null) : IOException()

/** Mantém seleções e operações de disco em andamento durante a recriação da Activity. */
internal class IdeaDraft : ViewModel() {
    val io = Executors.newSingleThreadExecutor()
    val attachments = mutableListOf<IdeaAttachment>()
    val errors = mutableListOf<String>()
    var busy = false
    var saved = false
    var changed: (() -> Unit)? = null

    override fun onCleared() {
        io.shutdown()
    }
}

/** Fila local: mantém as linhas antigas e associa os nomes das cópias locais a cada linha. */
object IdeaStore {
    const val MAX_ATTACHMENT_BYTES = 15L * 1024 * 1024
    private val lock = Any()
    private val fileDate = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    private fun prefs(context: Context) = context.getSharedPreferences("ideias", Context.MODE_PRIVATE)
    fun attachmentFile(context: Context, name: String) = File(File(context.filesDir, "ideias-anexos"), name)
    private fun draftAttachmentFile(context: Context, name: String) =
        File(File(context.filesDir, "ideias-anexos"), "rascunho/$name")

    fun copyToDraft(context: Context, uri: Uri, name: String): Pair<Uri, Long> {
        var file = draftAttachmentFile(context, "${System.nanoTime()}-${Ideas.cleanFilename(name)}")
        while (file.exists()) file = draftAttachmentFile(context, "${System.nanoTime()}-${Ideas.cleanFilename(name)}")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        try {
            val size = context.contentResolver.openInputStream(uri).use { source ->
                checkNotNull(source) { "Não foi possível abrir $name" }
                file.outputStream().use { copyLimited(source, it) }
            }
            return Uri.fromFile(file) to size
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun deleteDraftAttachments(context: Context) {
        draftAttachmentFile(context, ".placeholder").parentFile?.listFiles()?.forEach { it.delete() }
    }

    private fun attachments(context: Context) = JSONObject(prefs(context).getString("anexos", "{}").orEmpty())

    fun filesFor(context: Context, lines: List<String>): List<String> = synchronized(lock) {
        val attachments = attachments(context)
        lines.flatMap { line ->
            val files = attachments.optJSONArray(line) ?: JSONArray()
            List(files.length()) { files.getString(it) }
        }
    }

    /** Confere o tamanho real, inclusive quando o provedor não informa SIZE ou informa errado. */
    fun copyLimited(input: InputStream, output: OutputStream? = null): Long {
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return size
            size += count
            if (size > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
            output?.write(buffer, 0, count)
        }
    }

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

    internal fun addWithAttachments(context: Context, text: String, selected: List<IdeaAttachment>) = synchronized(lock) {
        val at = LocalDateTime.now()
        val copied = mutableListOf<String>()
        var sequence = prefs(context).getLong("anexo_seq", 0L)
        try {
            selected.forEach { attachment ->
                var name: String
                do {
                    name = "${at.format(fileDate)}-${++sequence}-${Ideas.cleanFilename(attachment.name)}"
                } while (attachmentFile(context, name).exists())
                val file = attachmentFile(context, name)
                check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                copied += name // inclui cópia parcial na limpeza se houver erro.
                (if (attachment.uri.scheme == "file") FileInputStream(attachment.uri.path!!) else
                    context.contentResolver.openInputStream(attachment.uri)).use { source ->
                    checkNotNull(source) { "Não foi possível abrir ${attachment.name}" }
                    try {
                        file.outputStream().use { copyLimited(source, it) }
                    } catch (_: AttachmentTooLargeException) {
                        throw AttachmentTooLargeException(attachment.uri)
                    }
                }
            }
            val line = Ideas.line(text, at) + Ideas.attachmentSuffix(copied)
            val metadata = attachments(context)
            if (copied.isNotEmpty()) metadata.put(line, JSONArray(copied))
            check(prefs(context).edit()
                .putString("fila", (lines(context) + line).joinToString("\n"))
                .putString("anexos", metadata.toString())
                .putLong("anexo_seq", sequence).commit())
        } catch (e: Exception) {
            copied.forEach { attachmentFile(context, it).delete() }
            throw e
        }
    }

    /** Tira só as linhas enviadas (as que chegaram depois ficam). */
    fun removeSent(context: Context, sent: List<String>) = synchronized(lock) {
        val files = filesFor(context, sent)
        val rest = lines(context).toMutableList().apply { sent.forEach { remove(it) } }
        val metadata = attachments(context)
        sent.forEach { metadata.remove(it) }
        check(prefs(context).edit().putString("fila", rest.joinToString("\n"))
            .putString("anexos", metadata.toString()).commit())
        files.forEach { attachmentFile(context, it).delete() }
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
        for (name in IdeaStore.filesFor(context, lines)) {
            if (!uploadAttachment(context, token, name)) return false
        }
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

    private fun uploadAttachment(context: Context, token: String, name: String): Boolean {
        val file = IdeaStore.attachmentFile(context, name)
        check(file.length() <= IdeaStore.MAX_ATTACHMENT_BYTES)
        val bytes = file.readBytes()
        val url = URL_ARQ.substringBeforeLast('/') + "/anexos/$name"
        val body = JSONObject().put("message", "anexo de ideia")
            .put("content", Base64.getEncoder().encodeToString(bytes))
        val code = request(token, "PUT", body.toString(), url).first
        if (code == 200 || code == 201) return true
        if (code != 409 && code != 422) return false
        // Um envio anterior pode ter terminado antes de cair a conexão ou falhar caixa.md.
        // Confere o blob existente sem sobrescrever outro arquivo e sem duplicar anexos.
        val (readCode, text) = request(token, "GET", null, url)
        if (readCode != 200) return false
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        val sha = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject(text).optString("sha") == sha
    }

    private fun read(token: String): Pair<String?, String?> {
        val (code, text) = request(token, "GET", null)
        if (code == 404) return null to null
        check(code == 200) { "GET $code" }
        val json = JSONObject(text)
        val content = String(Base64.getMimeDecoder().decode(json.getString("content")))
        return content to json.getString("sha")
    }

    private fun request(token: String, method: String, body: String?, url: String = URL_ARQ): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
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

/**
 * Tela do widget Ideia (APK 1.3): grava até o Artur tocar em ⏸ (pausas na fala não encerram),
 * mostrando o texto enquanto fala; dá para editar, continuar falando e salvar.
 * Salvar = fila do GitHub (ideias/caixa.md) + tarefa pendente tipo "ideia" (o site cria "💡 ..." em Ferramenta).
 */
class IdeaActivity : ComponentActivity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var mic: Button
    private var recognizer: SpeechRecognizer? = null
    private var listening = false // laço de gravação ligado
    private var finishing = false // pediu para parar; esperando o último trecho (onResults/onError)
    private var saveAfterFinish = false
    private var base = "" // texto já fechado
    private var partial = "" // trecho da rodada atual, ainda não fechado
    private var failures = 0
    private var pulse: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var draft: IdeaDraft
    private val attachments get() = draft.attachments
    private lateinit var attachmentList: LinearLayout
    private lateinit var attachmentScroll: ScrollView
    private lateinit var attach: Button
    private val busy get() = draft.busy

    private val documents = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val uris = mutableListOf<Uri>()
            data?.data?.let {
                runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                uris += it
            }
            data?.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let {
                    runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    uris += it
                }
            }
            addAttachments(uris)
        }
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else showPaused(R.string.idea_no_mic, keyboard = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        draft = ViewModelProvider(this)[IdeaDraft::class.java]
        setContentView(R.layout.idea)
        setFinishOnTouchOutside(false)
        input = findViewById(R.id.idea_input)
        status = findViewById(R.id.idea_status)
        mic = findViewById(R.id.idea_mic)
        setupAttachments()
        mic.setOnClickListener { if (!busy) { if (listening) stop() else if (!finishing) start() } }
        findViewById<Button>(R.id.idea_save).setOnClickListener { save() }
        findViewById<Button>(R.id.idea_cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.idea_root).setOnClickListener {
            if (!busy && input.text.isBlank() && attachments.isEmpty()) finish()
        }
        input.setOnFocusChangeListener { _, focused -> if (focused && listening) stop() }
        if (savedInstanceState != null) {
            val uris = savedInstanceState.getStringArrayList("attachment_uris").orEmpty()
            val names = savedInstanceState.getStringArrayList("attachment_names").orEmpty()
            val sizes = savedInstanceState.getLongArray("attachment_sizes") ?: longArrayOf()
            if (attachments.isEmpty() && !busy && !draft.saved) {
                for (i in uris.indices) {
                    if (i < names.size && i < sizes.size) attachments += IdeaAttachment(Uri.parse(uris[i]), names[i], sizes[i])
                }
            }
            renderAttachments()
            showPaused(R.string.idea_paused)
        } else if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            show(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty())
            showPaused(R.string.idea_paused)
            receiveAttachments(intent)
        } else start()
        draft.changed = { refreshDraft() }
        refreshDraft()
    }

    /** Insere os controles junto ao campo existente sem depender de novos recursos de layout. */
    private fun setupAttachments() {
        val parent = input.parent as ViewGroup
        val index = parent.indexOfChild(input)
        val params = input.layoutParams
        parent.removeView(input)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(column, index, params)
        val fill = params.height != ViewGroup.LayoutParams.WRAP_CONTENT
        column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            if (fill) 0 else ViewGroup.LayoutParams.WRAP_CONTENT, if (fill) 1f else 0f))
        attach = Button(this).apply {
            text = "📎 Anexar"
            setOnClickListener {
                if (!busy) documents.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                })
            }
        }
        column.addView(attach)
        attachmentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        attachmentScroll = ScrollView(this).apply { addView(attachmentList); visibility = View.GONE }
        column.addView(attachmentScroll)
    }

    @Suppress("DEPRECATION") // Sobrecargas tipadas de Parcelable só existem a partir da API 33.
    private fun receiveAttachments(shared: Intent) {
        val uris = mutableListOf<Uri>()
        if (shared.action == Intent.ACTION_SEND_MULTIPLE) {
            shared.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        } else shared.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
        shared.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
        }
        addAttachments(uris, copyToDraft = true)
    }

    private fun addAttachments(uris: List<Uri>, copyToDraft: Boolean = false) {
        val fresh = uris.distinct().filter { uri -> attachments.none { it.uri == uri } }
        if (fresh.isEmpty()) return
        setBusy(true)
        draft.io.execute {
            val accepted = mutableListOf<IdeaAttachment>()
            val errors = mutableListOf<String>()
            fresh.forEach { uri ->
                try {
                    // Não permite que um compartilhamento exponha arquivos privados via file://.
                    require(uri.scheme == "content")
                    var name = "arquivo"
                    var size = -1L
                    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                        null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                            if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                            if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                        }
                    }
                    if (size > IdeaStore.MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
                    if (copyToDraft) {
                        val (localUri, localSize) = IdeaStore.copyToDraft(applicationContext, uri, name)
                        accepted += IdeaAttachment(localUri, name, localSize)
                    } else {
                        // Confere SIZE contra o conteúdo real. A cópia definitiva só ocorre ao salvar.
                        size = contentResolver.openInputStream(uri).use {
                            IdeaStore.copyLimited(checkNotNull(it))
                        }
                        accepted += IdeaAttachment(uri, name, size)
                    }
                } catch (_: AttachmentTooLargeException) {
                    errors += "Arquivo acima de 15 MB: não anexado."
                } catch (_: Exception) {
                    errors += "Não foi possível abrir um anexo."
                }
            }
            runOnUiThread {
                attachments.addAll(accepted)
                draft.busy = false
                draft.errors.addAll(errors.distinct())
                draft.changed?.invoke()
            }
        }
    }

    private fun renderAttachments() {
        attachmentList.removeAllViews()
        attachments.forEach { attachment ->
            val row = LinearLayout(this)
            row.addView(TextView(this).apply {
                text = "${attachment.name} (${android.text.format.Formatter.formatFileSize(this@IdeaActivity, attachment.size)})"
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "✕"
                contentDescription = "Remover ${attachment.name}"
                setOnClickListener { if (!busy) { attachments.remove(attachment); renderAttachments() } }
            })
            attachmentList.addView(row)
        }
        attachmentScroll.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        attachmentScroll.layoutParams = attachmentScroll.layoutParams.apply {
            height = (minOf(attachments.size, 2) * 64 * resources.displayMetrics.density).toInt()
        }
    }

    private fun setBusy(value: Boolean) {
        draft.busy = value
        updateBusyControls()
    }

    private fun updateBusyControls() {
        val value = busy
        attach.isEnabled = !value
        mic.isEnabled = !value && !finishing
        input.isEnabled = !value && !finishing
        findViewById<Button>(R.id.idea_save).isEnabled = !value
        findViewById<Button>(R.id.idea_cancel).isEnabled = !value
    }

    private fun refreshDraft() {
        updateBusyControls()
        renderAttachments()
        draft.errors.forEach { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        draft.errors.clear()
        if (draft.saved) saved()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("attachment_uris", ArrayList(attachments.map { it.uri.toString() }))
        outState.putStringArrayList("attachment_names", ArrayList(attachments.map { it.name }))
        outState.putLongArray("attachment_sizes", attachments.map { it.size }.toLongArray())
        super.onSaveInstanceState(outState)
    }

    private fun start() {
        if (finishing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return showPaused(R.string.no_recognizer, keyboard = true)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        input.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        base = input.text.toString().trim()
        partial = ""
        failures = 0
        listening = true
        status.setText(R.string.idea_listening)
        mic.text = "⏸"
        mic.setBackgroundResource(R.drawable.idea_mic_on)
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            mic,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.1f),
        ).apply {
            duration = 650
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
        listenOnce()
    }

    /** Uma rodada do reconhecedor; ao fim (resultado ou silêncio) começa outra enquanto listening. */
    private fun listenOnce() {
        if (!listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Pedem mais tolerância ao silêncio (alguns aparelhos ignoram; o laço cobre o resto).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 5000L)
        })
    }

    private fun restart(delay: Long, fresh: Boolean) {
        if (fresh) {
            recognizer?.destroy()
            recognizer = null
        }
        handler.postDelayed({ listenOnce() }, delay)
    }

    private fun show(text: String) {
        input.setText(text)
        input.setSelection(text.length)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(bundle: Bundle?) {
            val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
            if (!listening && !finishing) return
            partial = heard
            show(Ideas.join(base, partial))
        }

        override fun onResults(results: Bundle?) {
            if (!listening && !finishing) return
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            base = Ideas.join(base, heard.ifBlank { partial })
            partial = ""
            show(base)
            failures = 0
            if (finishing) endRound() else if (listening) restart(80, fresh = false)
        }

        override fun onError(error: Int) {
            if (!listening && !finishing) return
            commitPartial()
            if (finishing) return endRound()
            when (error) {
                // Silêncio: não é fim da ideia, só começa outra rodada.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(80, fresh = false)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> showPaused(R.string.idea_no_mic, keyboard = true)
                else -> if (++failures >= 3) showPaused(R.string.idea_mic_error) else restart(400, fresh = true)
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Fecha o trecho em andamento (rodada que terminou sem resultado final). */
    private fun commitPartial() {
        if (partial.isBlank()) return
        base = Ideas.join(base, partial)
        partial = ""
        show(base)
    }

    /** Para de ouvir. O texto só fica editável quando o último trecho chega (ou após 2 s), para não perder nem repetir palavras. */
    private fun stop() {
        listening = false
        finishing = true
        handler.removeCallbacksAndMessages(null)
        input.isEnabled = false
        mic.isEnabled = false
        showPaused(R.string.idea_paused)
        recognizer?.stopListening()
        handler.postDelayed({ if (finishing) { recognizer?.cancel(); endRound() } }, 2000)
    }

    /** Fim da última rodada depois do pedido de parar. */
    private fun endRound() {
        handler.removeCallbacksAndMessages(null)
        commitPartial()
        finishing = false
        input.isEnabled = !busy
        mic.isEnabled = !busy
        if (saveAfterFinish) saveNow()
    }

    private fun showPaused(message: Int, keyboard: Boolean = false) {
        listening = false
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        pulse = null
        mic.scaleX = 1f
        mic.scaleY = 1f
        mic.text = "🎤"
        mic.setBackgroundResource(R.drawable.idea_mic_off)
        status.setText(message)
        if (keyboard) {
            input.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
        }
    }

    /** Gravando: para, espera o último trecho e só então salva. */
    private fun save() {
        if (busy) return
        when {
            listening -> { saveAfterFinish = true; stop() }
            finishing -> saveAfterFinish = true
            else -> saveNow()
        }
    }

    private fun saveNow() {
        saveAfterFinish = false
        val text = input.text.toString().trim()
        if (text.isEmpty() && attachments.isEmpty()) return finish()
        if (attachments.isNotEmpty()) {
            val selected = attachments.toList()
            setBusy(true)
            draft.io.execute {
                try {
                    IdeaStore.addWithAttachments(applicationContext, text, selected)
                } catch (e: Exception) {
                    runOnUiThread {
                        draft.busy = false
                        if (e is AttachmentTooLargeException) {
                            attachments.removeAll { it.uri == e.uri }
                            draft.errors += "Arquivo acima de 15 MB: não anexado. Salve novamente."
                        } else draft.errors += "Não foi possível copiar os anexos. A ideia não foi salva. Tente novamente."
                        draft.changed?.invoke()
                    }
                    return@execute
                }
                // A fila já está persistida: erros posteriores não devem oferecer salvar de novo.
                try {
                    IdeaUpload.enqueue(applicationContext)
                    QuickAdd.save(applicationContext, text, voice = true, kind = "ideia")
                } finally {
                    runOnUiThread {
                        draft.saved = true
                        draft.busy = false
                        draft.changed?.invoke()
                    }
                }
            }
            return
        }
        IdeaStore.add(this, text)
        IdeaUpload.enqueue(applicationContext)
        QuickAdd.save(this, text, voice = true, kind = "ideia")
        saved()
    }

    private fun saved() {
        val msg = if (IdeaStore.token(this) == null) R.string.idea_no_token else R.string.idea_saved
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (listening && !isFinishing) stop()
    }

    override fun onDestroy() {
        draft.changed = null
        if (isFinishing) IdeaStore.deleteDraftAttachments(applicationContext)
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
