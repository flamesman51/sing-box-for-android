package io.nekohasekai.sfa.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.UnknownHostException

class DiagnosticsProjectionTest {
    @get:Rule val temporary = TemporaryFolder()

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
    fun dropsCrashMessagesAndProjectsFramesInsteadOfRawMetadata() {
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
        assertEquals(setOf("kind", "at", "type", "category", "stack", "app_version", "core_version"), crash.keys)
        for (secret in listOf("private.example", "192.0.2.3", "/private", "PrivateSSID", "private-key")) assertFalse(crash.toString().contains(secret))
        assertTrue(crash.toString().contains("Worker.kt:42"))
        assertEquals("\"IllegalStateException\"", crash["type"].toString())
        assertEquals("\"other\"", crash["category"].toString())
    }

    @Test
    fun dropsPanicMessagesAndDropsUnknownNestedFields() {
        val raw = Json.parseToJsonElement(
            """{"group":"smart","panics":{"total":2,"recent":[{"at":"2026-01-02T03:04:05Z",
          "site":"probe","message":"${"я".repeat(600)} https://private.example/token",
          "stack":"at io.example.Worker.run(Worker.kt:42)","SSID":"PrivateSSID"}]}}""",
        ).jsonObject
        val recent = (DiagnosticsProjection.smartGroup(raw)["panics"]!!.jsonObject["recent"] as JsonArray)[0].jsonObject
        assertFalse(recent.toString().contains("PrivateSSID"))
        assertFalse(recent.containsKey("message"))
        assertEquals("\"CorePanic\"", recent["type"].toString())
        assertEquals("\"libbox\"", recent["category"].toString())
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

    @Test
    fun eventDetailsAcceptOnlyEnumsAndNumbersAndNeverExceptionMessages() {
        val event = DiagnosticsProjection.event(
            "vpn_start_failed",
            DiagnosticsProjection.failure(UnknownHostException("Unable to resolve host \"myrouter\"")) + mapOf(
                "message" to "dial private.example:443 192.0.2.10.",
                "reason" to "dial private.example:443",
                "decision" to "myrouter",
                "start_id" to 5,
            ),
        )
        val detail = event["detail"]!!.jsonObject
        assertEquals(setOf("type", "category", "start_id"), detail.keys)
        assertEquals("\"UnknownHostException\"", detail["type"].toString())
        assertEquals("\"network\"", detail["category"].toString())
        assertEquals(event, DiagnosticsProjection.sanitizeEvent(event))
        assertEquals("other", DiagnosticsProjection.failure(IllegalStateException("permission denied"))["category"])
        assertEquals("permission", DiagnosticsProjection.failure(SecurityException("private"))["category"])
        assertEquals("config", DiagnosticsProjection.failure(IllegalArgumentException("private"))["category"])
        assertEquals("oom", DiagnosticsProjection.failure(OutOfMemoryError("private"))["category"])
        assertEquals("native", DiagnosticsProjection.failure(UnsatisfiedLinkError("private"))["category"])
    }

    @Test
    fun crashWithResolverDialAndPunctuatedAddressNeverEntersReportOrJournal() {
        val secret = "dial private.example:443\nUnable to resolve host \"myrouter\"\n192.0.2.10."
        val crash = DiagnosticsProjection.crash(
            "crash_report",
            DiagnosticsProjection.utc(),
            buildJsonObject {
                put("exceptionName", JsonPrimitive("java.net.UnknownHostException"))
                put("exceptionReason", JsonPrimitive(secret))
            },
            "$secret\nat io.example.Worker.run(Worker.kt:42)",
        )
        val store = DiagnosticsStore(temporary.newFolder())
        store.discover("owner", "crash_report", "crash")
        store.record("owner", DiagnosticsProjection.event("vpn_start_failed", DiagnosticsProjection.failure(UnknownHostException(secret)) + mapOf("message" to secret)))
        val report = store.prepare("owner", JsonObject(emptyMap()), null, listOf(DiagnosticsCrash(DiagnosticsStore.crashKey("crash_report", "crash"), crash)))!!
        for (value in listOf("private.example", "myrouter", "192.0.2.10", "message")) {
            assertFalse(report.body, report.body.contains(value))
            assertFalse(store.eventsFile.readText().contains(value))
        }
        assertTrue(report.body.contains("Worker.kt:42"))
        assertEquals(crash, DiagnosticsProjection.sanitizeCrash(crash))
    }

    @Test
    fun preservesCoreGoStacksAndSanitizesLegacyStoredFields() {
        val raw = Json.parseToJsonElement(
            """{"groups":[{"group":"smart","panics":{"total":1,"recent":[{"site":"probe","message":"dial private.example:443","stack":"group.(*Smart).worker\nsmart.go:78"}]},"members":[{"tag":"node","tier":0,"state":"reachable","rtt_ms":12,"download_mbps":20,"throughput_source":"active","throughput_at":5}]}]}""",
        ).jsonObject
        val safe = DiagnosticsProjection.sanitizeSmart(raw)
        assertFalse(safe.toString().contains("message"))
        assertFalse(safe.toString().contains("private.example"))
        assertTrue(safe.toString().contains("smart.go:78"))
        assertEquals(safe, DiagnosticsProjection.sanitizeSmart(safe))
    }

    @Test
    fun redactsUncompressedIpv4EmbeddedIpv6InEverySmartTag() {
        val address = "2001:db8:1:2:3:4:192.0.2.10"
        val raw = Json.parseToJsonElement(
            """{"group":"$address","selected":{"tcp":"$address","standby":"$address"},"members":[{"tag":"$address"}]}""",
        ).jsonObject
        val projected = DiagnosticsProjection.smartGroup(raw)
        for (key in listOf("group", "primary", "standby")) assertEquals(JsonPrimitive("<ip>"), projected[key])
        assertEquals(JsonPrimitive("<ip>"), (projected["members"] as JsonArray).single().jsonObject["tag"])
        assertFalse(projected.toString().contains("2001:db8"))
        assertFalse(projected.toString().contains("192.0.2.10"))
    }
}
