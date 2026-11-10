package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.utils.AppScanRegion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedScanControllerTest {
    private val china = setOf(AppScanRegion.China)
    private val russia = setOf(AppScanRegion.Russia)

    @Test
    fun selectionUpdatesBeforeScanAndOnlyLatestResultPublishes() = runBlocking {
        val releaseOld = CompletableDeferred<Unit>()
        val published = mutableListOf<Set<AppScanRegion>>()
        val saved = mutableListOf<Set<AppScanRegion>>()
        var activeScans = 0
        var maxScans = 0
        val controller = ManagedScanController(
            scope = this,
            initialRegions = emptySet(),
            saveSelection = { saved.add(it) },
            scan = { regions ->
                activeScans++
                maxScans = maxOf(maxScans, activeScans)
                try {
                    if (regions == china) withContext(NonCancellable) { releaseOld.await() }
                    setOf(regions.single().name)
                } finally {
                    activeScans--
                }
            },
            publish = { regions, _ -> published.add(regions) },
        )
        val old = controller.select(china)
        assertEquals(china, controller.state.value.regions)
        assertTrue(controller.state.value.isScanning)
        yield()
        val latest = controller.select(russia)
        assertEquals(russia, controller.state.value.regions)
        assertEquals(listOf(china, russia), saved)
        assertTrue(published.isEmpty())
        releaseOld.complete(Unit)
        old.join()
        latest.join()
        assertEquals(listOf(russia), published)
        assertFalse(controller.state.value.isScanning)
        assertEquals(1, maxScans)
    }

    @Test
    fun clearingSelectionDisablesImmediatelyAndSupersedesAnUnfinishedScan() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<Set<AppScanRegion>>()
        val controller = ManagedScanController(
            scope = this,
            initialRegions = china,
            saveSelection = {},
            scan = {
                withContext(NonCancellable) { release.await() }
                setOf("old.app")
            },
            publish = { regions, _ -> published.add(regions) },
        )
        val old = controller.select(russia)
        yield()
        controller.select(emptySet()).join()
        assertTrue(controller.state.value.regions.isEmpty())
        assertFalse(controller.state.value.isScanning)
        assertEquals(listOf(emptySet<AppScanRegion>()), published)
        release.complete(Unit)
        old.join()
        assertEquals(listOf(emptySet<AppScanRegion>()), published)
    }

    @Test
    fun returningToTheOriginalSelectionStillSupersedesItsOldScan() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val published = mutableListOf<Set<String>>()
        var scans = 0
        val controller = ManagedScanController(
            scope = this,
            initialRegions = emptySet(),
            saveSelection = {},
            scan = {
                val scanNumber = ++scans
                if (scanNumber == 1) withContext(NonCancellable) { release.await() }
                setOf("scan.$scanNumber")
            },
            publish = { _, apps -> published.add(apps) },
        )
        val first = controller.select(china)
        yield()
        controller.select(russia)
        val latest = controller.select(china)
        release.complete(Unit)
        first.join()
        latest.join()
        assertEquals(listOf(setOf("scan.2")), published)
    }

    @Test
    fun failedScanKeepsTheEffectiveListAndAllowsAnotherSelection() = runBlocking {
        var effectiveApps = setOf("previous.app")
        val controller = ManagedScanController(
            scope = this,
            initialRegions = china,
            saveSelection = {},
            scan = { regions ->
                if (regions == russia) error("scan failed")
                setOf("new.app")
            },
            publish = { _, apps -> effectiveApps = apps },
        )
        controller.select(russia).join()
        assertEquals(setOf("previous.app"), effectiveApps)
        assertEquals("scan failed", controller.state.value.failure?.message)
        assertFalse(controller.state.value.isScanning)
        controller.select(china).join()
        assertEquals(setOf("new.app"), effectiveApps)
        assertEquals(null, controller.state.value.failure)
    }
}
