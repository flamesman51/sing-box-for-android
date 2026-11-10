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
    const val MAX_SMART_GROUPS = 4
    const val MAX_SMART_MEMBERS = 32
    const val MAX_SMART_PANICS = 4
    const val MAX_PANIC_STACK_BYTES = 2048
    private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")
    private val categories = setOf("permission", "config", "network", "libbox", "oom", "native", "other")
    private val numericDetails = setOf("from", "to", "version_code", "count", "start_id", "status")
    private val alerts = setOf("RequestVPNPermission", "RequestNotificationPermission", "RequestLocationPermission", "EmptyConfiguration", "StartCommandServer", "CreateService", "StartService")
    private val detailEnums = mapOf(
        "reason" to setOf("User", "AlwaysOn", "StickyRestart", "Boot", "Revoke", "FatalConfiguration", "ProcessDestroyed"),
        "action" to setOf("BOOT_COMPLETED", "MY_PACKAGE_REPLACED", "LOCKED_BOOT_COMPLETED"),
        "decision" to setOf("Start", "NotRequested", "UnreadCrashReports", "failed", "superseded_by_stop", "not_requested", "already_active"),
        "stage" to setOf("check", "download", "install"),
        "state" to setOf("Stopped", "Starting", "Started", "Stopping"),
        "alert" to alerts,
        "failure" to alerts,
        "category" to categories,
    )
    private val eventKinds = setOf("process_start", "core_ready", "core_setup_failed", "vpn_start", "vpn_stop", "vpn_start_failed", "vpn_start_request", "vpn_start_skipped", "boot_receiver", "update_downloaded", "update_installed", "update_failed", "crash_report", "oom_report", "power_report", "diagnostics_rejected")

    fun failure(error: Throwable): Map<String, Any?> = mapOf("type" to exceptionType(error.javaClass.simpleName), "category" to category(error.javaClass.simpleName))

    private fun exceptionType(value: String?): String = value?.substringAfterLast('.')?.takeIf(identifier::matches) ?: "UnknownException"

    private fun category(type: String): String = when (type) {
        "SecurityException", "AccessDeniedException" -> "permission"
        "IllegalArgumentException", "EmptyConfiguration" -> "config"
        "IOException", "SocketException", "SocketTimeoutException", "UnknownHostException", "ConnectException", "NoRouteToHostException", "SSLException", "SSLHandshakeException" -> "network"
        "OutOfMemoryError", "OutOfMemory", "OOM" -> "oom"
        "NativeCrash", "UnsatisfiedLinkError", "SIGSEGV", "SIGABRT", "SIGBUS", "SIGILL", "SIGFPE" -> "native"
        "CoreError", "CorePanic", "CreateService", "StartCommandServer" -> "libbox"
        "RequestVPNPermission", "RequestNotificationPermission", "RequestLocationPermission" -> "permission"
        else -> "other"
    }

    fun utc(millis: Long = System.currentTimeMillis()): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date(millis))

    fun app(fields: JsonObject): JsonObject = buildJsonObject {
        for (key in listOf("version_name", "core_version", "device_model")) put(key, safe(fields.string(key), 128))
        put("version_code", fields.number("version_code"))
        put("android_sdk", fields.number("android_sdk"))
    }

    fun event(kind: String, detail: Map<String, Any?>, at: String = utc()): JsonObject = buildJsonObject {
        put("at", DiagnosticsScrubber.utf8(at, 64))
        put("kind", kind.takeIf { it in eventKinds } ?: "unknown")
        put(
            "detail",
            JsonObject(
                buildMap {
                    for ((key, value) in detail) {
                        if (key in numericDetails && value is Number) {
                            value.toDouble().takeIf { it.isFinite() }?.let {
                                put(key, if (value is Byte || value is Short || value is Int || value is Long) JsonPrimitive(value.toLong()) else JsonPrimitive(it))
                            }
                        } else if (value is String) {
                            val normalized = if (key == "action") value.removePrefix("android.intent.action.") else value
                            if (key == "type") {
                                put(key, JsonPrimitive(exceptionType(value)))
                            } else if (normalized in detailEnums[key].orEmpty()) {
                                put(key, JsonPrimitive(normalized))
                            }
                        }
                    }
                    if (kind == "vpn_start_failed") {
                        val type = (get("type") as? JsonPrimitive)?.content ?: "CoreError"
                        put("type", JsonPrimitive(type))
                        if ("category" !in this) put("category", JsonPrimitive(category(type)))
                    }
                },
            ),
        )
    }

    fun sanitizeEvent(raw: JsonObject): JsonObject = event(
        raw.string("kind").orEmpty(),
        raw.obj("detail").orEmpty().mapValues { (_, value) ->
            (value as? JsonPrimitive)?.let { if (it.isString) it.content else it.longOrNull ?: it.doubleOrNull }
        },
        raw.string("at").orEmpty(),
    )

    fun smartGroup(raw: JsonObject): JsonObject = buildJsonObject {
        put("group", safe(raw.string("group"), 64))
        val selected = raw.obj("selected")
        put("primary", safe(selected?.string("tcp") ?: raw.string("primary"), 64))
        put("standby", safe(selected?.string("standby") ?: raw.string("standby"), 64))
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
                            panics.array("recent").takeLast(MAX_SMART_PANICS).mapNotNull { item ->
                                (item as? JsonObject)?.let { panic ->
                                    buildJsonObject {
                                        put("at", safe(panic.string("at"), 64))
                                        put("site", safe(panic.string("site"), 128))
                                        put("type", "CorePanic")
                                        put("category", "libbox")
                                        put("stack", DiagnosticsScrubber.stack(panic.string("stack").orEmpty(), MAX_PANIC_STACK_BYTES))
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
                raw.array("members").take(MAX_SMART_MEMBERS).mapNotNull { item ->
                    (item as? JsonObject)?.let { member ->
                        buildJsonObject {
                            put("tag", safe(member.string("tag"), 64))
                            put("tier", member.number("tier"))
                            put("state", safe(member.string("state"), 64))
                            put("latency_ms", if ("rtt_ms" in member) member.number("rtt_ms") else member.number("latency_ms"))
                            put(
                                "throughput",
                                buildJsonObject {
                                    val previous = member.obj("throughput")
                                    put("download", if ("download_mbps" in member) member.number("download_mbps") else previous?.number("download") ?: JsonNull)
                                    put("source", safe(member.string("throughput_source") ?: previous?.string("source"), 64))
                                    put("at", if ("throughput_at" in member) member.number("throughput_at") else previous?.number("at") ?: JsonNull)
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
        val type = exceptionType(
            metadata.string("exceptionName") ?: metadata.string("signalName") ?: when (kind) {
                "oom_report" -> "OutOfMemory"
                "power_report" -> "PowerReport"
                else -> "NativeCrash"
            },
        )
        put("type", type)
        put("category", category(type))
        put("stack", DiagnosticsScrubber.stack(frames, 16 * 1024))
        put("app_version", safe(metadata.string("appMarketingVersion") ?: metadata.string("appVersion"), 128))
        put("core_version", safe(metadata.string("coreVersion"), 128))
    }

    fun sanitizeSmart(raw: JsonObject): JsonObject = buildJsonObject {
        put("groups", JsonArray(raw.array("groups").take(MAX_SMART_GROUPS).mapNotNull { (it as? JsonObject)?.let(::smartGroup) }))
    }

    fun sanitizeCrash(raw: JsonObject): JsonObject {
        val metadata = buildJsonObject {
            put("exceptionName", raw.string("type") ?: "UnknownException")
            put("appMarketingVersion", raw["app_version"] ?: JsonNull)
            put("coreVersion", raw["core_version"] ?: JsonNull)
        }
        return JsonObject(
            crash(raw.string("kind").orEmpty(), raw.string("at").orEmpty(), metadata, raw.string("stack").orEmpty()) +
                raw["id"]?.let { mapOf("id" to it) }.orEmpty(),
        )
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
