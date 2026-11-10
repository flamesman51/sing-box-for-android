package io.nekohasekai.sfa.diagnostics

import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun diagnosticsAssociation(profileId: Long, source: String?): String = DiagnosticsStore.digest("$profileId\n${source?.let { "remote\n$it" } ?: "local"}")

internal data class DiagnosticsDestination(val profileId: Long, val source: String, val url: String) {
    val owner: String get() = DiagnosticsStore.digest("$profileId\n$source\n$url")
    val association: String = diagnosticsAssociation(profileId, source)
}

internal data class DiagnosticsProfile(val id: Long, val source: String?, val resolveSource: (() -> String?)? = null) {
    val association: String = diagnosticsAssociation(id, source)
    val localOwner: String get() = "local:$association"
}

internal class DiagnosticsSources(private val access: Any, private val read: (Long) -> String?) {
    private inner class Snapshot(private val id: Long) {
        private var resolved = false
        private var source: String? = null

        fun resolve(): String? = synchronized(access) {
            if (!resolved) {
                source = runCatching { read(id) }.getOrNull()
                resolved = true
            }
            source
        }

        fun missing() = synchronized(access) {
            source = null
            resolved = true
        }
    }

    private val snapshots = ConcurrentHashMap<Long, Snapshot>()

    fun capture(id: Long): () -> String? = snapshots.getOrPut(id) { Snapshot(id) }::resolve

    // Source mutations hold the same lock, preserving the old source before replacing its token.
    fun changing(id: Long) = synchronized(access) {
        snapshots.remove(id)?.resolve()
        Unit
    }

    fun created(id: Long) = synchronized(access) {
        snapshots.remove(id)?.missing()
        Unit
    }
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
    private var scheduled = false

    fun preferencesReady() = synchronized(lock) {
        ready = true
        deferred.forEach {
            if (!it.associated) {
                it.profile = captureProfile()
                it.associated = true
            }
        }
        dispatch()
    }

    fun retry() = synchronized(lock) { dispatch() }

    fun hasPending(): Boolean = synchronized(lock) { deferred.isNotEmpty() }

    fun record(kind: String, detail: Map<String, Any?>, profile: DiagnosticsProfile? = null) {
        val event = DiagnosticsProjection.event(kind, detail)
        synchronized(lock) {
            defer(Deferred(event, profile ?: if (ready) captureProfile() else null, associated = profile != null || ready))
        }
    }

    fun reportFound(kind: String, id: String) {
        if (kind !in setOf("crash_report", "oom_report", "power_report")) return
        val event = DiagnosticsProjection.event(kind, emptyMap())
        synchronized(lock) {
            val profile = if (ready) captureProfile() else null
            if (deferred.none { it.reportKind == kind && it.reportId == id }) {
                defer(Deferred(event, profile, associated = ready, reportKind = kind, reportId = id))
            } else {
                dispatch()
            }
        }
    }

    private fun captureProfile(): DiagnosticsProfile? = runCatching(selectedProfile).getOrNull()

    private fun defer(entry: Deferred) {
        if (deferred.size == 1000) deferred.removeFirst()
        deferred.addLast(entry)
        dispatch()
    }

    private fun dispatch() {
        if (!ready || scheduled || deferred.isEmpty()) return
        scheduled = true
        try {
            writer.execute(::drain)
        } catch (_: Throwable) {
            scheduled = false
        }
    }

    private fun drain() {
        while (true) {
            val entry = synchronized(lock) {
                deferred.firstOrNull().also { if (it == null) scheduled = false }
            } ?: return
            try {
                persist(entry)
            } catch (_: Throwable) {
                synchronized(lock) { scheduled = false }
                return
            }
            synchronized(lock) { if (deferred.firstOrNull() === entry) deferred.removeFirst() }
        }
    }

    private fun persist(entry: Deferred) {
        val captured = entry.profile ?: DiagnosticsProfile(-1L, null)
        val profile = captured.resolveSource?.let { DiagnosticsProfile(captured.id, it()) } ?: captured
        entry.profile = profile
        val target = runCatching { destination(profile) }.getOrNull()?.takeIf {
            profile.source != null && it.profileId == profile.id && it.source == profile.source
        }
        val owner = target?.owner ?: profile.localOwner
        val storage = store()
        if (entry.reportKind == null) {
            storage.record(owner, entry.event)
            runCatching { recorded(entry.event) }
        } else {
            val observation = storage.observe(profile.association, owner, entry.reportKind, requireNotNull(entry.reportId), entry.event)
            if (observation == DiagnosticsStore.Observation.RECORDED) runCatching { recorded(entry.event) }
            if (observation != DiagnosticsStore.Observation.OTHER_ASSOCIATION && target != null &&
                storage.discover(owner, entry.reportKind, entry.reportId)
            ) {
                runCatching { discovered(entry.reportKind) }
            }
        }
    }
}

object DiagnosticsJournal {
    private val writer = ScheduledThreadPoolExecutor(
        1,
        { runnable -> Thread(runnable, "diagnostics-journal").apply { isDaemon = true } },
    )

    internal val store: DiagnosticsStore by lazy {
        DiagnosticsStore(File(Application.application.noBackupFilesDir, "diagnostics"))
    }
    private val ownership = DiagnosticsOwnership(
        { store },
        writer,
        { captureProfile(Settings.selectedProfile) },
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

    init {
        runCatching { writer.scheduleWithFixedDelay({ ownership.retry() }, 1, 1, TimeUnit.SECONDS) }
    }

    private fun captureProfile(id: Long): DiagnosticsProfile = DiagnosticsProfile(id, null, ProfileManager.captureDiagnosticsSource(id))

    internal fun preferencesReady() {
        try {
            ownership.preferencesReady()
        } catch (_: Throwable) {
        }
    }

    internal fun flush() {
        ownership.retry()
        writer.submit {}.get(30, TimeUnit.SECONDS)
        check(!ownership.hasPending()) { "Diagnostics journal writes remain pending" }
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
            val profile = profileId?.let(::captureProfile)
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
