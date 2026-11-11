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
    enum class Observation { RECORDED, KNOWN, OTHER_ASSOCIATION }
    val eventsFile = File(directory, "events.jsonl")
    private val stateFile = File(directory, "pending.json")

    private fun readObject(file: File, limit: Int): JsonObject? {
        if (!file.exists()) return null
        if (file.length() > limit) throw IOException("Diagnostics file exceeds its bound")
        return Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }

    private fun state(): JsonObject {
        val current = readObject(stateFile, MAX_STATE_BYTES) ?: return JsonObject(emptyMap())
        val owner = current.string("owner") ?: return current
        if ("batches" !in current) return current
        // Write the owner queue first so an interrupted migration never loses the singleton.
        if (!queueFile(owner).exists()) saveQueue(owner, batches(current))
        return JsonObject(current - "owner" - "batches").also(::save)
    }
    private fun save(state: JsonObject) = atomic(stateFile, state.toString())
    private fun queueFile(owner: String): File = File(directory, "pending-${digest(owner)}.json")
    private fun queue(owner: String): List<DiagnosticsBatch> {
        val stored = readObject(queueFile(owner), MAX_STATE_BYTES) ?: return emptyList()
        require(stored.string("owner") == owner)
        val batches = batches(stored)
        if (JsonArray(batches.map { it.stored() }) != stored["batches"]) saveQueue(owner, batches)
        return batches
    }
    private fun saveQueue(owner: String, batches: List<DiagnosticsBatch>) = atomic(
        queueFile(owner),
        buildJsonObject {
            put("owner", owner)
            put("batches", JsonArray(batches.map { it.stored() }))
        }.toString(),
    )
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
        val stored = eventsFile.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            }.toList()
        }
        val projected = stored.map { entry ->
            entry.obj("event")?.let { JsonObject(entry + ("event" to DiagnosticsProjection.sanitizeEvent(it))) } ?: entry
        }
        if (projected != stored) atomic(eventsFile, projected.joinToString("\n", postfix = "\n"))
        return projected
    }

    fun eventCount(): Int = synchronized(lock) { events().size }

    fun record(owner: String?, event: JsonObject) = synchronized(lock) {
        if (owner == null) return@synchronized
        append(
            buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("owner", owner)
                put("event", DiagnosticsProjection.sanitizeEvent(event))
            },
        )
    }

    fun observe(association: String, owner: String, kind: String, id: String, event: JsonObject): Observation = synchronized(lock) {
        val current = state()
        val observations = current.array("observations")
        val key = crashKey(kind, id)
        observations.mapNotNull { it as? JsonObject }.firstOrNull { it.string("key") == key }?.let {
            return@synchronized if (it.string("association") == association) Observation.KNOWN else Observation.OTHER_ASSOCIATION
        }
        current.array("discoveries").mapNotNull { it as? JsonObject }.firstOrNull { it.string("key") == key }?.let {
            return@synchronized if (it.string("owner") == owner) Observation.KNOWN else Observation.OTHER_ASSOCIATION
        }
        // Recover the association if the event was synced before its observation state.
        val existing = events().firstOrNull { it.string("id") == "discovery:$key" }
        val captured = existing?.string("association") ?: association
        if (existing == null) {
            append(
                buildJsonObject {
                    put("id", "discovery:$key")
                    put("owner", owner)
                    put("association", association)
                    put("event", DiagnosticsProjection.sanitizeEvent(event))
                },
            )
        }
        val observation = buildJsonObject {
            put("key", key)
            put("association", captured)
        }
        save(JsonObject(current + ("observations" to JsonArray((observations + observation).takeLast(1000)))))
        when {
            captured != association -> Observation.OTHER_ASSOCIATION
            existing != null -> Observation.KNOWN
            else -> Observation.RECORDED
        }
    }

    private fun append(entry: JsonObject) {
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
        if (owner == null) return@synchronized false
        val current = state()
        val discoveries = current.array("discoveries")
        val key = crashKey(kind, id)
        if (discoveries.any { (it as? JsonObject)?.string("key") == key }) return false
        val discovery = buildJsonObject {
            put("key", key)
            put("owner", owner)
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
        val body = requireNotNull(stored.obj("body"))
        val projected = JsonObject(
            body + mapOf(
                "events" to JsonArray(body.array("events").map { DiagnosticsProjection.sanitizeEvent(it.jsonObject) }),
                "crashes" to JsonArray(body.array("crashes").map { DiagnosticsProjection.sanitizeCrash(it.jsonObject) }),
                "smart" to (body.obj("smart")?.let(DiagnosticsProjection::sanitizeSmart) ?: JsonNull),
            ),
        )
        DiagnosticsBatch(projected, stored.array("events").map { (it as JsonPrimitive).content }, stored.array("crashes").map { (it as JsonPrimitive).content })
    }

    fun pending(owner: String): DiagnosticsBatch? = synchronized(lock) {
        state()
        queue(owner).firstOrNull()
    }

    fun prepare(owner: String, app: JsonObject, smart: JsonObject?, crashes: List<DiagnosticsCrash>, association: String? = null): DiagnosticsBatch? = synchronized(lock) {
        state()
        queue(owner).firstOrNull()?.let { return it }
        val journal = events()
        val associated = if (association == null) {
            journal
        } else {
            journal.map { entry ->
                if (entry.string("owner") == "local:$association") JsonObject(entry + ("owner" to JsonPrimitive(owner))) else entry
            }
        }
        if (associated != journal) atomic(eventsFile, associated.joinToString("\n", postfix = "\n"))
        val selectedEvents = mutableListOf<JsonObject>()
        val eventIds = mutableListOf<String>()
        var eventBytes = 2
        for (entry in associated) {
            if (entry.string("owner") != owner) continue
            val event = entry.obj("event") ?: continue
            val size = event.toString().toByteArray(Charsets.UTF_8).size + if (selectedEvents.isEmpty()) 0 else 1
            if (eventBytes + size > MAX_EVENT_BYTES) break
            selectedEvents += event
            eventIds += requireNotNull(entry.string("id"))
            eventBytes += size
        }
        val unsent = unsentDiscoveries(owner)
        val selectedCrashes = crashes.filter { it.key in unsent }.take(5)
        val batch = newBatch(app, selectedEvents, selectedCrashes.map { it.report }, smart, eventIds, selectedCrashes.map { it.key })
        require(batch.body.toByteArray(Charsets.UTF_8).size <= MAX_BODY_BYTES)
        saveQueue(owner, listOf(batch))
        batch
    }

    private fun newBatch(app: JsonObject, events: List<JsonElement>, crashes: List<JsonElement>, smart: JsonObject?, eventIds: List<String>, crashKeys: List<String>, at: String = DiagnosticsProjection.utc()): DiagnosticsBatch = DiagnosticsBatch(
        buildJsonObject {
            put("schema", 1)
            put("report_id", UUID.randomUUID().toString())
            put("created_at", at)
            put("app", app)
            put("events", JsonArray(events.map { DiagnosticsProjection.sanitizeEvent(it.jsonObject) }))
            put("smart", smart?.let(DiagnosticsProjection::sanitizeSmart) ?: JsonNull)
            put("crashes", JsonArray(crashes.map { DiagnosticsProjection.sanitizeCrash(it.jsonObject) }))
        },
        eventIds,
        crashKeys,
    )

    fun respond(owner: String, reportId: String, status: Int): Outcome = synchronized(lock) {
        val current = state()
        val queue = queue(owner)
        val batch = queue.firstOrNull()?.takeIf { it.id == reportId } ?: return Outcome.RETRY
        when {
            status in 200..299 || status == 400 -> {
                val ids = batch.eventIds.toSet()
                val remaining = events().filter { it.string("owner") != owner || it.string("id") !in ids }
                atomic(eventsFile, if (remaining.isEmpty()) "" else remaining.joinToString("\n", postfix = "\n"))
                val discoveries = current.array("discoveries").map { item ->
                    val discovery = item.jsonObject
                    if (discovery.string("owner") == owner && discovery.string("key") in batch.crashKeys) JsonObject(discovery + ("sent" to JsonPrimitive(true))) else discovery
                }
                save(JsonObject(current + ("discoveries" to JsonArray(discoveries))))
                saveQueue(owner, queue.drop(1))
                if (status == 400) {
                    record(owner, DiagnosticsProjection.event("diagnostics_rejected", mapOf("status" to 400)))
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
                    saveQueue(owner, children + queue.drop(1))
                    Outcome.SPLIT
                }
            }
            else -> Outcome.RETRY
        }
    }

    private fun split(batch: DiagnosticsBatch): List<DiagnosticsBatch>? {
        val events = batch.envelope.array("events")
        val crashes = batch.envelope.array("crashes")
        val smart = batch.envelope.obj("smart")
        val total = events.size + crashes.size + if (smart != null) 1 else 0
        if (total < 2) return null // An indivisible report stays durable and retries; 413 never acknowledges it.
        val middle = total / 2
        return listOf(0 until middle, middle until total).map { range ->
            val eventIndexes = events.indices.filter { it in range }
            val crashIndexes = crashes.indices.filter { events.size + it in range }
            newBatch(
                requireNotNull(batch.envelope.obj("app")),
                eventIndexes.map { events[it] },
                crashIndexes.map { crashes[it] },
                smart?.takeIf { events.size + crashes.size in range },
                eventIndexes.map { batch.eventIds[it] },
                crashIndexes.map { batch.crashKeys[it] },
                requireNotNull(batch.envelope.string("created_at")),
            )
        }
    }
}
