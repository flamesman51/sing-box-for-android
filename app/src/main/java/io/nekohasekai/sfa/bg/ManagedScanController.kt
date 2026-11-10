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
    val appliedRevision: Long = 0,
    val failure: Exception? = null,
)

// Requests and publication are confined to the scope's dispatcher; scanning may switch dispatchers.
internal class ManagedScanController(
    private val scope: CoroutineScope,
    initialRegions: Set<AppScanRegion>,
    private val saveSelection: (Set<AppScanRegion>) -> Unit,
    private val scan: suspend (Set<AppScanRegion>) -> Set<String>,
    private val publish: (Set<AppScanRegion>, Set<String>) -> Unit,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(ManagedScanState(initialRegions))
    val state = mutableState.asStateFlow()
    private var revision = 0L
    private var job: Job? = null

    fun select(regions: Set<AppScanRegion>): Job {
        val snapshot = regions.toSet()
        val request = ++revision
        job?.cancel()
        saveSelection(snapshot)
        mutableState.value = mutableState.value.copy(regions = snapshot, isScanning = snapshot.isNotEmpty(), failure = null)
        return scope.launch {
            try {
                if (snapshot.isEmpty()) {
                    publish(snapshot, emptySet())
                } else {
                    mutex.withLock {
                        val apps = scan(snapshot)
                        ensureActive()
                        if (request != revision) return@withLock
                        publish(snapshot, apps)
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
