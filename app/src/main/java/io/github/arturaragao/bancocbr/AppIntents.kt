package io.github.arturaragao.bancocbr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

object AppIntents {
    fun open(context: Context, action: String? = null): Intent = Intent(context, MainActivity::class.java).apply {
        this.action = Intent.ACTION_VIEW
        data = Uri.parse("bancocbr://abrir").buildUpon().apply {
            if (action != null) appendQueryParameter("acao", action)
        }.build()
        if (action != null) putExtra("acao", action)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

    fun quickAdd(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 1, Intent(context, QuickAddActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun idea(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 2, Intent(context, IdeaActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun pending(context: Context, action: String? = null): PendingIntent = PendingIntent.getActivity(
        context, 0, open(context, action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
