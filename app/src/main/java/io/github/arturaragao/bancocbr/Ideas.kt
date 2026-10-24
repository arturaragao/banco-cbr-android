package io.github.arturaragao.bancocbr

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.RemoteViews
import android.widget.TextView
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

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else showPaused(R.string.idea_no_mic, keyboard = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.idea)
        setFinishOnTouchOutside(false)
        input = findViewById(R.id.idea_input)
        status = findViewById(R.id.idea_status)
        mic = findViewById(R.id.idea_mic)
        mic.setOnClickListener { if (listening) stop() else if (!finishing) start() }
        findViewById<Button>(R.id.idea_save).setOnClickListener { save() }
        findViewById<Button>(R.id.idea_cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.idea_root).setOnClickListener { if (input.text.isBlank()) finish() }
        input.setOnFocusChangeListener { _, focused -> if (focused && listening) stop() }
        if (savedInstanceState == null) start() else showPaused(R.string.idea_paused)
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
        input.isEnabled = true
        mic.isEnabled = true
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
        when {
            listening -> { saveAfterFinish = true; stop() }
            finishing -> saveAfterFinish = true
            else -> saveNow()
        }
    }

    private fun saveNow() {
        saveAfterFinish = false
        val text = input.text.toString().trim()
        if (text.isEmpty()) return finish()
        IdeaStore.add(this, text)
        IdeaUpload.enqueue(applicationContext)
        QuickAdd.save(this, text, voice = true, kind = "ideia")
        val msg = if (IdeaStore.token(this) == null) R.string.idea_no_token else R.string.idea_saved
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (listening && !isFinishing) stop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pulse?.cancel()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }
}
