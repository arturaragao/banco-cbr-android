package io.github.arturaragao.bancocbr

import android.content.Context

object SummaryStore {
    fun read(context: Context): Summary? = context.getSharedPreferences("summary", Context.MODE_PRIVATE)
        .getString("json", null)?.let { runCatching { Summary.parse(it) }.getOrNull() }

    fun save(context: Context, json: String) {
        Summary.parse(json) // Validate before replacing the last usable snapshot.
        check(context.getSharedPreferences("summary", Context.MODE_PRIVATE).edit().putString("json", json).commit())
    }
}
