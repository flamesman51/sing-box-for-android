package io.nekohasekai.sfa.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.diagnostics.DiagnosticsJournal
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

object ServerUpdateWork {
    private const val PERIODIC = "AutoUpdate"
    private const val CHECK = "ServerUpdateDiscovery"
    private const val AUTOMATIC_DOWNLOAD = "ServerUpdateAutomaticDownload"
    internal const val VERSION = "version"
    internal const val SHA256 = "sha256"
    internal const val MANUAL = "manual"

    /** KEEP preserves both the user's opt-out and an existing periodic deadline. */
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!Settings.autoUpdateEnabled) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(CHECK)
            manager.cancelAllWorkByTag(AUTOMATIC_DOWNLOAD)
            return
        }
        val request = PeriodicWorkRequestBuilder<ServerUpdateCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(connected())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        if (UpdateSource.fromString(Settings.updateSource) == UpdateSource.SERVER) {
            enqueueDownload(context)
        } else {
            manager.cancelUniqueWork(CHECK)
            manager.cancelAllWorkByTag(AUTOMATIC_DOWNLOAD)
        }
    }

    fun enqueueCheck(context: Context) {
        if (!automaticServerEnabled()) return
        val request = OneTimeWorkRequestBuilder<ServerUpdateCheckWorker>()
            .setConstraints(connected())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(CHECK, ExistingWorkPolicy.REPLACE, request)
    }

    fun enqueueDownload(context: Context, downloadNow: Boolean = false) {
        if (UpdateSource.fromString(Settings.updateSource) != UpdateSource.SERVER || (!downloadNow && !Settings.autoUpdateEnabled)) return
        ServerUpdates.initialize()
        val state = ServerUpdates.state.value
        val pending = state.pending ?: return
        if (state.ready?.versionCode == pending.versionCode && state.ready.sha256 == pending.artifact.sha256) return
        val nowMillis = System.currentTimeMillis()
        val identity = "${pending.versionCode}-${pending.artifact.sha256}"
        val manager = WorkManager.getInstance(context)
        val input = workDataOf(VERSION to pending.versionCode, SHA256 to pending.artifact.sha256, MANUAL to downloadNow)
        val immediate = OneTimeWorkRequestBuilder<ServerUpdateDownloadWorker>()
            .setInputData(input)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(
                    if (ServerUpdatePolicy.requiresUnmetered(pending.firstSeenAtMillis, nowMillis, downloadNow)) NetworkType.UNMETERED else NetworkType.CONNECTED,
                ).build(),
            )
        if (!downloadNow) immediate.addTag(AUTOMATIC_DOWNLOAD)
        manager.enqueueUniqueWork("ServerUpdateDownload-$identity-${if (downloadNow) "manual" else "automatic"}", ExistingWorkPolicy.KEEP, immediate.build())
        if (!downloadNow && ServerUpdatePolicy.requiresUnmetered(pending.firstSeenAtMillis, nowMillis)) {
            // This independent work becomes eligible even if every later manifest fetch fails.
            val fallback = OneTimeWorkRequestBuilder<ServerUpdateDownloadWorker>()
                .setInputData(input)
                .setConstraints(connected())
                .setInitialDelay(ServerUpdatePolicy.fallbackDelayMillis(pending.firstSeenAtMillis, nowMillis), TimeUnit.MILLISECONDS)
                .addTag(AUTOMATIC_DOWNLOAD)
                .build()
            manager.enqueueUniqueWork("ServerUpdateFallback-$identity", ExistingWorkPolicy.KEEP, fallback)
        }
    }

    internal suspend fun runCheck(context: Context): ListenableWorker.Result {
        ServerUpdates.initialize()
        ServerUpdateNotifier.refresh(context)
        return try {
            if (!Settings.autoUpdateEnabled) return ListenableWorker.Result.success()
            val info = runInterruptible(Dispatchers.IO) {
                if (UpdateSource.fromString(Settings.updateSource) == UpdateSource.SERVER) ServerUpdateChecker.check(context) else Vendor.checkUpdateAsync()
            }
            if (info != null) UpdateState.setUpdate(info)
            ListenableWorker.Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: Exception) {
            DiagnosticsJournal.record("update_failed", mapOf("stage" to "check", "message" to error.message))
            Log.w("ServerUpdates", "Background update check failed")
            ListenableWorker.Result.retry()
        } finally {
            enqueueDownload(context)
            ServerUpdateNotifier.refresh(context)
        }
    }

    private fun connected(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    internal fun automaticServerEnabled(): Boolean = Settings.autoUpdateEnabled && UpdateSource.fromString(Settings.updateSource) == UpdateSource.SERVER
}

class ServerUpdateCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channel = "${applicationContext.packageName}.server_update_checks"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(channel, applicationContext.getString(R.string.check_update), NotificationManager.IMPORTANCE_LOW))
        }
        return ForegroundInfo(
            40772,
            NotificationCompat.Builder(applicationContext, channel)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle(applicationContext.getString(R.string.app_name))
                .setContentText(applicationContext.getString(R.string.check_update))
                .setOngoing(true)
                .build(),
        )
    }

    override suspend fun doWork(): Result = ServerUpdateWork.runCheck(applicationContext)
}

class ServerUpdateDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        ServerUpdates.initialize()
        ServerUpdateNotifier.refresh(applicationContext)
        return try {
            val manual = inputData.getBoolean(ServerUpdateWork.MANUAL, false)
            if (UpdateSource.fromString(Settings.updateSource) != UpdateSource.SERVER || (!manual && !Settings.autoUpdateEnabled)) return Result.success()
            val pending = ServerUpdates.state.value.pending ?: return Result.success()
            if (inputData.getInt(ServerUpdateWork.VERSION, -1) != pending.versionCode || inputData.getString(ServerUpdateWork.SHA256) != pending.artifact.sha256) return Result.success()
            ServerUpdateChecker.download(applicationContext)
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (error: Exception) {
            DiagnosticsJournal.record("update_failed", mapOf("stage" to "download", "message" to error.message))
            Log.w("ServerUpdates", "Background update download failed")
            Result.retry()
        } finally {
            ServerUpdateNotifier.refresh(applicationContext)
        }
    }
}
