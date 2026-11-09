package io.nekohasekai.sfa.compose.screen.smart

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SmartStatus(
    val version: Int,
    val group: String,
    val state: String,
    val now: Long,
    val generation: Long,
    val selected: SmartSelected,
    val network: SmartNetwork,
    val direct: SmartDirect,
    val budget: SmartBudget,
    val members: List<SmartMember>,
    val events: List<SmartEvent>,
    val timeline: SmartTimeline,
)

@Serializable
data class SmartSelected(
    val tcp: String?,
    val standby: String?,
    val udp: String?,
    val udp443: SmartUdp443,
)

@Serializable
data class SmartUdp443(val mode: String, val member: String?, val reason: String)

@Serializable
data class SmartNetwork(
    val key: String,
    val type: String,
    val label: String?,
    val carrier: String?,
    val radio: String?,
    val country: String?,
)

@Serializable
data class SmartDirect(
    val state: String,
    @SerialName("checked_at") val checkedAt: Long?,
    @SerialName("rtt_ms") val rttMs: Double?,
    @SerialName("download_mbps") val downloadMbps: Double?,
)

@Serializable
data class SmartBudget(
    @SerialName("network_type") val networkType: String,
    val day: String,
    @SerialName("used_mb") val usedMb: Double,
    @SerialName("reserved_mb") val reservedMb: Double,
    @SerialName("limit_mb") val limitMb: Double,
    @SerialName("active_tests") val activeTests: Boolean,
)

@Serializable
data class SmartMember(
    val tag: String,
    val tier: Int,
    val country: String?,
    val state: String,
    val probation: Int,
    @SerialName("quarantine_until") val quarantineUntil: Long?,
    val tcp: String,
    val udp: String,
    val udp443: String,
    @SerialName("rtt_ms") val rttMs: Double?,
    @SerialName("rtt_p90_ms") val rttP90Ms: Double?,
    val stability: Double?,
    @SerialName("stability_class") val stabilityClass: String?,
    val weight: Double,
    @SerialName("download_mbps") val downloadMbps: Double?,
    @SerialName("upload_mbps") val uploadMbps: Double?,
    @SerialName("throughput_class") val throughputClass: Int,
    @SerialName("throughput_source") val throughputSource: String?,
    @SerialName("throughput_at") val throughputAt: Long?,
    @SerialName("last_ok") val lastOk: Long?,
    @SerialName("last_fail") val lastFail: Long?,
    val rank: Int,
)

@Serializable
data class SmartEvent(
    val time: Long,
    val kind: String,
    val from: String?,
    val to: String?,
    val network: String?,
    val reason: String,
)

@Serializable
data class SmartTimeline(
    @SerialName("bucket_seconds") val bucketSeconds: Int,
    val start: Long,
    val members: Map<String, List<List<Double?>>>,
)

@Serializable
data class SmartNetworks(val version: Int, val networks: List<SmartNetworkHistory>)

@Serializable
data class SmartNetworkHistory(
    val key: String,
    val type: String,
    val label: String?,
    val carrier: String?,
    val radio: String?,
    @SerialName("last_seen") val lastSeen: Long,
    val weight: Double,
    val members: List<SmartNetworkMember>,
)

@Serializable
data class SmartNetworkMember(
    val tag: String,
    val stability: Double?,
    val weight: Double,
    @SerialName("download_mbps") val downloadMbps: Double?,
    @SerialName("rtt_ms") val rttMs: Double?,
)

@Serializable
data class SmartImportReport(
    val version: Int,
    @SerialName("source_install_id") val sourceInstallId: String,
    val trust: Double,
    val applied: List<SmartImportApplied>,
    val ignored: List<SmartImportIgnored>,
    @SerialName("replaced_previous_import") val replacedPreviousImport: Boolean,
)

@Serializable
data class SmartImportApplied(
    val network: String,
    @SerialName("mapped_to") val mappedTo: String,
    val members: Int,
    val weight: Double,
)

@Serializable
data class SmartImportIgnored(val network: String, val reason: String)

@Serializable
data class SmartExportMetadata(
    val version: Int,
    @SerialName("exported_at") val exportedAt: Long,
    @SerialName("install_id") val installId: String,
    val redacted: Boolean,
    val device: SmartExportDevice? = null,
    val groups: Map<String, SmartExportGroup>,
) {
    val model: String?
        get() = device?.model?.trim()?.takeIf { it.isNotEmpty() }

    val carriers: Set<String>
        get() =
            groups.values
                .flatMap { it.networks.values }
                .mapNotNull { it.carrier?.trim()?.takeIf { carrier -> carrier.isNotEmpty() } }
                .toSet()
}

@Serializable
data class SmartExportDevice(
    val model: String? = null,
    val android: String? = null,
    val app: String? = null,
)

@Serializable
data class SmartExportGroup(
    val networks: Map<String, SmartExportNetwork>,
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class SmartExportNetwork(
    val type: String,
    val label: String? = null,
    val carrier: String? = null,
    val radio: String? = null,
    @SerialName("last_seen") val lastSeen: Long,
)

object SmartJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun status(data: String): SmartStatus = json.decodeFromString<SmartStatus>(data).also { requireVersion(it.version) }

    fun networks(data: String): SmartNetworks = json.decodeFromString<SmartNetworks>(data).also { requireVersion(it.version) }

    fun importReport(data: String): SmartImportReport = json.decodeFromString<SmartImportReport>(data).also { requireVersion(it.version) }

    fun exportMetadata(data: String): SmartExportMetadata = json.decodeFromString<SmartExportMetadata>(data).also { requireVersion(it.version) }

    fun importTrust(data: String, model: String, carrier: String): Double {
        val metadata = exportMetadata(data)
        val localCarrier = carrier.trim()
        if (localCarrier.isEmpty() || metadata.carriers.none { it.equals(localCarrier, ignoreCase = true) }) {
            return 0.1
        }
        val localModel = model.trim()
        return if (localModel.isNotEmpty() && metadata.model?.equals(localModel, ignoreCase = true) == true) 0.5 else 0.25
    }

    fun exportWithDevice(data: String, model: String, android: String, app: String): String {
        val document = json.parseToJsonElement(data).jsonObject
        requireVersion(document["version"]?.jsonPrimitive?.intOrNull ?: 0)
        val device = (document["device"]?.takeUnless { it == JsonNull }?.jsonObject ?: emptyMap()).toMutableMap()
        for ((key, value) in mapOf("model" to model, "android" to android, "app" to app)) {
            if (device[key]?.jsonPrimitive?.contentOrNull.isNullOrBlank()) {
                device[key] = JsonPrimitive(value.trim())
            }
        }
        return JsonObject(document + ("device" to JsonObject(device))).toString()
    }

    private fun requireVersion(version: Int) {
        require(version == 1) { "unsupported_version" }
    }
}
