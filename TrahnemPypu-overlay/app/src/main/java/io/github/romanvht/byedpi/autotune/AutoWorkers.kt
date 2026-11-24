package io.github.romanvht.byedpi.autotune

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.NativeEngine
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.TestService
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.mode
import io.github.romanvht.byedpi.utility.registerNotificationChannel
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.concurrent.TimeUnit

object AutoScheduler {
    private const val HEALTH = "auto_health"
    private const val TUNE = "auto_tune"

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<HealthWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HEALTH, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(HEALTH)
    }

    fun tuneNow(context: Context, full: Boolean) {
        val req = OneTimeWorkRequestBuilder<TuneWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(workDataOf("full" to full))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(TUNE, ExistingWorkPolicy.KEEP, req)
    }
}

/** Каждые ~15 минут: обновляет списки, проверяет здоровье обхода, при необходимости запускает подбор. */
class HealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.getPreferences()
        if (!prefs.getBoolean(AutoKeys.ENABLED, true)) return Result.success()
        if (TestService.isRunning || AutoTuner.isBusy) return Result.success()

        val now = System.currentTimeMillis()
        var listChanged = false
        if (now - prefs.getLong(AutoKeys.LAST_FETCH, 0L) > 3 * 3600_000L) {
            listChanged = StrategySources.refresh(ctx) == true
        }

        if (NativeEngine.running(ctx) == null) {
            if (prefs.getBoolean(AutoKeys.KEEP_ON, true)) {
                val mode = prefs.mode()
                if (mode != Mode.VPN || VpnService.prepare(ctx) == null) {
                    try { ServiceManager.start(ctx, mode) } catch (_: Exception) { }
                }
            }
            return Result.success()
        }

        if (!HealthCheck.hasInternet()) return Result.success()
        if (now < prefs.getLong(AutoKeys.NEXT_TRY, 0L)) return Result.success()

        var healthy = try { HealthCheck.ok(ctx) } catch (_: Exception) { true }
        if (!healthy) {
            delay(20_000)
            healthy = try { HealthCheck.ok(ctx) } catch (_: Exception) { true }
        }

        val lastTune = prefs.getLong(AutoKeys.LAST_TUNE, 0L)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val night = hour in 3..6
        val day = 24 * 3600_000L
        when {
            !healthy -> AutoScheduler.tuneNow(ctx, full = false)
            now - lastTune > day && (night || now - lastTune > 2 * day) -> AutoScheduler.tuneNow(ctx, full = true)
            listChanged && night -> AutoScheduler.tuneNow(ctx, full = false)
        }
        return Result.success()
    }
}

class TuneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutoTuner.run(applicationContext, inputData.getBoolean("full", false))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val ctx = applicationContext
        registerNotificationChannel(ctx, "AutoTune", R.string.title_test)
        val n: Notification = NotificationCompat.Builder(ctx, "AutoTune")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Подбор стратегии обхода")
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(7, n)
        }
    }
}
