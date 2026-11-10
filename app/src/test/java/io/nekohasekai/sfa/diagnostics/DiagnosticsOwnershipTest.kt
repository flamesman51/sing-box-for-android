package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.array
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.obj
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executor

class DiagnosticsOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    private val firstProfile = DiagnosticsProfile(1, "https://first.example/profile")
    private val secondProfile = DiagnosticsProfile(2, "https://second.example/profile")
    private val firstDestination = DiagnosticsDestination(1, firstProfile.source!!, "https://first.example/report")
    private val secondDestination = DiagnosticsDestination(2, secondProfile.source!!, "https://second.example/report")

    @Test
    fun processStartBecomesUploadableAtPreferencesReadinessBeforeCoreReadiness() {
        val store = DiagnosticsStore(temporary.newFolder())
        val writes = ArrayDeque<Runnable>()
        var selected = firstProfile
        var captures = 0
        val journal = DiagnosticsOwnership(
            { store },
            Executor { writes.addLast(it) },
            {
                captures++
                selected
            },
            { if (it == firstProfile) firstDestination else secondDestination },
        )
        val detail = mutableMapOf<String, Any?>("version_code" to 123, "message" to "private.example secret")

        journal.record("process_start", detail)
        detail["version_code"] = 456
        assertEquals(0, captures)
        assertTrue(writes.isEmpty())
        assertEquals(0, store.eventCount())

        journal.preferencesReady()
        selected = secondProfile
        while (writes.isNotEmpty()) writes.removeFirst().run()

        val batch = store.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        val event = batch.envelope.array("events").single().jsonObject
        assertEquals("process_start", event.string("kind"))
        assertEquals("123", event.obj("detail")!!.string("version_code"))
        assertFalse(batch.body.contains("private.example"))
        assertFalse(batch.body.contains("secret"))
        assertEquals(1, captures)
        assertTrue(store.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList())!!.envelope.array("events").isEmpty())
    }

    @Test
    fun crashDiscoveryRemainsRetryableUntilAnOwnerExists() {
        val store = DiagnosticsStore(temporary.newFolder())
        var destination: DiagnosticsDestination? = null
        var discoveries = 0
        val journal = DiagnosticsOwnership(
            { store },
            Executor { it.run() },
            { firstProfile },
            { destination },
            { discoveries++ },
        )

        journal.reportFound("crash_report", "crash-1")
        journal.preferencesReady()
        journal.reportFound("crash_report", "crash-1")
        assertEquals(0, store.eventCount())
        assertTrue(store.unsentDiscoveries(firstDestination.owner).isEmpty())
        assertEquals(0, discoveries)

        destination = firstDestination
        journal.retry()
        journal.reportFound("crash_report", "crash-1")

        assertEquals(setOf(DiagnosticsStore.crashKey("crash_report", "crash-1")), store.unsentDiscoveries(firstDestination.owner))
        assertEquals(1, store.eventCount())
        assertEquals(1, discoveries)
    }

    @Test
    fun queuedEventsAndExplicitActiveProfileKeepTheirOriginalDestinationAfterSelectionChanges() {
        val store = DiagnosticsStore(temporary.newFolder())
        val writes = ArrayDeque<Runnable>()
        var selected = firstProfile
        val journal = DiagnosticsOwnership(
            { store },
            Executor { writes.addLast(it) },
            { selected },
            { if (it == firstProfile) firstDestination else secondDestination },
        )
        journal.preferencesReady()
        journal.record("vpn_start", mapOf("reason" to "User"))
        journal.reportFound("crash_report", "crash-1")
        selected = secondProfile
        journal.record("vpn_stop", mapOf("reason" to "User"), firstProfile)
        journal.record("core_ready", emptyMap())
        while (writes.isNotEmpty()) writes.removeFirst().run()

        val first = store.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("vpn_start", "crash_report", "vpn_stop"), first.envelope.array("events").map { it.jsonObject.string("kind") })
        assertTrue(store.unsentDiscoveries(secondDestination.owner).isEmpty())
        val second = store.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("core_ready"), second.envelope.array("events").map { it.jsonObject.string("kind") })
    }

    @Test
    fun deferredEventsNeverMoveToAnotherProfileOrAnotherSource() {
        val store = DiagnosticsStore(temporary.newFolder())
        var selected = firstProfile
        var available: DiagnosticsDestination? = null
        val journal = DiagnosticsOwnership(
            { store },
            Executor { it.run() },
            { selected },
            { available },
        )
        journal.preferencesReady()
        journal.record("vpn_start", mapOf("reason" to "User"))
        journal.reportFound("crash_report", "crash-1")

        selected = secondProfile
        available = secondDestination
        journal.retry()
        available = firstDestination.copy(source = "https://first.example/replaced")
        journal.retry()
        assertEquals(0, store.eventCount())
        assertTrue(store.unsentDiscoveries(secondDestination.owner).isEmpty())

        available = firstDestination
        journal.retry()
        assertEquals(2, store.eventCount())
        assertEquals(setOf(DiagnosticsStore.crashKey("crash_report", "crash-1")), store.unsentDiscoveries(firstDestination.owner))
    }

    @Test
    fun explicitAssociationSurvivesReadinessAndMissingSourceNeverFallsBackToSelection() {
        val store = DiagnosticsStore(temporary.newFolder())
        val journal = DiagnosticsOwnership(
            { store },
            Executor { it.run() },
            { secondProfile },
            { if (it == firstProfile) firstDestination else secondDestination },
        )
        journal.record("vpn_stop", mapOf("reason" to "User"), firstProfile)
        journal.record("vpn_start_failed", emptyMap(), DiagnosticsProfile(1, null))
        assertEquals(0, store.eventCount())

        journal.preferencesReady()
        journal.retry()

        val first = store.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("vpn_stop"), first.envelope.array("events").map { it.jsonObject.string("kind") })
        assertEquals(1, store.eventCount())
        assertTrue(store.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList())!!.envelope.array("events").isEmpty())
    }
}
