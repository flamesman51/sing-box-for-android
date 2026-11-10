package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppScanRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

object ManagedAppScan {
    private val controller by lazy {
        ManagedScanController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            initialRegions = selectedRegions(),
            initialAppliedRegions = if (Settings.perAppProxyManagedMode) AppScanRegion.fromSettings(Settings.perAppProxyManagedRegions) else emptySet(),
            initialAppCount = Settings.perAppProxyManagedList.size,
            saveSelection = { regions -> Settings.perAppProxySelectedRegions = regions.map { it.name }.toSet() },
            scan = { regions -> PerAppProxyScanner.scanAllManagedApps(regions) },
            publish = { regions, apps ->
                // Keep the effective routing snapshot intact until a replacement scan succeeds.
                if (regions.isNotEmpty()) {
                    Settings.perAppProxyManagedList = apps
                    Settings.perAppProxyManagedRegions = regions.map { it.name }.toSet()
                }
                Settings.perAppProxyManagedMode = regions.isNotEmpty()
            },
        )
    }

    internal val state get() = controller.state

    private fun selectedRegions(): Set<AppScanRegion> = AppScanRegion.fromSettings(Settings.perAppProxySelectedRegions)

    fun select(regions: Set<AppScanRegion>) = controller.select(regions)

    suspend fun update() = withContext(Dispatchers.Main.immediate) {
        if (!Settings.perAppProxyEnabled || controller.state.value.regions.isEmpty()) return@withContext
        controller.select(controller.state.value.regions).join()
    }
}
