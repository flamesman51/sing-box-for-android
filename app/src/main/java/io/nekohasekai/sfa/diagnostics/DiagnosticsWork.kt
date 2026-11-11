package io.nekohasekai.sfa.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.bg.BoxService
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

object DiagnosticsWork {
    private const val PERIODIC = "DiagnosticsPeriodic"
    private const val IMMEDIATE = "DiagnosticsImmediate"
    private val scheduling = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleLock = Any()
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        scheduling.launch {
            try {
                Settings.dataStore.initialize()
                synchronized(scheduleLock) {
                    val manager = WorkManager.getInstance(context)
                    if (!Settings.sendDiagnostics) {
                        manager.cancelUniqueWork(PERIODIC)
                        manager.cancelUniqueWork(IMMEDIATE)
                    } else {
                        manager.enqueueUniquePeriodicWork(
                            PERIODIC,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            PeriodicWorkRequest.Builder(Upload::class.java, 6, TimeUnit.HOURS)
                                .setConstraints(connected)
                                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                                .build(),
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun enqueue(context: Context) {
        scheduling.launch {
            try {
                Settings.dataStore.initialize()
                synchronized(scheduleLock) {
                    if (!Settings.sendDiagnostics) return@synchronized
                    val request = OneTimeWorkRequest.Builder(Upload::class.java)
                        .setConstraints(connected)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                        // Startup triggers run before the command server; wait so the report carries smart status.
                        .setInitialDelay(1, TimeUnit.MINUTES)
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, request)
                }
            } catch (_: Exception) {
            }
        }
    }

    class Upload(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
        companion object {
            private val uploadLock = Mutex()
        }

        override suspend fun doWork(): Result = uploadLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val coreReady = try {
                        Application.application.coreReady.await()
                        true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                    DiagnosticsJournal.flush()
                    if (!Settings.sendDiagnostics) return@withContext Result.success()
                    val destination = DiagnosticsJournal.selectedDestination() ?: run {
                        Log.i("DiagnosticsReport", "No report capability for selected profile")
                        return@withContext Result.success()
                    }
                    val store = DiagnosticsJournal.store
                    val unsent = DiagnosticsReportDiscovery.shared.unsent(store, destination.owner)
                    var batch = store.pending(destination.owner)
                    if (batch == null) {
                        val app = DiagnosticsProjection.app(
                            buildJsonObject {
                                put("version_name", BuildConfig.VERSION_NAME)
                                put("version_code", BuildConfig.VERSION_CODE)
                                put("core_version", if (coreReady) Libbox.version() else "unknown")
                                put("android_sdk", Build.VERSION.SDK_INT)
                                put("device_model", Build.MODEL)
                            },
                        )
                        val smart = if (coreReady && BoxService.activeDiagnosticsProfileId == destination.profileId &&
                            BoxService.activeDiagnosticsProfileSource == destination.source
                        ) {
                            DiagnosticsSnapshot.smart().takeIf {
                                BoxService.activeDiagnosticsProfileId == destination.profileId &&
                                    BoxService.activeDiagnosticsProfileSource == destination.source
                            }
                        } else {
                            null
                        }
                        val crashes = DiagnosticsSnapshot.crashes(unsent)
                        val runtime = DiagnosticsSnapshot.runtime {
                            coreReady && BoxService.activeDiagnosticsProfileId == destination.profileId &&
                                BoxService.activeDiagnosticsProfileSource == destination.source
                        }
                        batch = store.prepare(destination.owner, app, smart, crashes, association = destination.association, runtime = { runtime })
                    }
                    repeat(8) {
                        val pending = batch ?: return@withContext Result.success()
                        currentCoroutineContext().ensureActive()
                        if (!Settings.sendDiagnostics || DiagnosticsJournal.selectedDestination() != destination) return@withContext Result.success()
                        val body = pending.body.toByteArray(Charsets.UTF_8)
                        val status = post(destination.url, body)
                        Log.i("DiagnosticsReport", "report_id=${pending.id} bytes=${body.size} status=$status")
                        currentCoroutineContext().ensureActive()
                        when (store.respond(destination.owner, pending.id, status)) {
                            DiagnosticsStore.Outcome.RETRY -> return@withContext Result.retry()
                            DiagnosticsStore.Outcome.ACKNOWLEDGED, DiagnosticsStore.Outcome.DISCARDED, DiagnosticsStore.Outcome.SPLIT -> {
                                batch = store.pending(destination.owner)
                            }
                        }
                    }
                    if (batch == null) Result.success() else Result.retry()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("DiagnosticsReport", "Upload failed: ${error.javaClass.simpleName}")
                    // Neither transport exception text nor the request URL may enter logs or the journal.
                    Result.retry()
                }
            }
        }
    }

    private fun post(destination: String, body: ByteArray): Int {
        require(body.size <= DiagnosticsStore.MAX_BODY_BYTES)
        val url = URL(destination)
        require(url.protocol.equals("https", true) && url.userInfo == null && url.ref == null)
        val connection = url.openConnection() as HttpsURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
