package io.github.arturaragao.bancocbr

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Regras puras (testadas): versionCode = número da execução do Actions = último número da tag (v1.1.7 -> 7). */
object UpdateVersion {
    fun codeFromTag(tag: String): Int? = tag.trim().substringAfterLast('.').toIntOrNull()
    fun isNewer(tag: String, current: Int): Boolean = (codeFromTag(tag) ?: 0) > current
}

/** Restringe o APK aos assets HTTPS deste repositório; redirects HTTPS ficam com o GitHub. */
internal object UpdateSource {
    fun isTrustedApkUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        val prefix = "/arturaragao/banco-cbr-android/releases/download/"
        val path = uri.rawPath.orEmpty()
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            path.startsWith(prefix) && path.removePrefix(prefix).split('/').let { parts ->
                parts.size == 2 && parts.all { it.isNotBlank() && it != "." && it != ".." && '%' !in it } &&
                    parts.last().endsWith(".apk")
            }
    }.getOrDefault(false)
}

/**
 * Consulta o GitHub ao abrir e periodicamente, em qualquer rede conectada.
 * Com permissão, baixa e instala por sessão; o Android decide se exige confirmação.
 */
internal object UpdateRetry {
    const val ATTEMPTS = 5
    /** Avisa só depois de várias rodadas seguidas sem conseguir (cada rodada já tenta 5 vezes). */
    const val NOTIFY_AFTER = 6
    fun isTransient(error: Throwable?): Boolean {
        var e = error
        while (e != null) { if (e is IOException) return true; e = e.cause }
        return false
    }
    fun shouldNotify(error: Throwable, failuresInARow: Int) = !isTransient(error) || failuresInARow >= NOTIFY_AFTER
    /** Resposta a um pedido com Range: true = continuar o arquivo, false = recomeçar, null = já estava completo. */
    fun appendFor(code: Int, asked: Boolean): Boolean? = when {
        code == 206 && asked -> true
        code == 200 -> false
        code == 416 && asked -> null
        else -> throw IOException("HTTP $code")
    }
}

object Updater {
    private const val API = "https://api.github.com/repos/arturaragao/banco-cbr-android/releases/latest"
    private const val PREFS = "update"
    private const val NOTIF_ID = 900
    const val EXTRA = "atualizar"
    internal const val STATUS_ACTION = "io.github.arturaragao.bancocbr.UPDATE_STATUS"
    private val executor = Executors.newSingleThreadExecutor()
    private val updateLock = Any()

    data class Release(val tag: String, val apkUrl: String)

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("check-update", ExistingPeriodicWorkPolicy.UPDATE, request)
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
        onPackageReplaced(activity)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Retoma a confirmação mesmo se o Android bloqueou a abertura/notificação em segundo plano.
        prefs.getString("confirmation", null)?.let { saved ->
            val id = prefs.getInt("sessionId", -1)
            if (id != -1 && activity.packageManager.packageInstaller.getSessionInfo(id) != null) {
                runCatching {
                    activity.startActivity(Intent.parseUri(saved, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { reportError(activity, it.message ?: "Não consegui abrir a confirmação") }
                return
            }
            prefs.edit().remove("confirmation").apply()
        }
        val resumeInstall = prefs.getBoolean("installPending", false) && canInstall(activity)
        val now = System.currentTimeMillis()
        if (!force && !resumeInstall && now - prefs.getLong("lastCheck", 0) < TimeUnit.MINUTES.toMillis(1)) return
        prefs.edit().putLong("lastCheck", now).apply()
        val context = activity.applicationContext
        executor.execute {
            runCatching {
                synchronized(updateLock) {
                    if (hasActiveSession(context)) return@synchronized
                    val staged = prefs.getString("stagedTag", null)
                    if (resumeInstall && staged != null && UpdateVersion.isNewer(staged, BuildConfig.VERSION_CODE) && apkFile(context).isFile) {
                        install(context, staged)
                        return@synchronized
                    }
                    val release = latest() ?: return@synchronized
                    if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE)) return@synchronized
                    if (canInstall(context)) downloadAndInstall(context, release)
                    else if (force) {
                        download(context, release)
                        prefs.edit().putBoolean("installPending", true).apply()
                        activity.runOnUiThread {
                            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                            Toast.makeText(activity, "Permita \"instalar apps desconhecidos\" para o Banco CBR e volte.", Toast.LENGTH_LONG).show()
                            runCatching {
                                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
                            }.onFailure { reportError(context, it.message ?: "Não consegui abrir a permissão de instalação") }
                        }
                    } else notifyAvailable(context, release)
                }
            }.onFailure { failed(context, it) }
        }
    }

