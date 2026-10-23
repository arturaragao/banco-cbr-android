package io.github.arturaragao.bancocbr

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BancoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "refresh-saved-summary", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS).build()
        )
        RefreshCoordinator.enqueue(this)
    }
}

object RefreshCoordinator {
    private val executor = Executors.newSingleThreadExecutor()
    private val lock = Any()

    fun accept(context: Context, json: String) = enqueue(context, before = { SummaryStore.save(context, json) })

    fun enqueue(context: Context, before: () -> Unit = {}, finished: () -> Unit = {}) {
        val app = context.applicationContext
        executor.execute {
            try {
                synchronized(lock) {
                    before()
                    refreshLocked(app)
                }
            } catch (error: Exception) {
                Log.w("BancoCBR", "Não foi possível atualizar o resumo nativo", error)
            } finally {
                finished()
            }
        }
    }

    fun refresh(context: Context) = synchronized(lock) { refreshLocked(context) }

    private fun refreshLocked(context: Context) {
        val summary = SummaryStore.read(context)
        WidgetRenderer.updateAll(context, summary)
        Notifications.schedule(context, summary)
    }
}

class RefreshWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = try {
        RefreshCoordinator.refresh(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
