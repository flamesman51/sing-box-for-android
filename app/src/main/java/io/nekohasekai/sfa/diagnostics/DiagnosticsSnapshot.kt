package io.nekohasekai.sfa.diagnostics

import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.io.File

internal object DiagnosticsSnapshot {
    suspend fun runtime(serviceAvailable: () -> Boolean): DiagnosticsRuntime = coroutineScope {
        fun processSample(): DiagnosticsRuntime {
            val startedAt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Process.getStartElapsedRealtime()
            } else {
                Application.application.processStartedAtElapsedMillis
            }
            return DiagnosticsRuntime(
                System.currentTimeMillis(),
                (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0),
            )
        }
        if (!serviceAvailable()) return@coroutineScope processSample()
        val status = CompletableDeferred<StatusMessage?>()
        val handler = SnapshotHandler(status = status)
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            statusInterval = 1_000_000_000
        }
        // A local client avoids sampling a remote-control target instead of this process.
        val client = CommandClient(handler, options)
        val snapshot = async(Dispatchers.IO) {
            try {
                val startedAt = Libbox.newStandaloneCommandClient().startedAt
                client.connect()
                val sample = status.await()
                sample?.takeIf { serviceAvailable() && startedAt > 0 && startedAt == client.startedAt }?.let {
                    val process = processSample()
                    process.copy(
                        memoryBytes = it.memory.takeIf { bytes -> bytes > 0 },
                        goroutines = it.goroutines.takeIf { count -> count > 0 },
                        serviceUptimeMillis = (process.sampledAt - startedAt).coerceAtLeast(0),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        try {
            withTimeoutOrNull(5_000) { snapshot.await() } ?: processSample()
        } finally {
            runCatching { client.disconnect() }
            snapshot.cancel()
        }
    }

    suspend fun smart(): JsonObject? = coroutineScope {
        val tags = CompletableDeferred<List<String>>()
        val handler = SnapshotHandler(tags)
        val options = CommandClientOptions().apply { addCommand(Libbox.CommandGroup) }
        // Always local: remote-control UI targets must never influence diagnostics ownership.
        val client = CommandClient(handler, options)
        val snapshot = async(Dispatchers.IO) {
            try {
                client.connect()
                val groups = tags.await().mapNotNull { tag ->
                    try {
                        val raw = client.getSmartStatus(tag)
                        if (raw.toByteArray(Charsets.UTF_8).size > 2 * 1024 * 1024) {
                            null
                        } else {
                            (Json.parseToJsonElement(raw) as? JsonObject)?.let(DiagnosticsProjection::smartGroup)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
                }
                buildJsonObject { put("groups", JsonArray(groups)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        try {
            withTimeoutOrNull(15_000) { snapshot.await() }
        } finally {
            // Disconnect cancels the libbox context even when a blocking RPC is still waiting.
            runCatching { client.disconnect() }
            snapshot.cancel()
        }
    }

    suspend fun crashes(unsent: Set<String>): List<DiagnosticsCrash> {
        if (unsent.isEmpty()) return emptyList()
        val candidates = buildList {
            CrashReportManager.reports.value.forEach { add(Candidate("crash_report", it.id, it.date.time, it.directory)) }
            OOMReportManager.reports.value.forEach { add(Candidate("oom_report", it.id, it.date.time, it.directory)) }
            PowerReportManager.reports.value.forEach { add(Candidate("power_report", it.id, it.date.time, it.directory)) }
        }
        return candidates.asSequence().filter { DiagnosticsStore.crashKey(it.kind, it.id) in unsent }
            .sortedBy { it.time }.take(5).map { candidate ->
                DiagnosticsCrash(DiagnosticsStore.crashKey(candidate.kind, candidate.id), projectCrash(candidate))
            }.toList()
    }

    private data class Candidate(val kind: String, val id: String, val time: Long, val directory: File)

    private fun projectCrash(candidate: Candidate): JsonObject {
        val metadataFile = File(candidate.directory, "metadata.json")
        val metadata = runCatching {
            if (metadataFile.isFile && metadataFile.length() <= 64 * 1024) Json.parseToJsonElement(metadataFile.readText(Charsets.UTF_8)) as? JsonObject else null
        }.getOrNull() ?: JsonObject(emptyMap())
        val frames = runCatching {
            val jvm = File(candidate.directory, "jvm.log")
            val go = File(candidate.directory, "go.log")
            val stack = if (jvm.isFile) jvm else go
            if (stack.isFile) stack.reader(Charsets.UTF_8).use { DiagnosticsScrubber.stack(it) } else ""
        }.getOrDefault("")
        // These are the only crash files opened. No configuration, timeline, events, or raw log tail.
        return DiagnosticsProjection.crash(candidate.kind, DiagnosticsProjection.utc(candidate.time), metadata, frames)
    }

    private class SnapshotHandler(
        private val tags: CompletableDeferred<List<String>>? = null,
        private val status: CompletableDeferred<StatusMessage?>? = null,
    ) : CommandClientHandler {
        override fun writeGroups(groups: OutboundGroupIterator?) {
            val tags = tags ?: return
            if (tags.isCompleted || groups == null) return
            val found = mutableListOf<String>()
            var seen = 0
            while (groups.hasNext() && seen++ < 256 && found.size < DiagnosticsProjection.MAX_SMART_GROUPS) {
                val group = groups.next()
                if (group.type == "smart") found += group.tag
            }
            tags.complete(found)
        }
        override fun connected() {}
        override fun disconnected(message: String?) {
            tags?.complete(emptyList())
            status?.complete(null)
        }
        override fun clearLogs() {}
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
        override fun writeLogs(logs: LogIterator?) {}
        override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) {}
        override fun writeStatus(status: StatusMessage?) {
            this.status?.complete(status)
        }
    }
}
