package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.bg.CrashReportManager
import io.nekohasekai.sfa.bg.OOMReportManager
import io.nekohasekai.sfa.bg.PowerReportManager

internal class DiagnosticsReportDiscovery(
    private val refreshReports: suspend () -> Unit = {
        CrashReportManager.refresh()
        OOMReportManager.refresh()
        PowerReportManager.refresh()
    },
    private val flushJournal: () -> Unit = DiagnosticsJournal::flush,
) {
    companion object {
        val shared = DiagnosticsReportDiscovery()
    }

    suspend fun refresh() {
        refreshReports()
        // Report observations are queued; persist them before any unsent-discovery lookup.
        flushJournal()
    }

    suspend fun afterPromotion(promote: () -> Unit) {
        promote()
        refresh()
    }

    suspend fun unsent(store: DiagnosticsStore, owner: String): Set<String> {
        refresh()
        return store.unsentDiscoveries(owner)
    }
}
