package io.nekohasekai.sfa.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsProjectionTest {
    @Test
    fun projectsOnlyApprovedSmartFieldsAndToleratesOldCore() {
        val raw = Json.parseToJsonElement(
            """{
          "version":1,"group":"smart","selected":{"tcp":"node.example","standby":"backup"},
          "network":{"type":"wifi","label":"PrivateSSID","key":"BSSID-secret","carrier":"carrier-secret"},
          "budget":{"network_type":"wifi","day":"2026-01-02","used_mb":2,"reserved_mb":1,"limit_mb":10},
          "members":[{"tag":"main","tier":0,"state":"reachable","rtt_ms":12,"download_mbps":40,
            "throughput_source":"active","throughput_at":123,"quarantine_until":null,"destination":"https://secret.example/token"}],
          "configuration":{"private_key":"private-secret"},"events":[{"reason":"event-secret"}]
        }""",
        ).jsonObject
        val projected = DiagnosticsProjection.smartGroup(raw)
        assertEquals("<host>", projected["primary"]?.toString()?.trim('"'))
        assertEquals(setOf("type"), projected["network"]!!.jsonObject.keys)
        assertTrue(projected.toString().contains("latency_ms"))
        for (secret in listOf("PrivateSSID", "BSSID-secret", "carrier-secret", "secret.example", "private-secret", "event-secret", "destination")) {
            assertFalse(projected.toString().contains(secret))
        }
        assertEquals(JsonNull, projected["panics"])
    }

    @Test
    fun preservesOptionalBudgetAccountingDayEnd() {
        val raw = Json.parseToJsonElement(
            """{"group":"smart","budgets":{
                "wifi":{"day":"2026-01-02","day_ends_at":"2026-01-02T21:00:00Z","used_mb":2,"reserved_mb":1,"limit_mb":10},
                "cellular":{"day":"2026-01-02","used_mb":0,"reserved_mb":0,"limit_mb":3},
                "other":{"day":"2026-01-02","day_ends_at":"2026-01-03T00:00:00Z","used_mb":0,"reserved_mb":0,"limit_mb":5}
            }}""",
        ).jsonObject
        val budgets = DiagnosticsProjection.smartGroup(raw)["budgets"]!!.jsonObject
        assertEquals("\"2026-01-02T21:00:00Z\"", budgets["wifi"]!!.jsonObject["day_ends_at"].toString())
        assertEquals("\"2026-01-03T00:00:00Z\"", budgets["other"]!!.jsonObject["day_ends_at"].toString())
        assertFalse(budgets["cellular"]!!.jsonObject.containsKey("day_ends_at"))
    }

    @Test
    fun scrubsCrashMessageAndProjectsFramesInsteadOfRawMetadata() {
        val metadata = Json.parseToJsonElement(
            """{"exceptionName":"java.lang.IllegalStateException",
          "exceptionReason":"dial https://private.example/token 192.0.2.3","appMarketingVersion":"1.0","coreVersion":"1.13.0",
          "processPath":"/private/process","platform":{"SSID":"PrivateSSID"},"configuration":"private-key"}""",
        ).jsonObject
        val crash = DiagnosticsProjection.crash(
            "crash_report",
            "2026-01-02T03:04:05Z",
            metadata,
            DiagnosticsScrubber.stack("at io.example.Worker.run(Worker.kt:42)"),
        )
        assertEquals(setOf("kind", "at", "type", "message", "stack", "app_version", "core_version"), crash.keys)
        assertTrue(crash.toString().contains("Worker.kt:42"))
        for (secret in listOf("private.example", "192.0.2.3", "/private", "PrivateSSID", "private-key")) assertFalse(crash.toString().contains(secret))
        assertTrue(crash["message"].toString().toByteArray().size < 512)
    }

    @Test
    fun boundsPanicMessagesAndDropsUnknownNestedFields() {
        val raw = Json.parseToJsonElement(
            """{"group":"smart","panics":{"total":2,"recent":[{"at":"2026-01-02T03:04:05Z",
          "site":"probe","message":"${"я".repeat(600)} https://private.example/token",
          "stack":"at io.example.Worker.run(Worker.kt:42)","SSID":"PrivateSSID"}]}}""",
        ).jsonObject
        val recent = (DiagnosticsProjection.smartGroup(raw)["panics"]!!.jsonObject["recent"] as JsonArray)[0].jsonObject
        assertTrue((recent["message"] as kotlinx.serialization.json.JsonPrimitive).content.toByteArray().size <= 512)
        assertFalse(recent.toString().contains("PrivateSSID"))
    }

    @Test
    fun preservesKnownLifecycleCategoriesWithoutTreatingActionsAsHostnames() {
        val event = DiagnosticsProjection.event(
            "boot_receiver",
            mapOf(
                "action" to "android.intent.action.MY_PACKAGE_REPLACED",
                "stage" to "install",
                "failure" to "StartService",
                "alert" to "CreateService",
                "start_id" to 7,
                "state" to "Stopped",
            ),
        )
        val detail = event["detail"]!!.jsonObject
        assertEquals("\"MY_PACKAGE_REPLACED\"", detail["action"].toString())
        assertEquals("\"StartService\"", detail["failure"].toString())
        assertEquals("\"install\"", detail["stage"].toString())
        assertEquals("7", detail["start_id"].toString())
        assertEquals(6, detail.size)
    }
}
