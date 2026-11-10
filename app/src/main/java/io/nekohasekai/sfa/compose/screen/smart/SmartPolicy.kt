package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.libbox.Libbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class SmartProfileEntry(val name: String, val policy: JsonObject)

internal fun smartProfileCatalog(): List<SmartProfileEntry> = smartProfileCatalog(Libbox.smartProfiles())

internal fun smartProfileCatalog(data: String): List<SmartProfileEntry> = Json.parseToJsonElement(data).jsonArray.map { element ->
    val row = element.jsonObject
    val name = row.getValue("name").jsonPrimitive.content
    SmartProfileEntry(
        name,
        buildJsonObject {
            put("preference", buildJsonObject {
                put("profile", name)
                put("rank", row.getValue("rank"))
                put("min_stability", row.getValue("min_stability"))
                put("speed_floor_hysteresis", row.getValue("hysteresis"))
                put("switch", buildJsonObject {
                    put("improvement", row.getValue("improvement"))
                    put("dwell", row.getValue("dwell"))
                    put("confirmations", row.getValue("confirmations"))
                    put("confirmation_interval", row.getValue("confirmation_interval"))
                    put("tier_restore", row.getValue("tier_restore"))
                    put("speed_margin", row.getValue("speed_margin"))
                })
            })
            put("throughput", buildJsonObject {
                put("target_mbps", row.getValue("target_mbps"))
                put("enabled", row.getValue("throughput_enabled"))
            })
            put("probe", buildJsonObject {
                put("heartbeat_interval", row.getValue("heartbeat_interval"))
                put("standby_interval", row.getValue("standby_interval"))
                put("background_interval", row.getValue("background_interval"))
            })
            put("interrupt_on_failover", row.getValue("interrupt_on_failover"))
            put("udp", buildJsonObject {
                put("quic_policy", row.getValue("quic_policy"))
            })
        },
    )
}

internal fun smartNamedPolicy(name: String): String = buildJsonObject {
    put("preference", buildJsonObject { put("profile", name) })
}.toString()

internal fun smartPolicyIsCustom(value: String): Boolean {
    if (value.isEmpty()) return false
    val policy = Json.parseToJsonElement(value).jsonObject
    val preference = policy["preference"] as? JsonObject
    val profile = preference?.get("profile") as? JsonPrimitive
    return policy.size != 1 || preference?.size != 1 || profile?.isString != true
}

internal fun smartPolicyBase(value: String): String {
    if (value.isEmpty()) return "default"
    val preference = Json.parseToJsonElement(value).jsonObject["preference"] as? JsonObject
    return (preference?.get("profile") as? JsonPrimitive)?.content ?: "default"
}
