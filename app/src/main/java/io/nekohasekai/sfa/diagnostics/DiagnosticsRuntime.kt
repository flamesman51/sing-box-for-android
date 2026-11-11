package io.nekohasekai.sfa.diagnostics

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class DiagnosticsRuntime(
    val sampledAt: Long,
    val appProcessUptimeMillis: Long?,
    val memoryBytes: Long? = null,
    val goroutines: Int? = null,
    val serviceUptimeMillis: Long? = null,
) {
    fun report(): JsonObject = buildJsonObject {
        put("sampled_at", DiagnosticsProjection.utc(sampledAt))
        appProcessUptimeMillis?.let { put("app_process_uptime_ms", it) }
        memoryBytes?.let { put("memory_bytes", it) }
        goroutines?.let { put("goroutines", it) }
        serviceUptimeMillis?.let { put("service_uptime_ms", it) }
    }
}
