package io.nekohasekai.sfa.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun persistsIdenticalReportAcrossRestartAndRetryStatuses() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        store.record("owner", DiagnosticsProjection.event("vpn_start", mapOf("reason" to "User")))
        val batch = store.prepare("owner", JsonObject(emptyMap()), null, emptyList())!!
        for (status in listOf(302, 401, 404, 429, 500, 503)) {
            assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond(batch.id, status))
            val restarted = DiagnosticsStore(directory).pending("owner")!!
            assertEquals(batch.id, restarted.id)
            assertEquals(batch.body, restarted.body)
        }
        store.record("owner", DiagnosticsProjection.event("vpn_stop", mapOf("reason" to "User")))
        assertEquals(DiagnosticsStore.Outcome.ACKNOWLEDGED, store.respond(batch.id, 204))
        assertNull(store.pending("owner"))
        val next = store.prepare("owner", JsonObject(emptyMap()), null, emptyList())!!
        assertEquals("vpn_stop", next.envelope["events"]!!.let { (it as JsonArray)[0].jsonObject["kind"] as JsonPrimitive }.content)
    }

    @Test
    fun splits413DurablyWithoutLosingEventsOrCrashes() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        repeat(8) { store.record("owner", DiagnosticsProjection.event("vpn_start", mapOf("count" to it))) }
        store.discover("owner", "crash_report", "one")
        val crash = DiagnosticsCrash(
            DiagnosticsStore.crashKey("crash_report", "one"),
            buildJsonObject {
                put("kind", "crash_report")
                put("stack", "frame")
            },
        )
        val original = store.prepare("owner", JsonObject(emptyMap()), null, listOf(crash))!!
        assertEquals(DiagnosticsStore.Outcome.SPLIT, store.respond(original.id, 413))
        val values = mutableListOf<Int>()
        var crashCount = 0
        var reopened = DiagnosticsStore(directory)
        while (true) {
            val batch = reopened.pending("owner") ?: break
            assertNotEquals(original.id, batch.id)
            for (event in batch.envelope["events"] as JsonArray) {
                values += ((event.jsonObject["detail"]!!.jsonObject["count"] as JsonPrimitive).content.toInt())
            }
            crashCount += (batch.envelope["crashes"] as JsonArray).size
            reopened.respond(batch.id, 200)
            reopened = DiagnosticsStore(directory)
        }
        assertEquals((0..7).toList(), values)
        assertEquals(1, crashCount)
        assertTrue(reopened.unsentDiscoveries("owner").isEmpty())
    }

    @Test
    fun enforcesFinalEncodedByteBoundsAndJournalRetention() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        repeat(1100) { store.record("owner", DiagnosticsProjection.event("vpn_start", mapOf("message" to "\"я".repeat(900), "count" to it))) }
        assertTrue(store.eventsFile.length() <= 512 * 1024)
        assertTrue(store.eventCount() <= 1000)
        val crashes = (0..8).map { DiagnosticsCrash("crash_report:$it", buildJsonObject { put("stack", "я".repeat(8192)) }) }
        val batch = store.prepare("owner", JsonObject(emptyMap()), null, crashes)!!
        assertTrue(batch.body.toByteArray(Charsets.UTF_8).size <= 768 * 1024)
        assertTrue(batch.envelope["events"].toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024)
        assertEquals(5, (batch.envelope["crashes"] as JsonArray).size)
    }

    @Test
    fun neverMovesOldOwnerEventsOrCrashesToNewDestination() {
        val store = DiagnosticsStore(temporary.newFolder())
        store.record("old", DiagnosticsProjection.event("vpn_start", mapOf("reason" to "old-owner")))
        store.discover("old", "crash_report", "old-crash")
        val old = store.prepare("old", JsonObject(emptyMap()), null, emptyList())!!
        store.record("new", DiagnosticsProjection.event("vpn_stop", mapOf("reason" to "new-owner")))
        val fresh = store.prepare("new", JsonObject(emptyMap()), null, emptyList())!!
        assertFalse(fresh.body.contains("old-owner"))
        assertNotEquals(old.id, fresh.id)
        assertTrue(store.unsentDiscoveries("new").isEmpty())
        store.respond(fresh.id, 204)
        assertTrue(store.prepare("old", JsonObject(emptyMap()), null, emptyList())!!.body.contains("old-owner"))
    }

    @Test
    fun splitsSingleSmartGroupMembersWithoutLosingItsPanic() {
        val store = DiagnosticsStore(temporary.newFolder())
        val smart = buildJsonObject {
            put(
                "groups",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("group", "smart")
                            put("members", JsonArray((0..3).map { buildJsonObject { put("tag", "member$it") } }))
                            put(
                                "panics",
                                buildJsonObject {
                                    put("total", 1)
                                    put("recent", JsonArray(listOf(buildJsonObject { put("site", "probe") })))
                                },
                            )
                        },
                    ),
                ),
            )
        }
        val batch = store.prepare("owner", JsonObject(emptyMap()), smart, emptyList())!!
        assertEquals(DiagnosticsStore.Outcome.SPLIT, store.respond(batch.id, 413))
        var members = 0
        var panics = 0
        while (true) {
            val part = store.pending("owner") ?: break
            val group = ((part.envelope["smart"]!!.jsonObject["groups"] as JsonArray)[0]).jsonObject
            members += (group["members"] as JsonArray).size
            panics += (group["panics"]!!.jsonObject["recent"] as JsonArray).size
            store.respond(part.id, 204)
        }
        assertEquals(4, members)
        assertEquals(1, panics)
    }

    @Test
    fun persistsOnlyBoundedScrubbedEventDetails() {
        val store = DiagnosticsStore(temporary.newFolder())
        store.record(
            "owner",
            DiagnosticsProjection.event(
                "vpn_start_failed",
                mapOf(
                    "message" to "dial https://private.example/credential 192.0.2.99",
                    "configuration" to "private-key",
                    "reason" to "я".repeat(1000),
                ),
            ),
        )
        val stored = store.eventsFile.readText()
        for (secret in listOf("private.example", "credential", "192.0.2.99", "private-key")) assertFalse(stored.contains(secret))
        assertTrue(stored.contains("<url>"))
        assertTrue(stored.toByteArray(Charsets.UTF_8).size < 4096)
    }

    @Test
    fun keepsAnIndivisible413AndRecords400WithoutAcknowledgingOtherEvents() {
        val store = DiagnosticsStore(temporary.newFolder())
        store.record("owner", DiagnosticsProjection.event("vpn_start", emptyMap()))
        val batch = store.prepare("owner", JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond(batch.id, 413))
        assertEquals(batch.body, store.pending("owner")!!.body)
        store.record("owner", DiagnosticsProjection.event("vpn_stop", emptyMap()))
        assertEquals(DiagnosticsStore.Outcome.DISCARDED, store.respond(batch.id, 400))
        val remaining = store.prepare("owner", JsonObject(emptyMap()), null, emptyList())!!.body
        assertTrue(remaining.contains("vpn_stop"))
        assertTrue(remaining.contains("diagnostics_rejected"))
        assertFalse(remaining.contains("vpn_start"))
    }
}
