package io.nekohasekai.sfa.update

import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Authoritative server state. Settings must be initialized before any state operation. */
object ServerUpdates {
    private val lock = Any()
    private val mutableState = MutableStateFlow(ServerUpdateSnapshot())
    val state: StateFlow<ServerUpdateSnapshot> = mutableState.asStateFlow()
    private var initialized = false
    private var loggedForeignOrigin = false
    private val stateFile by lazy { AtomicFile(Settings.serverUpdateStateFile) }

    fun initialize() = synchronized(lock) {
        if (!initialized) {
            val stored = try {
                stateFile.openRead().use { Json.decodeFromString<ServerUpdateSnapshot>(it.readBytes().toString(Charsets.UTF_8)) }
            } catch (_: FileNotFoundException) {
                if (stateFile.baseFile.exists()) throw IOException("Cannot read server update state")
                ServerUpdateSnapshot()
            } catch (_: Exception) {
                throw IOException("Cannot read server update state")
            }
            mutableState.value = stored
            initialized = true
        }
    }

    fun reconcileInstalled() {
        synchronized(lock) {
            initialize()
            persist(ServerUpdatePolicy.reconcile(mutableState.value, ServerUpdateChecker.installedVersion(Application.application)))
        }
        ServerUpdateChecker.deleteInstalledArtifacts(Application.application)
    }

    fun discover(profileUrl: String, header: String): Boolean {
        val changed = synchronized(lock) {
            initialize()
            val current = mutableState.value
            val discovery = ServerUpdatePolicy.discover(current.origin, profileUrl, header)
            if (discovery == null) {
                val incomingOrigin = ServerUpdatePolicy.origin(profileUrl)
                if (current.origin != null && incomingOrigin != null && current.origin != incomingOrigin && !loggedForeignOrigin) {
                    Log.w("ServerUpdates", "Ignored update discovery from another origin")
                    loggedForeignOrigin = true
                }
                return false
            }
            val next = current.copy(origin = discovery.origin, manifestUrl = discovery.manifestUrl)
            persist(next)
            current.origin != next.origin || current.manifestUrl != next.manifestUrl
        }
        if (changed) ServerUpdateWork.enqueueCheck(Application.application)
        return true
    }

    fun forgetServer() = synchronized(lock) {
        initialize()
        persist(ServerUpdatePolicy.forget(mutableState.value))
        loggedForeignOrigin = false
    }

    fun markReminderShown(nowMillis: Long) = synchronized(lock) {
        initialize()
        if (mutableState.value.pending != null) persist(mutableState.value.copy(lastReminderAtMillis = nowMillis))
    }

    internal fun accept(candidate: PendingServerUpdate, manifestUrl: String, installedVersion: Long) = synchronized(lock) {
        initialize()
        if (mutableState.value.manifestUrl == manifestUrl) persist(ServerUpdatePolicy.accept(mutableState.value, candidate, installedVersion))
    }

    internal fun publishReady(pending: PendingServerUpdate, path: String): Boolean = synchronized(lock) {
        val current = mutableState.value
        if (current.pending?.versionCode != pending.versionCode || current.pending.artifact.sha256 != pending.artifact.sha256) {
            false
        } else {
            persist(current.copy(ready = ReadyServerUpdate(pending.versionCode, pending.artifact.sha256, path)))
            true
        }
    }

    internal fun discardReady(ready: ReadyServerUpdate) = synchronized(lock) {
        if (mutableState.value.ready == ready) persist(mutableState.value.copy(ready = null))
    }

    private fun persist(next: ServerUpdateSnapshot) {
        if (mutableState.value == next) return
        val bytes = Json.encodeToString(next).toByteArray(Charsets.UTF_8)
        val output = try {
            stateFile.startWrite()
        } catch (_: Exception) {
            throw IOException("Cannot persist server update state")
        }
        try {
            output.write(bytes)
            output.fd.sync()
            stateFile.finishWrite(output)
            // AtomicFile can log a failed rename instead of throwing it.
            syncServerUpdateDirectory(requireNotNull(Settings.serverUpdateStateFile.parentFile))
            if (!stateFile.openRead().use { it.readBytes().contentEquals(bytes) }) throw IOException("Cannot persist server update state")
        } catch (_: Exception) {
            stateFile.failWrite(output)
            throw IOException("Cannot persist server update state")
        }
        mutableState.value = next
    }
}

internal fun syncServerUpdateDirectory(directory: File) {
    val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(descriptor)
    } finally {
        Os.close(descriptor)
    }
}
