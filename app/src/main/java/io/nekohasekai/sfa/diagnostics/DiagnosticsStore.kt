package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.array
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.obj
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

internal data class DiagnosticsCrash(val key: String, val report: JsonObject)
internal data class DiagnosticsBatch(val envelope: JsonObject, val eventIds: List<String>, val crashKeys: List<String>) {
    val id: String get() = requireNotNull(envelope.string("report_id"))
    val body: String get() = envelope.toString()
    fun stored(): JsonObject = buildJsonObject {
        put("body", envelope)
        put("events", JsonArray(eventIds.map(::JsonPrimitive)))
        put("crashes", JsonArray(crashKeys.map(::JsonPrimitive)))
    }
}

internal class DiagnosticsStore(private val directory: File) {
    companion object {
        const val MAX_BODY_BYTES = 768 * 1024
        const val MAX_EVENT_BYTES = 256 * 1024
        private const val MAX_JOURNAL_BYTES = 512 * 1024
        private const val MAX_STATE_BYTES = 4 * 1024 * 1024
        private val lock = Any()
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun crashKey(kind: String, id: String): String = "$kind:${digest(id)}"
    }

    enum class Outcome { ACKNOWLEDGED, DISCARDED, SPLIT, RETRY }
    val eventsFile = File(directory, "events.jsonl")
    private val stateFile = File(directory, "pending.json")

    private fun readObject(file: File, limit: Int): JsonObject? {
        if (!file.exists()) return null
        if (file.length() > limit) throw IOException("Diagnostics file exceeds its bound")
        return Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }

    private fun state(): JsonObject = readObject(stateFile, MAX_STATE_BYTES) ?: JsonObject(emptyMap())
    private fun save(state: JsonObject) = atomic(stateFile, state.toString())
    private fun atomic(file: File, content: String) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostics storage")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STATE_BYTES) throw IOException("Diagnostics storage exceeds its bound")
        val temp = File(directory, "${file.name}.tmp")
        FileOutputStream(temp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("Cannot replace diagnostics storage")
    }

