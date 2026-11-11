package io.nekohasekai.sfa.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
import java.io.File

class DiagnosticsStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val app = DiagnosticsProjection.app(buildJsonObject { put("version_name", "1.0") })

    private fun event(
        count: Int = 0,
        kind: String = "vpn_start",
    ) = DiagnosticsProjection.event(kind, mapOf("count" to count, "reason" to "User"))

    private fun crash(id: String): DiagnosticsCrash =
        DiagnosticsCrash(
            DiagnosticsStore.crashKey("crash_report", id),
            DiagnosticsProjection.crash(
                "crash_report",
                DiagnosticsProjection.utc(),
                buildJsonObject { put("exceptionName", "java.lang.IllegalStateException") },
                "\tat io.nekohasekai.sfa.bg.BoxService.start(BoxService.kt:123)",
            ),
        )

    private fun smart(groups: Int = DiagnosticsProjection.MAX_SMART_GROUPS): JsonObject =
        buildJsonObject {
            put(
                "groups",
                JsonArray(
                    (0 until groups).map { index ->
                        DiagnosticsProjection.smartGroup(
                            buildJsonObject {
                                put("group", "smart$index")
                                put("selected", buildJsonObject { put("tcp", "member0") })
                                put(
                                    "members",
                                    JsonArray(
                                        (0..3).map {
                                            buildJsonObject {
                                                put("tag", "member$it")
                                                put("tier", it)
                                                put("state", "healthy")
                                                put("rtt_ms", 10 + it)
                                            }
                                        },
                                    ),
                                )
                                put(
                                    "panics",
                                    buildJsonObject {
                                        put("total", 1)
                                        put(
                                            "recent",
                                            JsonArray(
                                                listOf(
                                                    buildJsonObject {
                                                        put("at", DiagnosticsProjection.utc())
                                                        put("site", "probe")
                                                        put("stack", "github.com/sagernet/sing-box/outbound.(*Smart).probe()\n\toutbound/smart.go:123 +0x12")
                                                    },
                                                ),
                                            ),
                                        )
                                    },
                                )
                            },
                        )
                    },
                ),
            )
        }

    @Test
    fun persistsIdenticalReportAcrossRestartAndRetryStatuses() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        store.record("owner", event())
        val batch = store.prepare("owner", app, null, emptyList())!!
        for (status in listOf(302, 401, 404, 429, 500, 503)) {
            assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond("owner", batch.id, status))
            val restarted = DiagnosticsStore(directory).pending("owner")!!
            assertEquals(batch.id, restarted.id)
            assertEquals(batch.body, restarted.body)
        }
        store.record("owner", event(kind = "vpn_stop"))
        assertEquals(DiagnosticsStore.Outcome.ACKNOWLEDGED, store.respond("owner", batch.id, 204))
        assertNull(store.pending("owner"))
        val next = store.prepare("owner", app, null, emptyList())!!
        assertEquals("vpn_stop", ((next.envelope["events"] as JsonArray)[0].jsonObject["kind"] as JsonPrimitive).content)
    }

    @Test
    fun switchesOwnersWithoutReplacingEitherDurableQueue() {
        val directory = temporary.newFolder()
        var store = DiagnosticsStore(directory)
        store.record("A", event(1))
        store.discover("A", "crash_report", "A-crash")
        val aCrash = crash("A-crash")
        val a = store.prepare("A", app, smart(), listOf(aCrash))!!
        store.record("B", event(2))
        store.discover("B", "crash_report", "B-crash")
        val bCrash = crash("B-crash")
        val b = store.prepare("B", app, null, listOf(aCrash, bCrash))!!
        assertEquals(listOf(bCrash.report), b.envelope["crashes"] as JsonArray)
        assertEquals(listOf(2), counts(b))
        assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond("B", a.id, 204))
        store = DiagnosticsStore(directory)
        assertEquals(a.body, store.prepare("A", app, null, emptyList())!!.body)
        assertEquals(a.id, store.pending("A")!!.id)
        assertEquals(b.body, store.pending("B")!!.body)
        assertEquals(DiagnosticsStore.Outcome.ACKNOWLEDGED, store.respond("B", b.id, 204))
        assertEquals(a.body, DiagnosticsStore(directory).pending("A")!!.body)
        assertEquals(setOf(aCrash.key), store.unsentDiscoveries("A"))
        assertTrue(store.unsentDiscoveries("B").isEmpty())
        assertEquals(1, store.eventCount())
    }

    @Test
    fun resumesAlreadySplitQueueWithIdenticalFragmentIdsAndBodies() {
        val directory = temporary.newFolder()
        var store = DiagnosticsStore(directory)
        repeat(8) { store.record("A", event(it)) }
        val original = store.prepare("A", app, smart(), emptyList())!!
        assertEquals(DiagnosticsStore.Outcome.SPLIT, store.respond("A", original.id, 413))
        val first = store.pending("A")!!
        assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond("A", original.id, 204))
        val persisted = ownerQueue(directory, "A").readText()
        store.record("B", event(99))
        val b = store.prepare("B", app, null, emptyList())!!
        store = DiagnosticsStore(directory)
        assertEquals(first.id, store.prepare("A", app, null, emptyList())!!.id)
        assertEquals(first.body, store.pending("A")!!.body)
        assertEquals(persisted, ownerQueue(directory, "A").readText())
        assertEquals(b.body, store.pending("B")!!.body)
        val collectedCounts = mutableListOf<Int>()
        var snapshots = 0
        while (true) {
            val part = store.pending("A") ?: break
            collectedCounts += counts(part)
            if (part.envelope["smart"] is JsonObject) snapshots++
            store.respond("A", part.id, 204)
            store = DiagnosticsStore(directory)
        }
        assertEquals((0..7).toList(), collectedCounts)
        assertEquals(1, snapshots)
        assertEquals(b.body, store.pending("B")!!.body)
    }

    @Test
    fun acknowledgementsAreScopedEvenWhenStoredIdsMatchAcrossOwners() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        val eventId = "77d5eb98-c606-423c-9f4c-b039c8d88ee0"
        val crash = crash("shared-id")
        store.eventsFile.writeText(
            listOf("A", "B").joinToString("\n", postfix = "\n") { owner ->
                buildJsonObject {
                    put("id", eventId)
                    put("owner", owner)
                    put("event", event())
                }.toString()
            },
        )
        File(directory, "pending.json").writeText(
            buildJsonObject {
                put(
                    "discoveries",
                    JsonArray(
                        listOf("A", "B").map { owner ->
                            buildJsonObject {
                                put("owner", owner)
                                put("key", crash.key)
                                put("sent", false)
                            }
                        },
                    ),
                )
            }.toString(),
        )
        val a = store.prepare("A", app, null, listOf(crash))!!
        val b = store.prepare("B", app, null, listOf(crash))!!
        val sameId = b.copy(envelope = JsonObject(b.envelope + ("report_id" to JsonPrimitive(a.id))))
        writeQueue(directory, "B", listOf(sameId))
        assertEquals(DiagnosticsStore.Outcome.ACKNOWLEDGED, store.respond("A", a.id, 204))
        assertNull(store.pending("A"))
        assertEquals(sameId.body, store.pending("B")!!.body)
        assertEquals(1, store.eventCount())
        assertEquals(setOf(crash.key), store.unsentDiscoveries("B"))
        assertTrue(store.unsentDiscoveries("A").isEmpty())
        assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond("A", sameId.id, 204))
        assertEquals(DiagnosticsStore.Outcome.ACKNOWLEDGED, store.respond("B", sameId.id, 204))
        assertEquals(0, store.eventCount())
    }

    @Test
    fun migratesSingletonSplitQueueWithoutChangingIdsOrLosingOtherOwner() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        repeat(8) { store.record("A", event(it)) }
        val a = store.prepare("A", app, smart(), emptyList())!!
        store.respond("A", a.id, 413)
        val split = Json.parseToJsonElement(ownerQueue(directory, "A").readText()).jsonObject["batches"]!!
        File(directory, "pending.json").writeText(
            buildJsonObject {
                put("owner", "A")
                put("batches", split)
                put("discoveries", JsonArray(emptyList()))
            }.toString(),
        )
        assertTrue(ownerQueue(directory, "A").delete())
        val reopened = DiagnosticsStore(directory)
        reopened.record("B", event(99))
        val b = reopened.prepare("B", app, null, emptyList())!!
        val migrated = Json.parseToJsonElement(ownerQueue(directory, "A").readText()).jsonObject
        assertEquals(split, migrated["batches"])
        assertEquals((split as JsonArray)[0].jsonObject["body"], reopened.pending("A")!!.envelope)
        assertEquals(b.body, reopened.pending("B")!!.body)
        assertFalse(Json.parseToJsonElement(File(directory, "pending.json").readText()).jsonObject.containsKey("batches"))
    }

    @Test
    fun interruptedMigrationNeverRestoresAlreadyAcknowledgedFragments() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        repeat(4) { store.record("A", event(it)) }
        val a = store.prepare("A", app, null, emptyList())!!
        store.respond("A", a.id, 413)
        val legacy = ownerQueue(directory, "A").readText()
        val first = store.pending("A")!!
        store.respond("A", first.id, 204)
        val remaining = store.pending("A")!!
        File(directory, "pending.json").writeText(legacy)
        val reopened = DiagnosticsStore(directory)
        assertEquals(remaining.body, reopened.pending("A")!!.body)
        assertNotEquals(first.id, reopened.pending("A")!!.id)
    }

    @Test
    fun defersNullDiscoveryAndNeverClaimsHistoricalUnownedData() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        store.record(null, event())
        assertEquals(0, store.eventCount())
        assertFalse(store.discover(null, "crash_report", "deferred"))
        assertTrue(store.discover("A", "crash_report", "deferred"))
        assertFalse(store.discover("B", "crash_report", "deferred"))
        assertEquals(setOf(DiagnosticsStore.crashKey("crash_report", "deferred")), store.unsentDiscoveries("A"))
        assertTrue(store.unsentDiscoveries("B").isEmpty())
        store.eventsFile.writeText(
            buildJsonObject {
                put("id", "historical-event")
                put("owner", JsonNull)
                put("event", event(17))
            }.toString() + "\n",
        )
        val legacy = Json.parseToJsonElement(File(directory, "pending.json").readText()).jsonObject
        val unowned =
            buildJsonObject {
                put("key", DiagnosticsStore.crashKey("crash_report", "unowned"))
                put("owner", JsonNull)
                put("sent", false)
            }
        File(directory, "pending.json").writeText(JsonObject(legacy + ("discoveries" to JsonArray((legacy["discoveries"] as JsonArray) + unowned))).toString())
        assertFalse(store.discover("A", "crash_report", "unowned"))
        val batch = store.prepare("B", app, null, listOf(crash("deferred"), crash("unowned")))!!
        assertTrue((batch.envelope["events"] as JsonArray).isEmpty())
        assertTrue((batch.envelope["crashes"] as JsonArray).isEmpty())
        store.respond("B", batch.id, 204)
        assertEquals(1, store.eventCount())
        assertEquals(setOf(DiagnosticsStore.crashKey("crash_report", "deferred")), store.unsentDiscoveries("A"))
    }

    @Test
    fun repeated413SplitsOnlyEventsAndCrashesAndKeepsWholeSnapshotExactlyOnce() {
        val directory = temporary.newFolder()
        var store = DiagnosticsStore(directory)
        repeat(8) { store.record("owner", event(it)) }
        val crashes =
            (0..2).map { index ->
                store.discover("owner", "crash_report", "crash$index")
                crash("crash$index")
            }
        val snapshot = smart()
        val original = store.prepare("owner", app, snapshot, crashes)!!
        assertEquals(snapshot, original.envelope["smart"])
        assertEquals(DiagnosticsStore.Outcome.SPLIT, store.respond("owner", original.id, 413))
        val collectedCounts = mutableListOf<Int>()
        val crashKeys = mutableListOf<String>()
        var snapshots = 0
        while (true) {
            val part = store.pending("owner") ?: break
            val outcome = store.respond("owner", part.id, 413)
            if (outcome == DiagnosticsStore.Outcome.SPLIT) {
                store = DiagnosticsStore(directory)
                continue
            }
            assertEquals(DiagnosticsStore.Outcome.RETRY, outcome)
            collectedCounts += counts(part)
            crashKeys += part.crashKeys
            if (part.envelope["smart"] is JsonObject) {
                snapshots++
                assertEquals(snapshot, part.envelope["smart"])
                assertEquals(part.body, DiagnosticsStore(directory).pending("owner")!!.body)
                assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond("owner", part.id, 413))
            }
            store.respond("owner", part.id, 204)
            store = DiagnosticsStore(directory)
        }
        assertEquals((0..7).toList(), collectedCounts)
        assertEquals(crashes.map { it.key }, crashKeys)
        assertEquals(1, snapshots)
        assertTrue(store.unsentDiscoveries("owner").isEmpty())
    }

    @Test
    fun smartOnlyMultiGroupSnapshotIsIndivisible() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        val snapshot = smart()
        val batch = store.prepare("owner", app, snapshot, emptyList())!!
        repeat(3) {
            assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond("owner", batch.id, 413))
            assertEquals(batch.body, DiagnosticsStore(directory).pending("owner")!!.body)
            assertEquals(snapshot, store.pending("owner")!!.envelope["smart"])
        }
    }

    @Test
    fun maximalProjectedSnapshotEventsAndCrashesFitEncodedBodyBound() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        val detail =
            buildMap<String, Any> {
                for (key in listOf("from", "to", "count", "status", "version_code", "start_id")) put(key, Long.MAX_VALUE)
                put("reason", "User")
                put("type", "E".repeat(128))
                put("category", "other")
            }
        val maximalEvent = DiagnosticsProjection.event("vpn_start_failed", detail)
        repeat(1100) { store.record("owner", maximalEvent) }
        assertTrue(store.eventsFile.length() <= 512 * 1024)
        assertTrue(store.eventCount() <= 1000)
        val frame = "\tat io.nekohasekai.sfa.bg.${"Service".repeat(15)}.${"start".repeat(20)}(BoxService.kt:123)\n"
        val crashes =
            (0..8).map { index ->
                store.discover("owner", "crash_report", "max$index")
                DiagnosticsCrash(
                    DiagnosticsStore.crashKey("crash_report", "max$index"),
                    DiagnosticsProjection.crash(
                        "crash_report",
                        DiagnosticsProjection.utc(),
                        buildJsonObject {
                            put("exceptionName", "java.lang.IllegalStateException")
                            put("appMarketingVersion", "v".repeat(128))
                            put("coreVersion", "v".repeat(128))
                        },
                        frame.repeat(256),
                    ),
                )
            }
        val group =
            DiagnosticsProjection.smartGroup(
                buildJsonObject {
                    put("group", "g".repeat(128))
                    put(
                        "selected",
                        buildJsonObject {
                            put("tcp", "t".repeat(128))
                            put("standby", "s".repeat(128))
                        },
                    )
                    put("degraded", true)
                    put("network", buildJsonObject { put("type", "wifi") })
                    put(
                        "budgets",
                        buildJsonObject {
                            for (type in listOf("wifi", "cellular", "other")) {
                                put(
                                    type,
                                    buildJsonObject {
                                        put("day", "2026-11-10")
                                        put("day_ends_at", "2026-11-11T00:00:00Z")
                                        for (key in listOf("used_mb", "reserved_mb", "limit_mb")) put(key, Double.MAX_VALUE)
                                    },
                                )
                            }
                        },
                    )
                    put(
                        "members",
                        JsonArray(
                            (0 until 256).map {
                                buildJsonObject {
                                    put("tag", "m".repeat(128))
                                    put("state", "healthy")
                                    put("tier", Long.MAX_VALUE)
                                    put("rtt_ms", Double.MAX_VALUE)
                                    put("download_mbps", Double.MAX_VALUE)
                                    put("throughput_source", "probe")
                                    put("throughput_at", Long.MAX_VALUE)
                                    put("quarantine_until", Long.MAX_VALUE)
                                }
                            },
                        ),
                    )
                    put(
                        "panics",
                        buildJsonObject {
                            put("total", Long.MAX_VALUE)
                            put(
                                "recent",
                                JsonArray(
                                    (0 until 128).map {
                                        buildJsonObject {
                                            put("at", DiagnosticsProjection.utc())
                                            put("site", "probe")
                                            put("stack", frame.repeat(256))
                                        }
                                    },
                                ),
                            )
                        },
                    )
                },
            )
        val snapshot = buildJsonObject { put("groups", JsonArray(List(DiagnosticsProjection.MAX_SMART_GROUPS) { group })) }
        assertTrue(group.toString().toByteArray(Charsets.UTF_8).size < 64 * 1024)
        assertTrue(snapshot.toString().toByteArray(Charsets.UTF_8).size < 256 * 1024)
        val batch = store.prepare("owner", app, snapshot, crashes)!!
        assertEquals(snapshot, batch.envelope["smart"])
        assertEquals(5, (batch.envelope["crashes"] as JsonArray).size)
        assertTrue(batch.body.toByteArray(Charsets.UTF_8).size < DiagnosticsStore.MAX_BODY_BYTES)
        assertTrue(
            batch.envelope["events"]
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size <= DiagnosticsStore.MAX_EVENT_BYTES,
        )
        assertTrue(
            batch.envelope["events"]
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size >
                DiagnosticsStore.MAX_EVENT_BYTES - maximalEvent.toString().toByteArray(Charsets.UTF_8).size - 1,
        )
    }

    @Test
    fun sanitizesLegacyJournalAndPendingWithoutChangingReportId() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        store.record("owner", event())
        val snapshot = smart(1)
        val crash = crash("legacy")
        store.discover("owner", "crash_report", "legacy")
        val original = store.prepare("owner", app, snapshot, listOf(crash))!!
        val secret = JsonPrimitive("private.example credential 192.0.2.99")
        fun withMessage(value: JsonObject) = JsonObject(value + ("message" to secret))
        val legacyEvent = (original.envelope["events"] as JsonArray)[0].jsonObject
        val dirtyEvent = JsonObject(legacyEvent + ("detail" to withMessage(legacyEvent["detail"]!!.jsonObject)))
        val group = (snapshot["groups"] as JsonArray)[0].jsonObject
        val panics = group["panics"]!!.jsonObject
        val dirtyPanic = withMessage((panics["recent"] as JsonArray)[0].jsonObject)
        val dirtySmart =
            buildJsonObject {
                put("groups", JsonArray(listOf(JsonObject(group + ("panics" to JsonObject(panics + ("recent" to JsonArray(listOf(dirtyPanic)))))))))
            }
        val dirty =
            original.copy(
                envelope =
                    JsonObject(
                        original.envelope +
                            mapOf(
                                "events" to JsonArray(listOf(dirtyEvent)),
                                "crashes" to JsonArray(listOf(withMessage(crash.report))),
                                "smart" to dirtySmart,
                            ),
                    ),
            )
        writeQueue(directory, "owner", listOf(dirty))
        val entry = Json.parseToJsonElement(store.eventsFile.readText().trim()).jsonObject
        store.eventsFile.writeText(JsonObject(entry + ("event" to dirtyEvent)).toString() + "\n")
        val reopened = DiagnosticsStore(directory)
        val cleaned = reopened.pending("owner")!!
        assertEquals(original.id, cleaned.id)
        assertFalse(cleaned.body.contains("message"))
        assertFalse(cleaned.body.contains("private.example"))
        assertFalse(ownerQueue(directory, "owner").readText().contains("private.example"))
        reopened.eventCount()
        assertFalse(store.eventsFile.readText().contains("message"))
        assertFalse(store.eventsFile.readText().contains("private.example"))
        assertEquals(cleaned.body, DiagnosticsStore(directory).pending("owner")!!.body)
    }

    @Test
    fun keepsAnIndivisible413AndRecords400WithoutAcknowledgingOtherEvents() {
        val store = DiagnosticsStore(temporary.newFolder())
        store.record("owner", event())
        val batch = store.prepare("owner", app, null, emptyList())!!
        assertEquals(DiagnosticsStore.Outcome.RETRY, store.respond("owner", batch.id, 413))
        assertEquals(batch.body, store.pending("owner")!!.body)
        store.record("owner", event(kind = "vpn_stop"))
        assertEquals(DiagnosticsStore.Outcome.DISCARDED, store.respond("owner", batch.id, 400))
        val remaining = store.prepare("owner", app, null, emptyList())!!.body
        assertTrue(remaining.contains("vpn_stop"))
        assertTrue(remaining.contains("diagnostics_rejected"))
        assertFalse(remaining.contains("vpn_start"))
    }

    private fun counts(batch: DiagnosticsBatch): List<Int> =
        (batch.envelope["events"] as JsonArray).map {
            ((it.jsonObject["detail"]!!.jsonObject["count"] as JsonPrimitive).content.toInt())
        }

    private fun ownerQueue(
        directory: File,
        owner: String,
    ) = File(directory, "pending-${DiagnosticsStore.digest(owner)}.json")

    private fun writeQueue(
        directory: File,
        owner: String,
        batches: List<DiagnosticsBatch>,
    ) {
        ownerQueue(directory, owner).writeText(
            buildJsonObject {
                put("owner", owner)
                put("batches", JsonArray(batches.map { it.stored() }))
            }.toString(),
        )
    }
}
