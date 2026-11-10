package io.nekohasekai.sfa.diagnostics

import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun diagnosticsAssociation(profileId: Long, source: String?): String = DiagnosticsStore.digest("$profileId\n${source?.let { "remote\n$it" } ?: "local"}")

internal data class DiagnosticsDestination(val profileId: Long, val source: String, val url: String) {
    val owner: String get() = DiagnosticsStore.digest("$profileId\n$source\n$url")
    val association: String = diagnosticsAssociation(profileId, source)
}

internal data class DiagnosticsProfile(val id: Long, val source: String?) {
    val association: String = diagnosticsAssociation(id, source)
    val localOwner: String get() = "local:$association"
}

internal class DiagnosticsOwnership(
    private val store: () -> DiagnosticsStore,
    private val writer: Executor,
    private val selectedProfile: () -> DiagnosticsProfile?,
    private val destination: (DiagnosticsProfile) -> DiagnosticsDestination?,
    private val discovered: (String) -> Unit = {},
    private val recorded: (JsonObject) -> Unit = {},
) {
    private data class Deferred(
        val event: JsonObject,
        var profile: DiagnosticsProfile?,
        var associated: Boolean = profile != null,
        val reportKind: String? = null,
        val reportId: String? = null,
    )

    private val lock = Any()
    private val deferred = ArrayDeque<Deferred>()
    private var ready = false

    fun preferencesReady() = synchronized(lock) {
        ready = true
        drain()
    }

    fun retry() = synchronized(lock) { drain() }

    fun record(kind: String, detail: Map<String, Any?>, profile: DiagnosticsProfile? = null) {
        val event = DiagnosticsProjection.event(kind, detail)
        synchronized(lock) {
            defer(Deferred(event, profile ?: if (ready) selectedProfile() else null, associated = profile != null || ready))
        }
    }

    fun reportFound(kind: String, id: String) {
        if (kind !in setOf("crash_report", "oom_report", "power_report")) return
        val event = DiagnosticsProjection.event(kind, emptyMap())
        synchronized(lock) {
            val profile = if (ready) selectedProfile() else null
            if (deferred.none { it.reportKind == kind && it.reportId == id }) {
                defer(Deferred(event, profile, associated = ready, reportKind = kind, reportId = id))
            } else {
                drain()
            }
        }
    }

    private fun defer(entry: Deferred) {
        if (!ready && deferred.size == 256) deferred.removeFirst()
        deferred.addLast(entry)
        drain()
    }

    private fun drain() {
        if (!ready) return
        val entries = deferred.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (!entry.associated) {
                entry.profile = selectedProfile()
                entry.associated = true
            }
            val profile = entry.profile ?: DiagnosticsProfile(-1L, null)
            val target = destination(profile)?.takeIf {
                profile.source != null && it.profileId == profile.id && it.source == profile.source
            }
            val owner = target?.owner ?: profile.localOwner
            val write = Runnable {
                try {
                    val storage = store()
                    if (entry.reportKind == null) {
                        storage.record(owner, entry.event)
                        recorded(entry.event)
                    } else {
                        val observation = storage.observe(profile.association, owner, entry.reportKind, requireNotNull(entry.reportId), entry.event)
                        if (observation == DiagnosticsStore.Observation.RECORDED) recorded(entry.event)
                        if (observation != DiagnosticsStore.Observation.OTHER_ASSOCIATION && target != null &&
                            storage.discover(owner, entry.reportKind, entry.reportId)
                        ) {
                            discovered(entry.reportKind)
                        }
                    }
                } catch (_: Throwable) {
                    // Diagnostics must never affect the tunnel or its crash handler.
                }
            }
            try {
                writer.execute(write)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // A saturated writer must not turn ready events into a volatile backlog.
                write.run()
            }
            entries.remove()
        }
    }
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
    private val ownership = DiagnosticsOwnership(
        { store },
        writer,
        {
            runBlocking(Dispatchers.IO) {
                val id = Settings.selectedProfile
                val profile = ProfileManager.get(id)
                DiagnosticsProfile(id, profile?.typed?.takeIf { it.type == TypedProfile.Type.Remote }?.remoteURL)
            }
        },
        { profile ->
            profile.source?.let { source ->
                ReportCapabilities.destination(profile.id, source)?.takeIf(::validDestination)?.let {
                    DiagnosticsDestination(profile.id, source, it)
                }
            }
        },
        { kind -> if (kind != "power_report") DiagnosticsWork.enqueue(Application.application) },
        { event -> Log.i("DiagnosticsJournal", event.toString()) },
    )

    internal fun preferencesReady() {
        try {
            ownership.preferencesReady()
        } catch (_: Throwable) {
        }
    }

    internal fun flush() {
        ownership.retry()
        writer.submit {}.get(30, TimeUnit.SECONDS)
    }

    internal suspend fun selectedDestination(): DiagnosticsDestination? {
        val id = Settings.selectedProfile
        val profile = ProfileManager.get(id) ?: return null
        if (profile.typed.type != TypedProfile.Type.Remote) return null
        val source = profile.typed.remoteURL
        val destination = ReportCapabilities.destination(id, source) ?: return null
        if (!validDestination(destination)) return null
        if (Settings.selectedProfile != id) return null
        return DiagnosticsDestination(id, source, destination)
    }

    private fun validDestination(destination: String): Boolean {
        val uri = runCatching { URI(destination) }.getOrNull() ?: return false
        return uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null
    }

    fun record(kind: String, detail: Map<String, Any?> = emptyMap(), profileId: Long? = null) {
        try {
            val profile = profileId?.let { id ->
                runBlocking(Dispatchers.IO) {
                    val profile = ProfileManager.get(id)
                    DiagnosticsProfile(id, profile?.typed?.takeIf { it.type == TypedProfile.Type.Remote }?.remoteURL)
                }
            }
            ownership.record(kind, detail, profile)
        } catch (_: Throwable) {
        }
    }

    fun record(kind: String, detail: Map<String, Any?>, profileId: Long?, profileSource: String?) {
        try {
            ownership.record(kind, detail, DiagnosticsProfile(profileId ?: -1L, profileSource))
        } catch (_: Throwable) {
        }
    }

    fun reportFound(kind: String, id: String) {
        try {
            ownership.reportFound(kind, id)
        } catch (_: Throwable) {
        }
    }
}
