package io.github.arturaragao.bancocbr

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regras puras (testadas): versionCode = número da execução do Actions = último número da tag (v1.1.7 -> 7). */
object UpdateVersion {
    fun codeFromTag(tag: String): Int? = tag.trim().substringAfterLast('.').toIntOrNull()
    fun isNewer(tag: String, current: Int): Boolean = (codeFromTag(tag) ?: 0) > current
}

/**
 * Atualização pelo GitHub Releases, sem Play Store e sem custo (pedido do Artur, 24/10/2026).
 * Ao abrir o app (no máximo a cada 6 h) e de 12 em 12 h em segundo plano consulta a última versão; se houver uma nova,
 * pergunta "Atualizar?" (ou avisa por notificação), baixa o APK e abre a tela de instalação do Android (1 toque).
 * O Android não deixa app fora da Play Store se instalar sem esse toque.
 */
object Updater {
    private const val API = "https://api.github.com/repos/arturaragao/banco-cbr-android/releases/latest"
    private const val PREFS = "update"
    private const val NOTIF_ID = 900
    const val EXTRA = "atualizar"
    private val executor = Executors.newSingleThreadExecutor()

    data class Release(val tag: String, val apkUrl: String)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("check-update", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun latest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) return null
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) return Release(tag, a.getString("browser_download_url"))
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** Chamado no onResume. force = veio da notificação de atualização. */
    fun checkOnOpen(activity: Activity, force: Boolean) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("installPending", false) && apkFile(activity).isFile && canInstall(activity)) {
            prefs.edit().putBoolean("installPending", false).apply()
            install(activity)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < TimeUnit.HOURS.toMillis(6)) return
        prefs.edit().putLong("lastCheck", now).apply()
        executor.execute {
            val release = runCatching { latest() }.onFailure { Log.w("BancoCBR", "Falha ao procurar atualização", it) }.getOrNull()
                ?: return@execute
            if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE)) return@execute
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                AlertDialog.Builder(activity)
                    .setTitle("Atualização disponível")
                    .setMessage("Banco CBR ${release.tag.removePrefix("v")} (você tem ${BuildConfig.VERSION_NAME}). Atualizar agora?")
                    .setPositiveButton("Atualizar") { _, _ -> download(activity, release) }
                    .setNegativeButton("Depois", null)
                    .show()
            }
        }
    }

    private fun apkFile(context: Context) = File(File(context.cacheDir, "update"), "banco-cbr.apk")

    private fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    private fun download(activity: Activity, release: Release) {
        Toast.makeText(activity, "Baixando a atualização…", Toast.LENGTH_SHORT).show()
        executor.execute {
            val ok = runCatching {
                val file = apkFile(activity)
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "baixando.apk")
                val c = URL(release.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 60_000
                try {
                    check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                } finally {
                    c.disconnect()
                }
                check(tmp.length() > 100_000) { "APK pequeno demais" }
                file.delete()
                check(tmp.renameTo(file)) { "não renomeou" }
            }.onFailure { Log.w("BancoCBR", "Falha ao baixar atualização", it) }.isSuccess
            activity.runOnUiThread {
                if (!ok) {
                    Toast.makeText(activity, "Não consegui baixar a atualização. Tento de novo mais tarde.", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (!canInstall(activity)) {
                    // 1ª vez: o Android pede para permitir instalar apps vindos do Banco CBR. Depois volta e instala sozinho.
                    activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("installPending", true).apply()
                    Toast.makeText(activity, "Permita \"instalar apps desconhecidos\" para o Banco CBR e volte.", Toast.LENGTH_LONG).show()
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
                    }
                } else install(activity)
            }
        }
    }

    private fun install(activity: Activity) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".updates", apkFile(activity))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
            .onFailure { Toast.makeText(activity, "Não consegui abrir o instalador.", Toast.LENGTH_LONG).show() }
    }

    /** Segundo plano: só avisa (1 notificação por versão). */
    fun notifyIfNewer(context: Context) {
        val release = latest() ?: return
        if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("notified", null) == release.tag) return
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        Notifications.createChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        prefs.edit().putString("notified", release.tag).apply()
        val open = Intent(context, MainActivity::class.java).putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "Versão ${release.tag.removePrefix("v")} disponível. Toque para atualizar."
        val notification = Notification.Builder(context, "avisos")
            .setSmallIcon(R.drawable.ic_logo)
            .setColor(0xff60a5fa.toInt())
            .setContentTitle("Atualização do Banco CBR")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIF_ID, notification)
    }
}

class UpdateWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        Updater.notifyIfNewer(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
