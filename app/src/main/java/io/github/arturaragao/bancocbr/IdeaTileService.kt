package io.github.arturaragao.bancocbr

import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Bloco rápido "Ideia": um toque abre a tela de ditar ideia, igual ao widget/atalho. */
class IdeaTileService : TileService() {
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: a versão com Intent foi deprecada; usar PendingIntent (o mesmo do widget/atalho).
            startActivityAndCollapse(AppIntents.idea(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(Intent(this, IdeaActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
        }
    }
}
