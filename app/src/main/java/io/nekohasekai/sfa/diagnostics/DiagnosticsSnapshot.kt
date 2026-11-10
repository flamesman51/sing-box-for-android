package io.nekohasekai.sfa.diagnostics

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
import kotlinx.serialization.json.put
import java.io.File

internal object DiagnosticsSnapshot {
    suspend fun smart(): JsonObject? = coroutineScope {
        val tags = CompletableDeferred<List<String>>()
        val handler = GroupsHandler(tags)
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
        CrashReportManager.refresh()
        OOMReportManager.refresh()
        PowerReportManager.refresh()
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

    private class GroupsHandler(private val tags: CompletableDeferred<List<String>>) : CommandClientHandler {
        override fun writeGroups(groups: OutboundGroupIterator?) {
            if (tags.isCompleted || groups == null) return
            val found = mutableListOf<String>()
            var seen = 0
            while (groups.hasNext() && seen++ < 256 && found.size < 32) {
                val group = groups.next()
                if (group.type == "smart") found += group.tag
            }
            tags.complete(found)
        }
        override fun connected() {}
        override fun disconnected(message: String?) {
            tags.complete(emptyList())
        }
        override fun clearLogs() {}
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
        override fun setDefaultLogLevel(level: Int) {}
        override fun updateClashMode(newMode: String?) {}
        override fun writeConnectionEvents(events: ConnectionEvents?) {}
        override fun writeLogs(logs: LogIterator?) {}
        override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) {}
        override fun writeStatus(status: StatusMessage?) {}
    }
}