    private fun events(): List<JsonObject> {
        if (!eventsFile.exists()) return emptyList()
        if (eventsFile.length() > MAX_JOURNAL_BYTES) throw IOException("Diagnostics journal exceeds its bound")
        return eventsFile.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            }.toList()
        }
    }

    fun eventCount(): Int = synchronized(lock) { events().size }

    fun record(owner: String?, event: JsonObject) = synchronized(lock) {
        val entry = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("owner", owner?.let(::JsonPrimitive) ?: JsonNull)
            put("event", event)
        }
        val line = entry.toString() + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8 * 1024)
        val existing = events()
        if (existing.size < 1000 && eventsFile.length() + bytes.size <= MAX_JOURNAL_BYTES) {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create diagnostics storage")
            FileOutputStream(eventsFile, true).use {
                it.write(bytes)
                it.fd.sync()
            }
        } else {
            val kept = (existing + entry).takeLast(1000).toMutableList()
            var size = kept.sumOf { it.toString().toByteArray(Charsets.UTF_8).size + 1 }
            while (size > MAX_JOURNAL_BYTES) size -= kept.removeAt(0).toString().toByteArray(Charsets.UTF_8).size + 1
            atomic(eventsFile, kept.joinToString("\n", postfix = "\n"))
        }
    }

    fun discover(owner: String?, kind: String, id: String): Boolean = synchronized(lock) {
        val current = state()
        val discoveries = current.array("discoveries")
        val key = crashKey(kind, id)
        if (discoveries.any { (it as? JsonObject)?.string("key") == key }) return false
        val discovery = buildJsonObject {
            put("key", key)
            put("owner", owner?.let(::JsonPrimitive) ?: JsonNull)
            put("sent", false)
        }
        save(JsonObject(current + ("discoveries" to JsonArray((discoveries + discovery).takeLast(512)))))
        true
    }

    fun unsentDiscoveries(owner: String): Set<String> = synchronized(lock) {
        state().array("discoveries").mapNotNull { it as? JsonObject }.filter {
            it.string("owner") == owner && it["sent"] == JsonPrimitive(false)
        }.mapNotNull { it.string("key") }.toSet()
    }

    private fun batches(state: JsonObject): List<DiagnosticsBatch> = state.array("batches").map { item ->
        val stored = item.jsonObject
        DiagnosticsBatch(requireNotNull(stored.obj("body")), stored.array("events").map { (it as JsonPrimitive).content }, stored.array("crashes").map { (it as JsonPrimitive).content })
    }

    fun pending(owner: String): DiagnosticsBatch? = synchronized(lock) {
        val current = state()
        if (current.string("owner") != owner) null else batches(current).firstOrNull()
    }

    fun prepare(owner: String, app: JsonObject, smart: JsonObject?, crashes: List<DiagnosticsCrash>): DiagnosticsBatch? = synchronized(lock) {
        val current = state()
        if (current.string("owner") == owner) batches(current).firstOrNull()?.let { return it }
        val selectedEvents = mutableListOf<JsonObject>()
        val eventIds = mutableListOf<String>()
        var eventBytes = 2
        for (entry in events()) {
            if (entry.string("owner") != owner) continue
            val event = entry.obj("event") ?: continue
            val size = event.toString().toByteArray(Charsets.UTF_8).size + if (selectedEvents.isEmpty()) 0 else 1
            if (eventBytes + size > MAX_EVENT_BYTES) break
            selectedEvents += event
            eventIds += requireNotNull(entry.string("id"))
            eventBytes += size
        }
        val selectedCrashes = crashes.take(5)
        val groups = mutableListOf<JsonElement>()
        var batch = newBatch(app, selectedEvents, selectedCrashes.map { it.report }, null, eventIds, selectedCrashes.map { it.key })
        for (group in smart?.array("groups").orEmpty().take(32)) {
            val candidate = batch.copy(envelope = JsonObject(batch.envelope + ("smart" to buildJsonObject { put("groups", JsonArray(groups + group)) })))
            if (candidate.body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) break
            groups += group
            batch = candidate
        }
        require(batch.body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
        // A cutover never acknowledges old-owner data; it remains in the bounded source journal.
        save(JsonObject(current + mapOf("owner" to JsonPrimitive(owner), "batches" to JsonArray(listOf(batch.stored())))))
        batch
    }

    private fun newBatch(app: JsonObject, events: List<JsonElement>, crashes: List<JsonElement>, smart: JsonObject?, eventIds: List<String>, crashKeys: List<String>, at: String = DiagnosticsProjection.utc()): DiagnosticsBatch = DiagnosticsBatch(
        buildJsonObject {
            put("schema", 1)
            put("report_id", UUID.randomUUID().toString())
            put("created_at", at)
            put("app", app)
            put("events", JsonArray(events))
            put("smart", smart ?: JsonNull)
            put("crashes", JsonArray(crashes))
        },
        eventIds,
        crashKeys,
    )

    fun respond(reportId: String, status: Int): Outcome = synchronized(lock) {
        val current = state()
        val queue = batches(current)
        val batch = queue.firstOrNull()?.takeIf { it.id == reportId } ?: return Outcome.RETRY
        when {
            status in 200..299 || status == 400 -> {
                val ids = batch.eventIds.toSet()
                val remaining = events().filter { it.string("id") !in ids }
                atomic(eventsFile, if (remaining.isEmpty()) "" else remaining.joinToString("\n", postfix = "\n"))
                val discoveries = current.array("discoveries").map { item ->
                    val discovery = item.jsonObject
                    if (discovery.string("key") in batch.crashKeys) JsonObject(discovery + ("sent" to JsonPrimitive(true))) else discovery
                }
                save(JsonObject(current + mapOf("batches" to JsonArray(queue.drop(1).map { it.stored() }), "discoveries" to JsonArray(discoveries))))
                if (status == 400) {
                    record(current.string("owner"), DiagnosticsProjection.event("diagnostics_rejected", mapOf("status" to 400)))
                    Outcome.DISCARDED
                } else {
                    Outcome.ACKNOWLEDGED
                }
            }
            status == 413 -> {
                val children = split(batch)
                if (children == null) {
                    Outcome.RETRY
                } else {
                    save(JsonObject(current + ("batches" to JsonArray((children + queue.drop(1)).map { it.stored() }))))
                    Outcome.SPLIT
                }
            }
            else -> Outcome.RETRY
        }
    }

    private fun split(batch: DiagnosticsBatch): List<DiagnosticsBatch>? {
        val events = batch.envelope.array("events")
        val crashes = batch.envelope.array("crashes")
        val groups = batch.envelope.obj("smart")?.array("groups").orEmpty()
        val total = events.size + crashes.size + groups.size
        if (total == 1 && groups.size == 1) {
            val group = groups.single() as? JsonObject ?: return null
            val members = group.array("members")
            val recent = group.obj("panics")?.array("recent").orEmpty()
            val count = members.size + recent.size
            if (count >= 2) {
                return listOf(0 until count / 2, count / 2 until count).map { range ->
                    val fragment = JsonObject(
                        group + mapOf(
                            "members" to JsonArray(members.filterIndexed { index, _ -> index in range }),
                            "panics" to (
                                group.obj("panics")?.let {
                                    JsonObject(it + ("recent" to JsonArray(recent.filterIndexed { index, _ -> members.size + index in range })))
                                } ?: JsonNull
                                ),
                        ),
                    )
                    newBatch(
                        requireNotNull(batch.envelope.obj("app")),
                        emptyList(),
                        emptyList(),
                        buildJsonObject { put("groups", JsonArray(listOf(fragment))) },
                        emptyList(),
                        emptyList(),
                        requireNotNull(batch.envelope.string("created_at")),
                    )
                }
            }
        }
        if (total < 2) return null // An indivisible report stays durable and retries; 413 never acknowledges it.
        val middle = total / 2
        return listOf(0 until middle, middle until total).map { range ->
            val eventIndexes = events.indices.filter { it in range }
            val crashIndexes = crashes.indices.filter { events.size + it in range }
            val groupIndexes = groups.indices.filter { events.size + crashes.size + it in range }
            newBatch(
                requireNotNull(batch.envelope.obj("app")),
                eventIndexes.map { events[it] },
                crashIndexes.map { crashes[it] },
                groupIndexes.takeIf { it.isNotEmpty() }?.let { buildJsonObject { put("groups", JsonArray(it.map { index -> groups[index] })) } },
                eventIndexes.map { batch.eventIds[it] },
                crashIndexes.map { batch.crashKeys[it] },
                requireNotNull(batch.envelope.string("created_at")),
            )
        }
    }
}
