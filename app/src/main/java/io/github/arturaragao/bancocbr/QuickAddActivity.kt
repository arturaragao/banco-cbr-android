package io.github.arturaragao.bancocbr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

object QuickAdd {
    fun speechIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
    }

    fun spokenText(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Grava na fila e atualiza os widgets na hora, sem rede. */
    fun save(context: Context, text: String, voice: Boolean, kind: String = "") {
        PendingStore.add(context, text, voice, kind)
        RefreshCoordinator.enqueue(context.applicationContext)
    }
}

class QuickAddActivity : ComponentActivity() {
    private lateinit var input: EditText
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = if (result.resultCode == RESULT_OK) QuickAdd.spokenText(result.data) else null
        if (spoken != null) {
            input.setText(spoken)
            saveAndClose(spoken, true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.quick_add)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        input = findViewById(R.id.quick_input)
        val prefs = getSharedPreferences("draft", MODE_PRIVATE)
        input.setText(prefs.getString("text", "").orEmpty())
        input.setSelection(input.text.length)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().putString("text", s?.toString().orEmpty()).apply()
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                saveAndClose(input.text.toString(), false)
                true
            } else {
                false
            }
        }
        findViewById<Button>(R.id.quick_save).setOnClickListener { saveAndClose(input.text.toString(), false) }
        findViewById<ImageButton>(R.id.quick_mic).setOnClickListener {
            try {
                speech.launch(QuickAdd.speechIntent())
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.no_recognizer, Toast.LENGTH_SHORT).show()
            }
        }
        input.requestFocus()
    }

    private fun saveAndClose(text: String, voice: Boolean) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        QuickAdd.save(this, clean, voice)
        getSharedPreferences("draft", MODE_PRIVATE).edit().remove("text").apply()
        Toast.makeText(this, R.string.task_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}

/** Activity transparente do atalho "Falar tarefa": dispara o reconhecedor de voz na hora. */
class VoiceTaskActivity : ComponentActivity() {
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = if (result.resultCode == RESULT_OK) QuickAdd.spokenText(result.data) else null
        if (spoken != null) {
            QuickAdd.save(this, spoken, true)
            Toast.makeText(this, getString(R.string.task_saved_text, spoken), Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // Recriada (ex.: giro): o resultado chega pelo launcher.
        try {
            speech.launch(QuickAdd.speechIntent())
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(this, QuickAddActivity::class.java))
            finish()
        }
    }
}
