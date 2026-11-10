package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.AppScanRegion
import io.nekohasekai.sfa.utils.shouldPublishManagedScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object ManagedAppScan {
    private val mutex = Mutex()

    suspend fun update(regions: Set<AppScanRegion>? = null, enableManagedMode: Boolean = false) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (regions == null && (!Settings.perAppProxyEnabled || !Settings.perAppProxyManagedMode)) return@withContext
            val snapshot = regions ?: AppScanRegion.fromSettings(Settings.perAppProxyManagedRegions)
            val managedApps = PerAppProxyScanner.scanAllManagedApps(snapshot)
            ensureActive()
            if (regions == null && !shouldPublishManagedScan(
                    snapshot,
                    AppScanRegion.fromSettings(Settings.perAppProxyManagedRegions),
                    Settings.perAppProxyEnabled && Settings.perAppProxyManagedMode,
                )
            ) {
                return@withContext
            }
            // Publish only completed scans, without a cancellation point between the list and its regions.
            Settings.perAppProxyManagedList = managedApps
            Settings.perAppProxyManagedRegions = snapshot.map { it.name }.toSet()
            if (enableManagedMode) Settings.perAppProxyManagedMode = true
        }
    }
}
