package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.array
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection.string
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticsDiscoveryTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var workingDirectory: File
    private lateinit var store: DiagnosticsStore
    private lateinit var journal: DiagnosticsOwnership
    private lateinit var discovery: DiagnosticsReportDiscovery
    private val writes = ArrayDeque<Runnable>()
    private val profile = DiagnosticsProfile(1, "https://profile.example/subscription")
    private val destination = DiagnosticsDestination(1, profile.source!!, "https://profile.example/report")
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var enqueued = 0

    @Before
    fun installReportManagers() {
        workingDirectory = temporary.newFolder("working")
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        CrashReportManager.install(workingDirectory, temporary.newFolder("base"))
        OOMReportManager.install(workingDirectory)
        PowerReportManager.install(workingDirectory)
        store = DiagnosticsStore(temporary.newFolder("journal"))
        journal = DiagnosticsOwnership(
            { store },
            Executor { writes.addLast(it) },
            { profile },
            { destination },
            { enqueued++ },
        )
        journal.preferencesReady()
        discovery = DiagnosticsReportDiscovery(
            {
                CrashReportManager.refresh(journal::reportFound)
                OOMReportManager.refresh(journal::reportFound)
                PowerReportManager.refresh(journal::reportFound)
            },
            { while (writes.isNotEmpty()) writes.removeFirst().run() },
        )
    }

    @After
    fun restoreCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun nativeCrashArchivedOnlyDuringSetupIsDiscoveredAndEnqueued() = runBlocking {
        discovery.refresh()
        assertTrue(CrashReportManager.reports.value.isEmpty())
        val pending = File(workingDirectory, "CrashReport-Application.log").apply {
            writeText("runtime.panic(0x1234)\n")
        }

        discovery.afterPromotion {
            val archived = File(workingDirectory, "crash_reports/2026-11-10T10-00-00")
            assertTrue(archived.mkdirs())
            assertTrue(pending.renameTo(File(archived, "go.log")))
        }?.join()

        val key = DiagnosticsStore.crashKey("crash_report", "2026-11-10T10-00-00")
        assertFalse(pending.exists())
        assertEquals(setOf(key), store.unsentDiscoveries(destination.owner))
        assertEquals(1, enqueued)
        val crashes = DiagnosticsSnapshot.crashes(store.unsentDiscoveries(destination.owner))
        assertEquals(listOf(key), crashes.map { it.key })
        assertEquals("crash_report", crashes.single().report.string("kind"))
    }

    @Test
    fun oomDraftPromotedAtServiceStartIsDiscoveredAndEnqueued() = runBlocking {
        discovery.refresh()
        assertTrue(OOMReportManager.reports.value.isEmpty())
        val draft = File(workingDirectory, "oom_draft").apply { mkdirs() }
        File(draft, "go.log").writeText("runtime.throw(0x1234)\n")

        discovery.afterPromotion {
            val reports = File(workingDirectory, "oom_reports").apply { mkdirs() }
            assertTrue(draft.renameTo(File(reports, "2026-11-10T11-00-00")))
        }?.join()

        assertEquals(
            setOf(DiagnosticsStore.crashKey("oom_report", "2026-11-10T11-00-00")),
            store.unsentDiscoveries(destination.owner),
        )
        assertEquals(1, enqueued)
    }

    @Test
    fun periodicScanFindsReportsFromEmptyUnsentSetAndFlushesBeforeLookup() = runBlocking {
        assertTrue(store.unsentDiscoveries(destination.owner).isEmpty())
        val report = File(workingDirectory, "crash_reports/2026-11-10T12-00-00").apply { mkdirs() }
        File(report, "go.log").writeText("runtime.panic(0x1234)\n")

        val unsent = discovery.unsent(store, destination.owner)

        val key = DiagnosticsStore.crashKey("crash_report", "2026-11-10T12-00-00")
        assertEquals(setOf(key), unsent)
        assertTrue(writes.isEmpty())
        assertEquals(1, enqueued)
        val batch = store.prepare(destination.owner, JsonObject(emptyMap()), null, DiagnosticsSnapshot.crashes(unsent), association = destination.association)!!
        assertEquals("crash_report", batch.envelope.array("events").single().jsonObject.string("kind"))
        assertEquals(1, batch.envelope.array("crashes").size)
        assertTrue(store.unsentDiscoveries("unrelated-owner").isEmpty())
    }

    @Test
    fun repeatedRefreshDoesNotEnqueueAcknowledgedReportsAgain() = runBlocking {
        File(workingDirectory, "crash_reports/2026-11-10T13-00-00").mkdirs()
        val unsent = discovery.unsent(store, destination.owner)
        val batch = store.prepare(destination.owner, JsonObject(emptyMap()), null, DiagnosticsSnapshot.crashes(unsent), association = destination.association)!!
        assertEquals(DiagnosticsStore.Outcome.ACKNOWLEDGED, store.respond(destination.owner, batch.id, 204))

        assertTrue(discovery.unsent(store, destination.owner).isEmpty())
        assertEquals(1, enqueued)
    }

    @Test
    fun rejectedDiagnosticsFlushDoesNotFailPromotionOrStartupContinuation() {
        assertFlushFailureDoesNotFailStartup(RejectedExecutionException("diagnostics writer rejected"))
    }

    @Test
    fun timedOutDiagnosticsFlushDoesNotFailPromotionOrStartupContinuation() {
        assertFlushFailureDoesNotFailStartup(TimeoutException("diagnostics writer timed out"))
    }

    @Test
    fun startupContinuationDoesNotWaitForBlockedDiagnosticsScan() {
        val scanStarted = CountDownLatch(1)
        val releaseScan = CountDownLatch(1)
        val scanFinished = CountDownLatch(1)
        val continued = CountDownLatch(1)
        val startup = Executors.newSingleThreadExecutor()
        val blockedDiscovery = DiagnosticsReportDiscovery(
            {
                scanStarted.countDown()
                try {
                    assertTrue("Test did not release the diagnostics scan", releaseScan.await(30, TimeUnit.SECONDS))
                } finally {
                    scanFinished.countDown()
                }
            },
            {},
        )
        val result = startup.submit {
            runBlocking {
                blockedDiscovery.afterPromotion {}
                continued.countDown()
            }
        }
        try {
            assertTrue("Diagnostics scan did not start", scanStarted.await(5, TimeUnit.SECONDS))
            assertTrue("Diagnostics scan blocked startup continuation", continued.await(5, TimeUnit.SECONDS))
        } finally {
            releaseScan.countDown()
            try {
                result.get(5, TimeUnit.SECONDS)
                assertTrue("Diagnostics scan did not finish", scanFinished.await(5, TimeUnit.SECONDS))
            } finally {
                startup.shutdownNow()
            }
        }
    }

    @Test
    fun actualPromotionFailureStillPropagatesWithoutScanningDiagnostics() {
        val failure = IllegalStateException("native promotion failed")
        var scanned = false
        val failingDiscovery = DiagnosticsReportDiscovery({ scanned = true }, {})

        val observed = runCatching {
            runBlocking { failingDiscovery.afterPromotion { throw failure } }
        }.exceptionOrNull()

        assertSame(failure, observed)
        assertFalse(scanned)
    }

    private fun assertFlushFailureDoesNotFailStartup(failure: Exception) {
        val flushed = CountDownLatch(1)
        val flushAttempts = AtomicInteger()
        var promoted = false
        var continued = false
        val failingDiscovery = DiagnosticsReportDiscovery(
            {},
            {
                if (flushAttempts.incrementAndGet() == 1) {
                    flushed.countDown()
                    throw failure
                }
            },
        )

        runBlocking {
            failingDiscovery.afterPromotion { promoted = true }
            continued = true
        }

        assertTrue(promoted)
        assertTrue(continued)
        assertTrue("Diagnostics flush was not attempted", flushed.await(5, TimeUnit.SECONDS))

        runBlocking {
            assertTrue(failingDiscovery.unsent(store, destination.owner).isEmpty())
        }
        assertEquals(2, flushAttempts.get())
    }
}
