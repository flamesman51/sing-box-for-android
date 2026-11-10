package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class DiagnosticsReportDiscovery(
    private val refreshReports: suspend () -> Unit = {
        CrashReportManager.refresh()
        OOMReportManager.refresh()
        PowerReportManager.refresh()
    },
    private val flushJournal: () -> Unit = DiagnosticsJournal::flush,
) {
    companion object {
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shared = DiagnosticsReportDiscovery()
    }

    suspend fun refresh() {
        refreshReports()
        // Report observations are queued; persist them before any unsent-discovery lookup.
        flushJournal()
    }

    fun scheduleRefresh() = try {
        background.launch {
            // Upload discovery retries refresh/flush failures without holding up the lifecycle.
            runCatching { refresh() }
        }
    } catch (_: Throwable) {
        null
    }

    fun afterPromotion(promote: () -> Unit): Job? {
        promote()
        return scheduleRefresh()
    }

    suspend fun unsent(store: DiagnosticsStore, owner: String): Set<String> {
        refresh()
        return store.unsentDiscoveries(owner)
    }
}
