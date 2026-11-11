package io.nekohasekai.sfa.compose.screen.smart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartPolicyTest {
    @Test
    fun convertsEveryCatalogFieldToTheFullOverrideShapeWithoutChangingValues() {
        val entries =
            smartProfileCatalog(
                """[
                {"name":"future-profile","rank":["udp","latency"],"min_stability":"C",
                 "target_mbps":17.125,"throughput_enabled":false,"streams":7,"heartbeat_interval":"7.5s",
                 "standby_interval":"3m2s","background_interval":"9m1s","interrupt_on_failover":false,
                 "quic_policy":"route_only","improvement":false,"dwell":"11m12s","confirmations":5,
                 "confirmation_interval":"4.25s","tier_restore":"0s","hysteresis":0.123456789,"speed_margin":1.23456789},
                {"name":"another-profile","rank":["download"],"min_stability":"none",
                 "target_mbps":99,"throughput_enabled":true,"streams":1,"heartbeat_interval":"1s",
                 "standby_interval":"2s","background_interval":"3s","interrupt_on_failover":true,
                 "quic_policy":"off","improvement":true,"dwell":"4s","confirmations":6,
                 "confirmation_interval":"5s","tier_restore":"6s","hysteresis":0,"speed_margin":2}
            ]""",
            )
        assertEquals(listOf("future-profile", "another-profile"), entries.map { it.name })
        assertEquals(
            Json.parseToJsonElement(
                """{"preference":{"profile":"future-profile","rank":["udp","latency"],"min_stability":"C",
                    "speed_floor_hysteresis":0.123456789,"switch":{"improvement":false,"dwell":"11m12s",
                    "confirmations":5,"confirmation_interval":"4.25s","tier_restore":"0s","speed_margin":1.23456789}},
                    "throughput":{"target_mbps":17.125,"enabled":false,"streams":7},
                    "probe":{"heartbeat_interval":"7.5s","standby_interval":"3m2s","background_interval":"9m1s"},
                    "interrupt_on_failover":false,"udp":{"quic_policy":"route_only"}}""",
            ),
            entries.first().policy,
        )
        assertEquals("another-profile", smartPolicyBase(entries.last().policy.toString()))
        assertTrue(smartPolicyIsCustom(entries.first().policy.toString()))
        assertEquals(emptyList<SmartProfileEntry>(), smartProfileCatalog("[]"))
    }

    @Test
    fun namedPoliciesUseExactCompactStorageAndEscapeUnknownNames() {
        assertEquals("{\"preference\":{\"profile\":\"default\"}}", smartNamedPolicy("default"))
        assertEquals("{\"preference\":{\"profile\":\"phone\"}}", smartNamedPolicy("phone"))
        val unknown = "future-\"profile\\name"
        assertEquals(unknown, smartPolicyBase(smartNamedPolicy(unknown)))
        assertFalse(smartPolicyIsCustom(smartNamedPolicy(unknown)))
        assertFalse(smartPolicyIsCustom(" { \"preference\": { \"profile\": \"phone\" } } "))
    }

    @Test
    fun customClassificationPreservesExplicitValuesAndPartialJson() {
        assertFalse(smartPolicyIsCustom(""))
        assertEquals("default", smartPolicyBase(""))
        assertTrue(smartPolicyIsCustom("{}"))
        assertEquals("default", smartPolicyBase("{}"))
        assertTrue(smartPolicyIsCustom("""{"preference":{"profile":"phone","rank":["stability"]}}"""))
        assertTrue(smartPolicyIsCustom("""{"preference":{"profile":"phone"},"throughput":{"enabled":true}}"""))
        assertTrue(smartPolicyIsCustom("""{"preference":{"profile":"phone","switch":{}}}"""))
        assertTrue(smartPolicyIsCustom("""{"probe":{"heartbeat_interval":"2.123456789s"}}"""))
        assertEquals("phone", smartPolicyBase("""{"preference":{"profile":"phone","switch":{"improvement":false}}}"""))
    }

    @Test
    fun statusDecodesPreferenceAndStillAcceptsLegacyDocuments() {
        val fixture =
            requireNotNull(javaClass.getResourceAsStream("/smart/status.json"))
                .bufferedReader()
                .use { it.readText() }
        val document = Json.parseToJsonElement(fixture) as JsonObject
        val legacy = SmartJson.status(JsonObject(document - "preference").toString())
        assertNull(legacy.preference)
        val resolved = Json.parseToJsonElement("""{"preference":{"profile":"phone"},"throughput":{"target_mbps":17.123456789}}""")
        val preference =
            Json.parseToJsonElement(
                """{"engine":"profile","base":"phone","custom":true,"source":"override","override_hash":"exact-hash","resolved":$resolved}""",
            )
        val parsed = SmartJson.status(JsonObject(document + ("preference" to preference)).toString())
        assertEquals(SmartPreference("profile", "phone", true, "override", "exact-hash", resolved as JsonObject), parsed.preference)
        assertEquals(legacy, parsed.copy(preference = null))
        assertNull(SmartJson.status(JsonObject(document + ("preference" to kotlinx.serialization.json.JsonNull)).toString()).preference)
        assertEquals(
            JsonPrimitive("phone"),
            parsed.preference
                ?.resolved
                ?.get("preference")
                ?.let { (it as JsonObject)["profile"] },
        )
    }
}
