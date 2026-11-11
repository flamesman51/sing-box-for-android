package io.nekohasekai.sfa.compose.screen.smart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SmartEditorPolicyTest {
    private val fullPolicy = """
        {
          "preference": {
            "profile": "phone", "rank": ["outages", "download", "udp"],
            "min_stability": "D", "speed_floor_hysteresis": 0.12345678901234567890123456789,
            "switch": {
              "improvement": false, "dwell": "1m0.000000001s", "confirmations": 9223372036854775807,
              "confirmation_interval": "1.000000001s", "tier_restore": "0",
              "speed_margin": 1.2345678901234567890123456789
            }
          },
          "throughput": {"target_mbps": 17.12345678901234567890123456789, "enabled": false, "streams": 4},
          "probe": {"heartbeat_interval": "1ns", "standby_interval": "2µs", "background_interval": "24h0m0.000000001s"},
          "interrupt_on_failover": false, "udp": {"quic_policy": "route_only"}
        }
    """.trimIndent()

    @Test
    fun untouchedPolicyKeepsExactSourceIncludingWhitespaceAndNumberTokens() {
        val policy = SmartEditorPolicy(fullPolicy)
        assertEquals(fullPolicy, policy.source)
        assertSame(policy, policy.withValue("interrupt_on_failover", JsonPrimitive(false)))
    }

    @Test
    fun everyNumberRoundTripsWithoutChangingAnyField() {
        val policy = SmartEditorPolicy(fullPolicy)
        for (field in SmartEditorNumber.entries) {
            val text = requireNotNull(field.display(policy))
            assertSame("${field.name}: $text", policy, field.update(policy, text))
            assertSame("${field.name}: localized", policy, field.update(policy, text.replace('.', ',')))
        }
    }

    @Test
    fun singleStructuredEditPreservesOtherObjectsAndExactNumbers() {
        val policy = SmartEditorPolicy(fullPolicy)
        val changed = policy.withValue("interrupt_on_failover", JsonPrimitive(true))
        for ((key, value) in policy.objectValue) {
            if (key != "interrupt_on_failover") assertEquals(value, changed.objectValue[key])
        }
        assertEquals("D", changed.text("preference.min_stability"))
        assertEquals("17.12345678901234567890123456789", changed.text("throughput.target_mbps"))
        assertEquals("1m0.000000001s", changed.text("preference.switch.dwell"))
        assertEquals(listOf("outages", "download", "udp"), changed.ranking())
    }

    @Test
    fun localizedDecimalsNeverPassThroughDouble() {
        val policy = SmartEditorPolicy(fullPolicy)
        val target = SmartEditorNumber.Target.update(policy, "37,12345678901234567890123456789")
        assertEquals("37.12345678901234567890123456789", target.text("throughput.target_mbps"))
        assertFalse((target.value("throughput.target_mbps") as JsonPrimitive).isString)
        val hysteresis = SmartEditorNumber.Hysteresis.update(policy, "12,345678901234567890123456789")
        assertEquals("0.12345678901234567890123456789", hysteresis.text("preference.speed_floor_hysteresis"))
        val margin = SmartEditorNumber.SpeedMargin.update(policy, "42,12345678901234567890123456789")
        assertEquals("1.4212345678901234567890123456789", margin.text("preference.switch.speed_margin"))
    }

    @Test
    fun durationEditsPreserveNanosecondsInBothUnits() {
        val policy = SmartEditorPolicy(fullPolicy)
        val seconds = SmartEditorNumber.Heartbeat.update(policy, "1,000000001")
        assertEquals("1000000001ns", seconds.text("probe.heartbeat_interval"))
        val minutes = SmartEditorNumber.Dwell.update(policy, "0,00000000005")
        assertEquals("3ns", minutes.text("preference.switch.dwell"))
        assertSame(minutes, SmartEditorNumber.Dwell.update(minutes, requireNotNull(SmartEditorNumber.Dwell.display(minutes))))
    }

    @Test
    fun durationGrammarIncludesAllCoreUnitsAndSignedZero() {
        assertEquals(BigDecimal("1"), SmartEditorNumber.durationNanos("1ns"))
        assertEquals(BigDecimal("1000"), SmartEditorNumber.durationNanos("1us"))
        assertEquals(BigDecimal("1000"), SmartEditorNumber.durationNanos("1µs"))
        assertEquals(BigDecimal("1000"), SmartEditorNumber.durationNanos("1μs"))
        assertEquals(BigDecimal("1000000"), SmartEditorNumber.durationNanos("1ms"))
        assertEquals(BigDecimal("90061000000001"), SmartEditorNumber.durationNanos("+1d1h1m1.000000001s"))
        assertEquals(BigDecimal.ZERO, SmartEditorNumber.durationNanos("-0"))
        assertEquals(BigDecimal("9223372036854775807"), SmartEditorNumber.durationNanos("2562047h47m16.854775807s"))
    }

    @Test
    fun integerCountEditsUseCanonicalIntegerTokensWithoutNarrowing() {
        val policy = SmartEditorPolicy(fullPolicy)
        for (field in listOf(SmartEditorNumber.Streams, SmartEditorNumber.Confirmations)) {
            val changed = field.update(policy, "9007199254740993,0")
            assertEquals("9007199254740993", changed.text(field.path))
            assertFalse((changed.value(field.path) as JsonPrimitive).isString)
            assertEquals("9007199254740993", field.display(changed))
            assertSame(changed, field.update(changed, requireNotNull(field.display(changed))))
            assertEquals(changed.objectValue, SmartEditorPolicy(changed.source).objectValue)
        }
    }

    @Test
    fun fractionalCountsRemainInvalidIntegerTokensForCoreValidation() {
        val policy = SmartEditorPolicy(fullPolicy)
        for (field in listOf(SmartEditorNumber.Streams, SmartEditorNumber.Confirmations)) {
            for (input in listOf("2.5", "2,5")) {
                val changed = field.update(policy, input)
                assertEquals("2.5", changed.text(field.path))
                assertFalse((changed.value(field.path) as JsonPrimitive).isString)
                assertNull(changed.text(field.path)!!.toLongOrNull())
            }
        }
    }

    @Test
    fun streamEditsPreserveOtherThroughputValuesAndPolicyFields() {
        val policy = SmartEditorPolicy(fullPolicy)
        val changed = SmartEditorNumber.Streams.update(policy, "8")
        assertEquals("8", changed.text("throughput.streams"))
        assertEquals(policy.value("throughput.target_mbps"), changed.value("throughput.target_mbps"))
        assertEquals(policy.value("throughput.enabled"), changed.value("throughput.enabled"))
        for ((key, value) in policy.objectValue) {
            if (key != "throughput") assertEquals(value, changed.objectValue[key])
        }
    }

    @Test
    fun newMeasurementCriteriaRoundTripInTheirExplicitRankingOrder() {
        val policy = SmartEditorPolicy(fullPolicy).withValue(
            "preference.rank",
            Json.parseToJsonElement("""["upload","rtt","jitter","latency","rtt_spread"]"""),
        )
        assertEquals(listOf("upload", "rtt", "jitter", "latency", "rtt_spread"), SmartEditorPolicy(policy.source).ranking())
    }

    @Test
    fun fractionalMinuteDisplayDoesNotEraseOneNanosecondOrMaximumDuration() {
        for (duration in listOf("1ns", "2562047h47m16.854775807s")) {
            val policy = SmartEditorPolicy("""{"preference":{"switch":{"dwell":"$duration"}}}""")
            val shown = requireNotNull(SmartEditorNumber.Dwell.display(policy))
            assertSame(policy, SmartEditorNumber.Dwell.update(policy, shown))
            assertEquals(duration, policy.text("preference.switch.dwell"))
        }
    }

    @Test
    fun sparseJsonIsNeverExpandedFromBaseDuringStructuredEdits() {
        val raw = """{ "preference": {"profile":"phone", "min_stability":"D"}, "udp":{"quic_policy":"off"} }"""
        val policy = SmartEditorPolicy(raw)
        assertEquals(raw, policy.source)
        val changed = SmartEditorNumber.Target.update(policy, "2.1234567890123456789")
        assertEquals(policy.value("preference"), changed.value("preference"))
        assertEquals(policy.value("udp"), changed.value("udp"))
        assertEquals(setOf("preference", "udp", "throughput"), changed.objectValue.keys)
        assertFalse(changed.objectValue.containsKey("probe"))
        assertEquals(setOf("target_mbps"), (changed.objectValue["throughput"] as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun jsonModeRoundTripPreservesCoreValidLessCommonValues() {
        val policy = SmartEditorPolicy(fullPolicy)
        val reloaded = SmartEditorPolicy(policy.source)
        assertEquals(Json.parseToJsonElement(fullPolicy), reloaded.objectValue)
        assertEquals("9223372036854775807", reloaded.text("preference.switch.confirmations"))
        assertEquals("4", reloaded.text("throughput.streams"))
        assertTrue((reloaded.value("preference.switch.dwell") as JsonPrimitive).isString)
        // Core badoption.Duration.UnmarshalJSON decodes a string, not a numeric nanosecond count.
        assertEquals("1ns", reloaded.text("probe.heartbeat_interval"))
    }

    @Test
    fun sparseDraftDisplaysFullBaseValuesWithoutChangingItsJsonSource() {
        val raw = """{ "preference": {"profile":"phone", "switch":{"improvement":true}}, "throughput":{"target_mbps":2.12345678901234567890123456789} }"""
        val draft = SmartEditorPolicy(raw)
        val base = SmartEditorPolicy(fullPolicy)
        val effective = draft.withDefaults(base.objectValue)
        assertEquals(raw, draft.source)
        assertEquals("2.12345678901234567890123456789", effective.text("throughput.target_mbps"))
        assertEquals("true", effective.text("preference.switch.improvement"))
        assertEquals(base.ranking(), effective.ranking())
        assertEquals(base.text("preference.switch.dwell"), effective.text("preference.switch.dwell"))
        assertEquals(base.text("throughput.enabled"), effective.text("throughput.enabled"))
        assertEquals("4", SmartEditorNumber.Streams.display(effective))
        assertEquals(base.value("probe"), effective.value("probe"))
        assertEquals(base.objectValue.keys, effective.objectValue.keys)
        assertEquals(base.value("udp"), effective.value("udp"))
        assertEquals(effective.objectValue, SmartEditorPolicy(effective.source).objectValue)
    }

    @Test
    fun expansionPreservesExplicitFalseZeroRankOrderAndFullPrecision() {
        val base = SmartEditorPolicy(fullPolicy)
        val raw = """{"preference":{"profile":"default","rank":["udp","download"],"min_stability":"none","speed_floor_hysteresis":0,"switch":{"improvement":false,"tier_restore":"0ns","speed_margin":1.00000000000000000000000001}},"throughput":{"enabled":false,"streams":1},"interrupt_on_failover":false}"""
        val draft = SmartEditorPolicy(raw)
        val effective = draft.withDefaults(base.objectValue)
        assertEquals(raw, draft.source)
        assertEquals("default", effective.text("preference.profile"))
        assertEquals(listOf("udp", "download"), effective.ranking())
        assertEquals("none", effective.text("preference.min_stability"))
        assertEquals("0", effective.text("preference.speed_floor_hysteresis"))
        assertEquals("false", effective.text("preference.switch.improvement"))
        assertEquals("0ns", effective.text("preference.switch.tier_restore"))
        assertEquals("1.00000000000000000000000001", effective.text("preference.switch.speed_margin"))
        assertEquals("false", effective.text("throughput.enabled"))
        assertEquals("1", effective.text("throughput.streams"))
        assertEquals("false", effective.text("interrupt_on_failover"))
    }

    @Test
    fun fullDraftExpansionKeepsOriginalExactJson() {
        val draft = SmartEditorPolicy(fullPolicy)
        assertSame(draft, draft.withDefaults(draft.objectValue))
        assertEquals(fullPolicy, draft.source)
    }
}
