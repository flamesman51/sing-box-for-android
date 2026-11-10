package io.nekohasekai.sfa.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticsProjection {
    private val detailKeys = setOf("reason", "message", "action", "decision", "from", "to", "version", "version_code", "type", "kind", "status", "count", "id", "alert", "failure", "start_id", "state", "stage")
    private val symbol = Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,255}")

    fun utc(millis: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))

    fun app(fields: JsonObject): JsonObject = buildJsonObject {
        for (key in listOf("version_name", "core_version", "device_model")) put(key, safe(fields.string(key), 128))
        put("version_code", fields.number("version_code"))
        put("android_sdk", fields.number("android_sdk"))
    }

    fun event(kind: String, detail: Map<String, Any?>, at: String = utc()): JsonObject = buildJsonObject {
        put("at", at)
        put("kind", kind.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "unknown")
        put(
            "detail",
            JsonObject(
                buildMap {
                    var bytes = 2
                    for ((key, value) in detail) {
                        if (key !in detailKeys) continue
                        val projected = when (value) {
                            null -> JsonNull
                            is Boolean -> JsonPrimitive(value)
                            is Number -> value.toDouble().takeIf { it.isFinite() }?.let { JsonPrimitive(value) } ?: JsonNull
                            is String -> {
                                val action = value.takeIf {
                                    key == "action" && it in setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED", "android.intent.action.LOCKED_BOOT_COMPLETED")
                                }?.substringAfterLast('.') ?: value
                                safe(action, 512)
                            }
                            else -> continue
                        }
                        val entryBytes = key.length + projected.toString().toByteArray(Charsets.UTF_8).size + 4
                        if (bytes + entryBytes <= 4096) {
                            put(key, projected)
                            bytes += entryBytes
                        }
                    }
                },
            ),
        )
    }

    fun smartGroup(raw: JsonObject): JsonObject = buildJsonObject {
        put("group", safe(raw.string("group"), 128))
        val selected = raw.obj("selected")
        put("primary", safe(selected?.string("tcp"), 128))
        put("standby", safe(selected?.string("standby"), 128))
        put("degraded", (raw["degraded"] as? JsonPrimitive)?.booleanOrNull?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "network",
            buildJsonObject {
                put("type", networkType(raw.obj("network")?.string("type")))
            },
        )
        put(
            "budgets",
            buildJsonObject {
                for (type in listOf("wifi", "cellular", "other")) {
                    val current = raw.obj("budgets")?.obj(type)
                        ?: raw.obj("budget")?.takeIf { it.string("network_type") == type }
                    if (current != null) {
                        put(
                            type,
                            buildJsonObject {
                                put("day", safe(current.string("day"), 32))
                                current.string("day_ends_at")?.let { put("day_ends_at", safe(it, 64)) }
                                for (key in listOf("used_mb", "reserved_mb", "limit_mb")) put(key, current.number(key))
                            },
                        )
                    }
                }
            },
        )
        put(
            "panics",
            raw.obj("panics")?.let { panics ->
                buildJsonObject {
                    put("total", panics.number("total"))
                    put(
                        "recent",
                        JsonArray(
                            panics.array("recent").takeLast(20).mapNotNull { item ->
                                (item as? JsonObject)?.let { panic ->
                                    buildJsonObject {
                                        put("at", safe(panic.string("at"), 64))
                                        put("site", safe(panic.string("site"), 128))
                                        put("message", safe(panic.string("message"), 512))
                                        put("stack", DiagnosticsScrubber.stack(panic.string("stack").orEmpty(), 4096))
                                    }
                                }
                            },
                        ),
                    )
                }
            } ?: JsonNull,
        )
        put(
            "members",
            JsonArray(
                raw.array("members").take(128).mapNotNull { item ->
                    (item as? JsonObject)?.let { member ->
                        buildJsonObject {
                            put("tag", safe(member.string("tag"), 128))
                            put("tier", member.number("tier"))
                            put("state", safe(member.string("state"), 64))
                            put("latency_ms", member.number("rtt_ms"))
                            put(
                                "throughput",
                                buildJsonObject {
                                    put("download", member.number("download_mbps"))
                                    put("source", safe(member.string("throughput_source"), 64))
                                    put("at", member.number("throughput_at"))
                                },
                            )
                            put("quarantine_until", member.number("quarantine_until"))
                        }
                    }
                },
            ),
        )
    }

    fun crash(kind: String, at: String, metadata: JsonObject, frames: String): JsonObject = buildJsonObject {
        put("kind", if (kind in setOf("crash_report", "oom_report", "power_report")) kind else "crash_report")
        put("at", at)
        val type = metadata.string("exceptionName") ?: metadata.string("signalName") ?: when (kind) {
            "oom_report" -> "OutOfMemory"
            "power_report" -> "PowerReport"
            else -> "NativeCrash"
        }
        put("type", type.takeIf { symbol.matches(it) } ?: "UnknownException")
        put("message", safe(metadata.string("exceptionReason"), 512))
        put("stack", DiagnosticsScrubber.stack(frames, 16 * 1024))
        put("app_version", safe(metadata.string("appMarketingVersion") ?: metadata.string("appVersion"), 128))
        put("core_version", safe(metadata.string("coreVersion"), 128))
    }

    private fun safe(value: String?, bytes: Int): JsonElement = value?.let { JsonPrimitive(DiagnosticsScrubber.text(it, bytes)) } ?: JsonNull
    private fun networkType(value: String?): JsonElement = value?.takeIf { it in setOf("wifi", "cellular", "other") }?.let(::JsonPrimitive) ?: JsonNull
    internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !== JsonNull }?.content
    internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    internal fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
    private fun JsonObject.number(key: String): JsonElement {
        val primitive = this[key] as? JsonPrimitive ?: return JsonNull
        if (primitive.isString) return JsonNull
        primitive.longOrNull?.let { return JsonPrimitive(it) }
        return primitive.doubleOrNull?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull
    }
}
