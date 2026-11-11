package io.nekohasekai.sfa.bg

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReportManagerConcurrencyTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var workingDirectory: File
    private lateinit var baseDirectory: File
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val timestamps = mapOf(
        "1970-01-01T00-00-00" to 0L,
        "2000-01-01T00-00-00" to 946684800000L,
        "2000-02-29T23-59-58" to 951868798000L,
        "2024-01-01T00-00-00" to 1704067200000L,
        "2024-01-02T03-04-05" to 1704164645000L,
    )

    @Before
    fun installReportManagers() {
        workingDirectory = temporary.newFolder("working")
        baseDirectory = temporary.newFolder("base")
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        CrashReportManager.install(workingDirectory, baseDirectory)
        OOMReportManager.install(workingDirectory)
        PowerReportManager.install(workingDirectory)
    }

    @After
    fun restoreCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    @Test
    fun concurrentCrashRefreshPreservesExactTimestampsAndSkipsMalformedDirectories() {
        val expected = createReports("crash_reports")
        assertConcurrentRefresh("crash_report", expected, { CrashReportManager.refresh(it) }) {
            CrashReportManager.reports.value.map { it.id to it.date.time }
        }
        assertEquals(expected.size, CrashReportManager.unreadCount.value)
    }

    @Test
    fun concurrentOomRefreshPreservesExactTimestampsAndSkipsMalformedDirectories() {
        val expected = createReports("oom_reports")
        assertConcurrentRefresh("oom_report", expected, { OOMReportManager.refresh(it) }) {
            OOMReportManager.reports.value.map { it.id to it.date.time }
        }
        assertEquals(expected.size, OOMReportManager.unreadCount.value)
    }

    @Test
    fun concurrentPowerRefreshPreservesExactTimestampsAndSkipsMalformedDirectories() {
        val expected = createReports("power_reports")
        assertConcurrentRefresh("power_report", expected, { PowerReportManager.refresh(it) }) {
            PowerReportManager.reports.value.map { it.id to it.date.time }
        }
        assertEquals(expected.size, PowerReportManager.unreadCount.value)
    }

    @Test
    fun archivingJvmCrashDuringRefreshPreservesDirectoryTimestampAndCollisionSuffixes() {
        val expected = createReports("crash_reports").toMutableMap()
        val archivedTimestamp = "2000-02-29T23-59-58"
        val archivedMillis = timestamps.getValue(archivedTimestamp)
        val pending = File(workingDirectory, "CrashReport-JVM.log")

        concurrently { worker ->
            if (worker == 0) {
                repeat(32) { index ->
                    pending.writeText("JVM crash $index")
                    assertTrue(pending.setLastModified(archivedMillis))
                    CrashReportManager.install(workingDirectory, baseDirectory)
                    assertFalse(pending.exists())
                    Thread.setDefaultUncaughtExceptionHandler(previousHandler)
                }
            } else {
                repeat(64) {
                    runBlocking { CrashReportManager.refresh { _, _ -> } }
                    CrashReportManager.reports.value.forEach { report ->
                        val baseName = report.id.take(19)
                        assertEquals(report.id, timestamps.getValue(baseName), report.date.time)
                    }
                }
            }
        }

        (2..33).forEach { index -> expected["$archivedTimestamp-$index"] = archivedMillis }
        runBlocking { CrashReportManager.refresh { _, _ -> } }
        val reports = CrashReportManager.reports.value
        assertEquals(expected, reports.associate { it.id to it.date.time })
        assertEquals(32, reports.count { File(it.directory, "jvm.log").isFile })
    }

    private fun createReports(directoryName: String): Map<String, Long> {
        val expected = timestamps.flatMap { (name, millis) ->
            listOf(name to millis, "$name-1" to millis, "$name-42" to millis)
        }.toMap()
        expected.keys.forEach { name ->
            assertTrue(File(workingDirectory, "$directoryName/$name").mkdirs())
        }
        listOf(
            "not-a-report",
            "2026-11-noT12-00-00",
            "2026-11-10Tno-00-00",
            "2026-11-10T12-00-no",
        ).forEach { name -> assertTrue(File(workingDirectory, "$directoryName/$name").mkdirs()) }
        File(workingDirectory, "$directoryName/2025-01-01T00-00-00").writeText("not a directory")
        return expected
    }

    private fun assertConcurrentRefresh(
        kind: String,
        expected: Map<String, Long>,
        refresh: suspend ((String, String) -> Unit) -> Unit,
        reports: () -> List<Pair<String, Long>>,
    ) {
        concurrently {
            repeat(64) {
                val discoveries = mutableListOf<Pair<String, String>>()
                runBlocking { refresh { reportKind, id -> discoveries.add(reportKind to id) } }
                assertEquals(expected.keys.map { kind to it }.toSet(), discoveries.toSet())
                assertEquals(expected.size, discoveries.size)
                val actual = reports()
                assertEquals(expected, actual.toMap())
                assertEquals(expected.size, actual.size)
                assertEquals(actual.map { it.second }.sortedDescending(), actual.map { it.second })
            }
        }
    }

    private fun concurrently(action: (Int) -> Unit) {
        val workers = 8
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CyclicBarrier(workers)
        val futures = (0 until workers).map { worker ->
            executor.submit {
                ready.await(10, TimeUnit.SECONDS)
                action(worker)
            }
        }
        try {
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
