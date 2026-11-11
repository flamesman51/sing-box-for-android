package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.array
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.obj
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

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
        assertEquals(1, store.eventCount())
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
    fun locallyPersistedEventsNeverMoveToAnotherProfileOrAnotherSource() {
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
        assertEquals(2, store.eventCount())
        assertTrue(store.unsentDiscoveries(secondDestination.owner).isEmpty())
        assertTrue(store.unsentDiscoveries(firstDestination.owner).isEmpty())

        selected = firstProfile
        available = firstDestination
        journal.retry()
        journal.reportFound("crash_report", "crash-1")
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
        assertEquals(2, store.eventCount())
        assertTrue(store.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList())!!.envelope.array("events").isEmpty())
    }

    @Test
    fun localAndCapabilitylessEventsSurviveRestartBeyondTheDeferredQueueLimit() {
        for (profile in listOf(DiagnosticsProfile(3, null), firstProfile)) {
            val directory = temporary.newFolder()
            val store = DiagnosticsStore(directory)
            val journal = DiagnosticsOwnership({ store }, Executor { it.run() }, { profile }, { null })
            journal.preferencesReady()

            repeat(1100) { journal.record("process_start", mapOf("version_code" to it)) }

            val reopened = DiagnosticsStore(directory)
            assertEquals(1000, reopened.eventCount())
            val entries = reopened.eventsFile.readLines().map { Json.parseToJsonElement(it).jsonObject }
            assertEquals("100", entries.first().obj("event")!!.obj("detail")!!.string("version_code"))
            assertEquals("1099", entries.last().obj("event")!!.obj("detail")!!.string("version_code"))
            assertTrue(entries.all { it.string("owner") == profile.localOwner })
            assertFalse(reopened.eventsFile.readText().contains("https://"))
            assertTrue(reopened.eventsFile.length() <= 512 * 1024)
            assertTrue(reopened.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!.envelope.array("events").isEmpty())
        }
    }

    @Test
    fun preferenceDeferredEventsCaptureAssociationOnceAndMissingSelectionStaysLocal() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        val writes = ArrayDeque<Runnable>()
        var selected: DiagnosticsProfile? = null
        val journal = DiagnosticsOwnership(
            { store },
            Executor { writes.addLast(it) },
            { selected },
            { if (it == firstProfile) firstDestination else secondDestination },
        )
        journal.record("process_start", emptyMap())
        assertTrue(writes.isEmpty())
        journal.preferencesReady()
        selected = secondProfile
        while (writes.isNotEmpty()) writes.removeFirst().run()
        journal.retry()

        val reopened = DiagnosticsStore(directory)
        assertEquals(1, reopened.eventCount())
        assertTrue(reopened.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList())!!.envelope.array("events").isEmpty())
        assertFalse(reopened.eventsFile.readText().contains("https://"))
    }

    @Test
    fun discoveryObservationSurvivesRestartWithoutAcknowledgingOrChangingItsAssociation() {
        val directory = temporary.newFolder()
        var selected = firstProfile
        var available: DiagnosticsDestination? = null
        var discoveries = 0
        fun journal(store: DiagnosticsStore) = DiagnosticsOwnership(
            { store },
            Executor { it.run() },
            { selected },
            { available },
            { discoveries++ },
        ).apply { preferencesReady() }
        val initial = DiagnosticsStore(directory)
        journal(initial).reportFound("crash_report", "private-crash-id")
        assertEquals(1, initial.eventCount())
        assertTrue(initial.unsentDiscoveries(firstDestination.owner).isEmpty())

        val reopened = DiagnosticsStore(directory)
        val restarted = journal(reopened)
        restarted.reportFound("crash_report", "private-crash-id")
        assertEquals(1, reopened.eventCount())
        selected = secondProfile
        available = secondDestination
        restarted.reportFound("crash_report", "private-crash-id")
        assertTrue(reopened.unsentDiscoveries(secondDestination.owner).isEmpty())
        assertEquals(0, discoveries)

        selected = firstProfile
        available = firstDestination.copy(source = "https://first.example/replaced")
        restarted.reportFound("crash_report", "private-crash-id")
        assertEquals(0, discoveries)
        available = firstDestination
        restarted.reportFound("crash_report", "private-crash-id")
        restarted.reportFound("crash_report", "private-crash-id")

        assertEquals(1, reopened.eventCount())
        assertEquals(1, discoveries)
        assertEquals(setOf(DiagnosticsStore.crashKey("crash_report", "private-crash-id")), reopened.unsentDiscoveries(firstDestination.owner))
        assertEquals(1, reopened.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList(), firstDestination.association)!!.envelope.array("events").size)
        assertFalse(directory.walkTopDown().filter { it.isFile }.any { it.readText().contains("https://") || it.readText().contains("private-crash-id") })
    }

    @Test
    fun capabilityGainPromotesOnlyMatchingAssociationAndNeverReassignsDestinationOwnedEvents() {
        val directory = temporary.newFolder()
        val initial = DiagnosticsStore(directory)
        val writes = ArrayDeque<Runnable>()
        var selected = firstProfile
        val journal = DiagnosticsOwnership({ initial }, Executor { writes.addLast(it) }, { selected }, { null })
        journal.preferencesReady()
        journal.record("vpn_start", mapOf("reason" to "User"))
        selected = secondProfile
        journal.record("core_ready", emptyMap())
        val changedProfile = firstProfile.copy(source = "https://first.example/replaced")
        selected = changedProfile
        journal.record("vpn_stop", mapOf("reason" to "User"))
        journal.record("vpn_start_failed", emptyMap(), DiagnosticsProfile(firstProfile.id, null))
        while (writes.isNotEmpty()) writes.removeFirst().run()

        val reopened = DiagnosticsStore(directory)
        assertEquals(4, reopened.eventCount())
        val first = reopened.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList(), firstDestination.association)!!
        assertEquals(listOf("vpn_start"), first.envelope.array("events").map { it.jsonObject.string("kind") })
        val changedDestination = firstDestination.copy(source = changedProfile.source!!)
        val changed = reopened.prepare(changedDestination.owner, JsonObject(emptyMap()), null, emptyList(), changedDestination.association)!!
        assertEquals(listOf("vpn_stop"), changed.envelope.array("events").map { it.jsonObject.string("kind") })
        val second = reopened.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList(), secondDestination.association)!!
        assertEquals(listOf("core_ready"), second.envelope.array("events").map { it.jsonObject.string("kind") })
        val replacement = firstDestination.copy(url = "https://first.example/new-report")
        assertTrue(reopened.prepare(replacement.owner, JsonObject(emptyMap()), null, emptyList(), replacement.association)!!.envelope.array("events").isEmpty())
        val restarted = DiagnosticsStore(directory)
        assertEquals(first.body, restarted.pending(firstDestination.owner)!!.body)
        assertEquals(DiagnosticsStore.Outcome.ACKNOWLEDGED, restarted.respond(firstDestination.owner, first.id, 200))
        assertEquals(3, restarted.eventCount())
        assertFalse(restarted.eventsFile.readText().contains("https://"))
    }

    @Test
    fun rejectingWriterNeverRunsStorageOnLifecycleCallerAndRetriesCapturedAssociation() {
        val store = DiagnosticsStore(temporary.newFolder())
        val lifecycleThread = Thread.currentThread()
        val storageThreads = mutableListOf<Thread>()
        val writes = ArrayDeque<Runnable>()
        var rejecting = true
        var selected = firstProfile
        val journal = DiagnosticsOwnership(
            {
                storageThreads += Thread.currentThread()
                store
            },
            Executor {
                if (rejecting) throw java.util.concurrent.RejectedExecutionException("saturated")
                writes.addLast(it)
            },
            { selected },
            { if (it == firstProfile) firstDestination else secondDestination },
        )
        journal.record("process_start", emptyMap())
        journal.preferencesReady()
        journal.record("core_ready", emptyMap())
        journal.reportFound("oom_report", "oom-1")
        journal.reportFound("oom_report", "oom-1")
        assertFalse(storageThreads.contains(lifecycleThread))
        assertEquals(0, store.eventCount())

        selected = secondProfile
        rejecting = false
        journal.retry()
        val worker = Thread { while (writes.isNotEmpty()) writes.removeFirst().run() }
        worker.start()
        worker.join()

        assertFalse(storageThreads.contains(lifecycleThread))
        val first = store.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("process_start", "core_ready", "oom_report"), first.envelope.array("events").map { it.jsonObject.string("kind") })
        assertTrue(store.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList())!!.envelope.array("events").isEmpty())
    }

    @Test
    fun writerDispatchFailuresNeverEscapeLifecycleCallsAndRemainRetryable() {
        val store = DiagnosticsStore(temporary.newFolder())
        var failing = true
        val writes = ArrayDeque<Runnable>()
        val journal = DiagnosticsOwnership(
            { store },
            Executor {
                if (failing) throw IllegalStateException("writer unavailable")
                writes.addLast(it)
            },
            { firstProfile },
            { firstDestination },
        )
        journal.record("process_start", emptyMap())
        journal.preferencesReady()
        journal.record("core_ready", emptyMap())
        journal.reportFound("crash_report", "crash-1")
        journal.retry()
        assertEquals(0, store.eventCount())

        failing = false
        journal.retry()
        while (writes.isNotEmpty()) writes.removeFirst().run()

        val batch = store.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("process_start", "core_ready", "crash_report"), batch.envelope.array("events").map { it.jsonObject.string("kind") })
    }

    @Test
    fun queuedColdCaptureRetainsOriginalSourceWhenProfileChangesBeforeStorageRuns() {
        val store = DiagnosticsStore(temporary.newFolder())
        val access = Any()
        var source = firstProfile.source
        val sources = DiagnosticsSources(access) { source }
        val replacementSource = "https://first.example/replaced"
        val replacementDestination = firstDestination.copy(source = replacementSource)
        val writes = ArrayDeque<Runnable>()
        val journal = DiagnosticsOwnership(
            { store },
            Executor { writes.addLast(it) },
            { DiagnosticsProfile(firstProfile.id, null, sources.capture(firstProfile.id)) },
            { if (it.source == firstProfile.source) firstDestination else replacementDestination },
        )
        journal.preferencesReady()
        journal.record("process_start", emptyMap())
        journal.reportFound("crash_report", "crash-1")

        synchronized(access) {
            sources.changing(firstProfile.id)
            source = replacementSource
        }
        journal.record("core_ready", emptyMap())
        while (writes.isNotEmpty()) writes.removeFirst().run()

        val first = store.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("process_start", "crash_report"), first.envelope.array("events").map { it.jsonObject.string("kind") })
        val replacement = store.prepare(replacementDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("core_ready"), replacement.envelope.array("events").map { it.jsonObject.string("kind") })
        assertTrue(store.unsentDiscoveries(replacementDestination.owner).isEmpty())
    }

    @Test
    fun lifecycleCaptureDoesNotWaitForProfileStorageLockOrResolveSource() {
        val store = DiagnosticsStore(temporary.newFolder())
        val access = Any()
        var reads = 0
        val sources = DiagnosticsSources(access) {
            reads++
            firstProfile.source
        }
        val writes = ArrayDeque<Runnable>()
        val journal = DiagnosticsOwnership(
            { store },
            Executor { writes.addLast(it) },
            { DiagnosticsProfile(firstProfile.id, null, sources.capture(firstProfile.id)) },
            { firstDestination },
        )
        journal.preferencesReady()
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = CountDownLatch(1)
        val storage = Thread {
            synchronized(access) {
                locked.countDown()
                release.await()
            }
        }
        val lifecycle = Thread {
            journal.record("core_ready", emptyMap())
            captured.countDown()
        }
        storage.start()
        var capturedWithoutStorage = false
        try {
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            lifecycle.start()
            capturedWithoutStorage = captured.await(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            storage.join()
            lifecycle.join()
        }
        assertTrue(capturedWithoutStorage)
        assertEquals(0, reads)

        while (writes.isNotEmpty()) writes.removeFirst().run()
        assertEquals(1, reads)
        assertEquals(1, store.eventCount())
    }

    @Test
    fun failedStorageRemainsQueuedWithoutResolvingLaterProfileSelection() {
        val store = DiagnosticsStore(temporary.newFolder())
        var failing = true
        var selected = firstProfile
        val journal = DiagnosticsOwnership(
            { if (failing) throw IllegalStateException("storage unavailable") else store },
            Executor { it.run() },
            { selected },
            { if (it == firstProfile) firstDestination else secondDestination },
        )
        journal.preferencesReady()
        journal.record("core_ready", emptyMap())
        journal.reportFound("oom_report", "oom-1")
        assertTrue(journal.hasPending())
        assertEquals(0, store.eventCount())

        selected = secondProfile
        failing = false
        journal.retry()

        assertFalse(journal.hasPending())
        val first = store.prepare(firstDestination.owner, JsonObject(emptyMap()), null, emptyList())!!
        assertEquals(listOf("core_ready", "oom_report"), first.envelope.array("events").map { it.jsonObject.string("kind") })
        assertTrue(store.prepare(secondDestination.owner, JsonObject(emptyMap()), null, emptyList())!!.envelope.array("events").isEmpty())
    }

    @Test
    fun existingDiscoveryWithoutObservationMetadataDoesNotCreateAnotherEvent() {
        val directory = temporary.newFolder()
        val store = DiagnosticsStore(directory)
        store.record(firstDestination.owner, DiagnosticsProjection.event("crash_report", emptyMap()))
        store.discover(firstDestination.owner, "crash_report", "legacy-crash")
        val reopened = DiagnosticsStore(directory)
        val journal = DiagnosticsOwnership(
            { reopened },
            Executor { it.run() },
            { firstProfile },
            { firstDestination },
        )
        journal.preferencesReady()
        journal.reportFound("crash_report", "legacy-crash")

        assertEquals(1, reopened.eventCount())
        assertEquals(setOf(DiagnosticsStore.crashKey("crash_report", "legacy-crash")), reopened.unsentDiscoveries(firstDestination.owner))
    }
}
