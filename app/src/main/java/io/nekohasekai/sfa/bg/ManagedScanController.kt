package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.utils.AppScanRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ManagedScanState(
    val regions: Set<AppScanRegion>,
    val isScanning: Boolean = false,
    val requestedRevision: Long = 0,
    val appliedRevision: Long = 0,
    val appliedRegions: Set<AppScanRegion> = emptySet(),
    val bypassedAppCount: Int = 0,
    val failure: Exception? = null,
) {
    val status: ManagedScanStatus
        get() =
            when {
                regions.isEmpty() -> ManagedScanStatus.Off
                isScanning -> ManagedScanStatus.Scanning
                failure != null -> ManagedScanStatus.Failed
                appliedRevision == requestedRevision && appliedRegions == regions -> ManagedScanStatus.Applied
                else -> ManagedScanStatus.Scanning
            }
}

internal enum class ManagedScanStatus {
    Off,
    Scanning,
    Applied,
    Failed,
}

// Requests and publication are confined to the scope's dispatcher; scanning may switch dispatchers.
internal class ManagedScanController(
    private val scope: CoroutineScope,
    initialRegions: Set<AppScanRegion>,
    initialAppliedRegions: Set<AppScanRegion> = emptySet(),
    initialAppCount: Int = 0,
    private val saveSelection: (Set<AppScanRegion>) -> Unit,
    private val scan: suspend (Set<AppScanRegion>) -> Set<String>,
    private val publish: (Set<AppScanRegion>, Set<String>) -> Unit,
) {
    private val mutex = Mutex()
    private val mutableState =
        MutableStateFlow(
            ManagedScanState(initialRegions, appliedRegions = initialAppliedRegions, bypassedAppCount = initialAppCount),
        )
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var job: Job? = null

    fun select(regions: Set<AppScanRegion>): Job {
        val snapshot = regions.toSet()
        val request = ++revision
        job?.cancel()
        saveSelection(snapshot)
        mutableState.value =
            mutableState.value.copy(
                regions = snapshot,
                requestedRevision = request,
                isScanning = snapshot.isNotEmpty(),
                failure = null,
            )
        return scope
            .launch {
                try {
                    if (snapshot.isEmpty()) {
                        publish(snapshot, emptySet())
                        mutableState.value = mutableState.value.copy(appliedRegions = snapshot, bypassedAppCount = 0)
                    } else {
                        mutex.withLock {
                            val apps = scan(snapshot)
                            ensureActive()
                            if (request != revision) return@withLock
                            publish(snapshot, apps)
                            mutableState.value = mutableState.value.copy(appliedRegions = snapshot, bypassedAppCount = apps.size)
                        }
                    }
                    if (request == revision) {
                        mutableState.value = mutableState.value.copy(isScanning = false, appliedRevision = request)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    if (request == revision) {
                        mutableState.value = mutableState.value.copy(isScanning = false, failure = exception)
                    }
                }
            }.also { job = it }
    }
}