    private fun apkFile(context: Context) = File(File(context.cacheDir, "update"), "banco-cbr.apk")

    private fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Baixa com retomada: se a conexão cai, continua de onde parou (até 5 tentativas nesta rodada). */
    private fun download(context: Context, release: Release) {
        check(UpdateSource.isTrustedApkUrl(release.apkUrl)) { "URL do APK não permitida" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val file = apkFile(context)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "baixando.apk")
        if (prefs.getString("partialTag", null) != release.tag) {
            tmp.delete()
            prefs.edit().putString("partialTag", release.tag).apply()
        }
        var attempt = 0
        while (true) {
            try { fetchInto(release.apkUrl, tmp); break }
            catch (e: IOException) {
                if (++attempt >= UpdateRetry.ATTEMPTS) throw e
                Thread.sleep(attempt * 3_000L)
            }
        }
        if (tmp.length() <= 100_000) { tmp.delete(); error("APK pequeno demais") }
        file.delete()
        check(tmp.renameTo(file)) { "Não consegui salvar o APK" }
        prefs.edit().putString("stagedTag", release.tag).remove("partialTag").apply()
    }

    private fun fetchInto(url: String, tmp: File) {
        val have = if (tmp.isFile) tmp.length() else 0L
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
        try {
            val append = UpdateRetry.appendFor(c.responseCode, have > 0) ?: return
            check(c.url.protocol == "https") { "Download sem HTTPS" }
            val expected = c.contentLengthLong.let { if (it > 0) it + (if (append) have else 0) else -1 }
            c.inputStream.use { input -> FileOutputStream(tmp, append).use { input.copyTo(it, 64 * 1024) } }
            if (expected > 0 && tmp.length() != expected) throw IOException("Download incompleto")
        } finally {
            c.disconnect()
        }
    }

    private fun downloadAndInstall(context: Context, release: Release) {
        download(context, release)
        if (canInstall(context)) install(context, release.tag)
        else notifyAvailable(context, release) // A permissão pode ter sido revogada durante o download.
    }

    @Suppress("DEPRECATION")
    private fun install(context: Context, tag: String) {
        val file = apkFile(context)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: run { file.delete(); error("APK inválido; baixo de novo na próxima vez") }
        check(archive.packageName == context.packageName) { "APK não pertence ao Banco CBR" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        check(code == UpdateVersion.codeFromTag(tag)?.toLong() && code > BuildConfig.VERSION_CODE) { "Versão do APK inválida" }
        // O PackageInstaller valida a assinatura contra a versão instalada, inclusive rotação de chave.
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        try {
            // Persistir antes de escrever/commitar permite recuperar sessões interrompidas.
            // Uma atualização de si mesmo encerra este processo.
            check(prefs.edit().putInt("sessionId", id).putString("sessionTag", tag)
                .putBoolean("installPending", false).commit()) { "Não consegui salvar a sessão" }
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val status = Intent(context, UpdateStatusReceiver::class.java).setAction(STATUS_ACTION)
                // O sistema precisa preencher EXTRA_STATUS/EXTRA_INTENT (imutável não funciona no target 35).
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(context, id, status, flags)
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            prefs.edit().remove("sessionId").remove("sessionTag").remove("confirmation").apply()
            throw e
        }
    }

    private fun hasActiveSession(context: Context): Boolean {
        onPackageReplaced(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt("sessionId", -1)
        if (id == -1) return false
        val session = context.packageManager.packageInstaller.getSessionInfo(id)
        if (session != null && session.isSealed) return true
        // Recupera uma sessão interrompida antes do commit, sem bloquear futuras tentativas.
        if (session != null) runCatching { context.packageManager.packageInstaller.abandonSession(id) }
        prefs.edit().remove("sessionId").remove("sessionTag").remove("confirmation").apply()
        return false
    }

    /** Segundo plano: baixa/instala com permissão; senão avisa uma vez por versão. */
    fun notifyIfNewer(context: Context) = synchronized(updateLock) {
        try {
            if (hasActiveSession(context)) return@synchronized
            val release = latest() ?: return@synchronized
            if (!UpdateVersion.isNewer(release.tag, BuildConfig.VERSION_CODE)) return@synchronized
            if (canInstall(context)) downloadAndInstall(context, release)
            else notifyAvailable(context, release)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("failures", 0).apply()
        } catch (e: Exception) {
            failed(context, e)
            throw e
        }
    }
    /** Baixa e instala pelo WorkManager: segue mesmo se o app sair da tela e espera a rede voltar. */
    fun downloadNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("update-now", ExistingWorkPolicy.KEEP, request)
    }

    /** Queda de rede não vira notificação: tenta de novo sozinho; só avisa se falhar muitas vezes seguidas. */
    private fun failed(context: Context, error: Throwable) {
        Log.w("BancoCBR", "Falha ao atualizar", error)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val failures = prefs.getInt("failures", 0) + 1
        prefs.edit().putInt("failures", failures).apply()
        if (UpdateRetry.isTransient(error)) downloadNow(context)
        if (UpdateRetry.shouldNotify(error, failures)) reportError(context,
            if (UpdateRetry.isTransient(error)) "Sem conseguir baixar a atualização (internet instável). Sigo tentando sozinho."
            else error.message ?: "Não consegui atualizar")
    }


    private fun notifyAvailable(context: Context, release: Release) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("notified", null) == release.tag) return
        val open = Intent(context, MainActivity::class.java).putExtra(EXTRA, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, NOTIF_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = "Versão ${release.tag.removePrefix("v")} disponível. Toque para atualizar."
        if (notify(context, "Atualização do Banco CBR", text, pending)) prefs.edit().putString("notified", release.tag).apply()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, NOTIF_ID + 1, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notify(context: Context, title: String, text: String, pending: PendingIntent): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return false
        Notifications.createChannel(context)
        val notification = Notification.Builder(context, "avisos")
            .setSmallIcon(R.drawable.ic_logo)
            .setColor(0xff60a5fa.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIF_ID, notification)
        return true
    }

