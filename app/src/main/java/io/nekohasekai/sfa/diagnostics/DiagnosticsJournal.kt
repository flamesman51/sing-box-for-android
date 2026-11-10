package io.nekohasekai.sfa.diagnostics

import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class DiagnosticsDestination(val profileId: Long, val source: String, val url: String) {
    val owner: String get() = DiagnosticsStore.digest("$profileId\n$source\n$url")
}

object DiagnosticsJournal {
    private val writer = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(256),
        { runnable -> Thread(runnable, "diagnostics-journal").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    internal val store: DiagnosticsStore by lazy {
        DiagnosticsStore(File(Application.application.noBackupFilesDir, "diagnostics"))
    }

    internal fun flush() {
        writer.submit {}.get(30, TimeUnit.SECONDS)
    }

    internal suspend fun selectedDestination(): DiagnosticsDestination? {
        val id = Settings.selectedProfile
        val profile = ProfileManager.get(id) ?: return null
        if (profile.typed.type != TypedProfile.Type.Remote) return null
        val source = profile.typed.remoteURL
        val destination = ReportCapabilities.destination(id, source) ?: return null
        val uri = runCatching { URI(destination) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (Settings.selectedProfile != id) return null
        return DiagnosticsDestination(id, source, destination)
    }

    private fun captureProfileId(): Long? = try {
        val ready = Application.application.coreReady.let { it.isCompleted && !it.isCancelled }
        if (ready) Settings.selectedProfile else null
    } catch (_: Throwable) {
        null
    }

    fun record(kind: String, detail: Map<String, Any?> = emptyMap(), profileId: Long? = captureProfileId()) {
        recordEvent(kind, detail, profileId, null)
    }

    fun record(kind: String, detail: Map<String, Any?>, profileId: Long?, profileSource: String?) {
        recordEvent(kind, detail, profileId.takeIf { profileSource != null }, profileSource)
    }

    private fun recordEvent(kind: String, detail: Map<String, Any?>, profileId: Long?, profileSource: String?) {
        try {
            val event = DiagnosticsProjection.event(kind, detail)
            writer.execute {
                try {
                    val owner = if (profileId != null) {
                        runBlocking {
                            selectedDestination()?.takeIf {
                                it.profileId == profileId && (profileSource == null || it.source == profileSource)
                            }?.owner
                        }
                    } else {
                        null
                    }
                    store.record(owner, event)
                    Log.i("DiagnosticsJournal", event.toString())
                } catch (_: Throwable) {
                    // Diagnostics must never affect the tunnel or its crash handler.
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun reportFound(kind: String, id: String) {
        if (kind !in setOf("crash_report", "oom_report", "power_report")) return
        try {
            val event = DiagnosticsProjection.event(kind, emptyMap())
            val profileId = captureProfileId()
            writer.execute {
                try {
                    val owner = runBlocking {
                        Application.application.coreReady.await()
                        selectedDestination()?.takeIf { it.profileId == profileId }?.owner
                    }
                    if (store.discover(owner, kind, id)) {
                        store.record(owner, event)
                        Log.i("DiagnosticsJournal", event.toString())
                        if (kind != "power_report") DiagnosticsWork.enqueue(Application.application)
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