    private fun reportError(context: Context, reason: String) {
        Log.w("BancoCBR", "Atualização: $reason")
        notify(context, "Falha ao atualizar Banco CBR", reason.take(180), openApp(context))
    }

    internal fun onPackageReplaced(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tag = prefs.getString("sessionTag", null) ?: return
        val code = UpdateVersion.codeFromTag(tag) ?: return
        if (BuildConfig.VERSION_CODE >= code) reportSuccess(context, tag)
    }

    private fun reportSuccess(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("sessionId").remove("sessionTag").remove("stagedTag").remove("notified").remove("confirmation")
            .putBoolean("installPending", false).commit()
        apkFile(context).delete()
        notify(context, "Banco CBR atualizado para ${tag.removePrefix("v")}", "Toque para abrir o app.", openApp(context))
    }

    @Suppress("DEPRECATION")
    internal fun onInstallStatus(context: Context, intent: Intent) {
        if (intent.action != STATUS_ACTION) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getInt("sessionId", -1)
        if (id == -1 || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation == null) {
                    runCatching { context.packageManager.packageInstaller.abandonSession(id) }
                    prefs.edit().remove("sessionId").remove("sessionTag").remove("notified").remove("confirmation").apply()
                    reportError(context, "O instalador não enviou a confirmação")
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                prefs.edit().putString("confirmation", confirmation.toUri(Intent.URI_INTENT_SCHEME)).apply()
                // Se o Android bloquear a abertura em segundo plano, o toque abre diretamente o instalador.
                val pending = PendingIntent.getActivity(context, id, confirmation, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notify(context, "Atualização do Banco CBR", "Toque para confirmar a instalação.", pending)
                runCatching { context.startActivity(confirmation) }
                    .onFailure {
                        Log.w("BancoCBR", "Confirme a instalação pela notificação", it)
                        notify(context, "Confirme a atualização do Banco CBR", (it.message ?: "Toque para abrir o instalador.").take(180), pending)
                    }
            }
            PackageInstaller.STATUS_SUCCESS -> reportSuccess(context, prefs.getString("sessionTag", null) ?: BuildConfig.VERSION_NAME)
            else -> {
                prefs.edit().remove("sessionId").remove("sessionTag").remove("notified").remove("confirmation").apply()
                reportError(context, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Instalação recusada pelo Android")
            }
        }
    }
}

class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.onPackageReplaced(context)
        else Updater.onInstallStatus(context, intent)
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
