package io.nekohasekai.sfa.compose.screen.smart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartMeasurementTest {
    @Test
    fun absentAndNullMeasurementFieldsRemainUnknown() {
        val fields = setOf("download", "upload", "upload_status", "udp_rtt_ms", "jitter_ms", "observed_peak_down", "floor_state", "legacy_episode_obs")
        for (replacement in listOf<Map<String, JsonElement>>(emptyMap(), fields.associateWith { JsonNull })) {
            val base = document()
            val original = base.getValue("members").let { it as JsonArray }.first().jsonObject
            val member = JsonObject(original.filterKeys { it !in fields } + replacement)
            val status = SmartJson.status(JsonObject(base.filterKeys { it != "upload_verified" } + ("members" to JsonArray(listOf(member)))).toString())
            val parsed = status.members.single()
            assertNull(status.uploadVerified)
            assertNull(parsed.download)
            assertNull(parsed.upload)
            assertNull(parsed.uploadStatus)
            assertNull(parsed.udpRttMs)
            assertNull(parsed.jitterMs)
            assertNull(parsed.observedPeakDown)
            assertNull(parsed.floorState)
            assertNull(parsed.legacyEpisodeObs)
            assertNull(parsed.download.displayValue())
            assertNull(parsed.download.displayP10())
            assertNull(parsed.uploadValue(status.uploadVerified))
            assertFalse(parsed.isUploadUnverified(status.uploadVerified))
            assertNull(parsed.observedDownloadValue())
            assertNull(parsed.effectiveFloorState())
            assertNull(parsed.legacyEpisodeObservation("legacy"))
        }
    }

    @Test
    fun parsesEveryNestedDirectionFieldAndCoreFlags() {
        val member = member("""{
            "download":{"value":48.1,"p10":40.2,"mean":45.3,"streams":4,"window":5.5,"source":"active","at":1,"valid":true,"fresh":true},
            "upload":{"value":12.5,"post_min":10.2,"streams":2,"window":6,"source":"active","at":2,"valid":true,"fresh":true},
            "upload_status":"verified","udp_rtt_ms":14,"jitter_ms":3,"observed_peak_down":52.4,
            "floor_state":{"kind":"proven","target":30,"value":32,"at":3,"effective":true},
            "legacy_episode_obs":{"value":28.5,"at":4}
        }""")
        val download = requireNotNull(member.download)
        assertEquals(48.1, download.displayValue()!!, 0.0)
        assertEquals(40.2, download.displayP10()!!, 0.0)
        assertEquals(45.3, download.mean!!, 0.0)
        assertEquals(4, download.streams)
        assertEquals(5.5, download.window!!, 0.0)
        assertEquals("active", download.source)
        assertEquals(1L, download.at)
        val upload = requireNotNull(member.upload)
        assertEquals(12.5, member.uploadValue(true)!!, 0.0)
        assertEquals(10.2, upload.postMin!!, 0.0)
        assertEquals(2, upload.streams)
        assertEquals(6.0, upload.window!!, 0.0)
        assertEquals(2L, upload.at)
        assertEquals("verified", member.uploadStatus)
        assertEquals(14.0, member.udpRttMs.knownMeasurement()!!, 0.0)
        assertEquals(3.0, member.jitterMs.knownMeasurement()!!, 0.0)
        assertEquals(52.4, member.observedDownloadValue()!!, 0.0)
        assertEquals(SmartFloorState("proven", 30.0, 3L, 32.0, true), member.effectiveFloorState())
        assertEquals(SmartLegacyEpisodeObservation(28.5, 4L), member.legacyEpisodeObs)
    }

    @Test
    fun directionsRequireBothCoreFlagsWithoutTimestampFreshness() {
        val fresh = SmartDirectionMeasurement(value = 10.0, p10 = 8.0, at = 1L, valid = true, fresh = true)
        assertEquals(10.0, fresh.displayValue()!!, 0.0)
        assertEquals(8.0, fresh.displayP10()!!, 0.0)
        for (valid in listOf(null, false, true)) {
            for (freshFlag in listOf(null, false, true)) {
                if (valid == true && freshFlag == true) continue
                val direction = fresh.copy(valid = valid, fresh = freshFlag, at = Long.MAX_VALUE)
                assertNull(direction.displayValue())
                assertNull(direction.displayP10())
                assertNull(member("{}").copy(upload = direction).uploadValue(true))
            }
        }
        assertEquals(10.0, fresh.copy(at = null).displayValue()!!, 0.0)
    }

    @Test
    fun incompleteDirectionRecordsNeverInventValues() {
        for (record in listOf("{}", """{"valid":true,"fresh":true}""", """{"value":10,"p10":8}""", """{"value":10,"p10":8,"valid":false,"fresh":true}""", """{"value":10,"p10":8,"valid":true,"fresh":false}""")) {
            val parsed = member("""{"download":$record,"upload":$record}""")
            assertNull(parsed.download.displayValue())
            assertNull(parsed.download.displayP10())
            assertNull(parsed.uploadValue(null))
        }
        val missingP10 = member("""{"download":{"value":10,"valid":true,"fresh":true}}""")
        assertEquals(10.0, missingP10.download.displayValue()!!, 0.0)
        assertNull(missingP10.download.displayP10())
    }

    @Test
    fun unverifiedGroupOrMemberTakesPrecedenceOverFreshUpload() {
        val verified = member("""{"upload":{"value":12.5,"valid":true,"fresh":true},"upload_status":"verified"}""")
        for (groupVerified in listOf(null, true)) {
            assertFalse(verified.isUploadUnverified(groupVerified))
            assertEquals(12.5, verified.uploadValue(groupVerified)!!, 0.0)
            val unverified = verified.copy(uploadStatus = "unverified")
            assertTrue(unverified.isUploadUnverified(groupVerified))
            assertNull(unverified.uploadValue(groupVerified))
        }
        for (uploadStatus in listOf(null, "unknown", "inconclusive", "verified", "unverified")) {
            val member = verified.copy(uploadStatus = uploadStatus)
            assertTrue(member.isUploadUnverified(false))
            assertNull(member.uploadValue(false))
        }
        val base = document()
        for (flag in listOf(false, true)) {
            assertEquals(flag, SmartJson.status(JsonObject(base + ("upload_verified" to JsonPrimitive(flag))).toString()).uploadVerified)
        }
        assertNull(SmartJson.status(JsonObject(base + ("upload_verified" to JsonNull)).toString()).uploadVerified)
    }

    @Test
    fun invalidMetricsStayHiddenAndZeroIsKnownExceptForPassiveDiagnostic() {
        val valid = SmartDirectionMeasurement(value = 10.0, p10 = 8.0, valid = true, fresh = true)
        val base = member("{}")
        for (invalid in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(invalid.knownMeasurement())
            assertNull(valid.copy(value = invalid).displayValue())
            assertNull(valid.copy(value = invalid).displayP10())
            assertNull(valid.copy(p10 = invalid).displayP10())
            assertNull(base.copy(upload = valid.copy(value = invalid)).uploadValue(true))
            assertNull(base.copy(observedPeakDown = invalid).observedDownloadValue())
            assertNull(base.copy(udpRttMs = invalid).udpRttMs.knownMeasurement())
            assertNull(base.copy(jitterMs = invalid).jitterMs.knownMeasurement())
        }
        assertEquals(0.0, valid.copy(value = 0.0, p10 = 0.0).displayValue()!!, 0.0)
        assertEquals(0.0, valid.copy(p10 = 0.0).displayP10()!!, 0.0)
        assertEquals(0.0, 0.0.knownMeasurement()!!, 0.0)
        assertNull(base.copy(observedPeakDown = 0.0).observedDownloadValue())
        assertEquals(0.1, base.copy(observedPeakDown = 0.1).observedDownloadValue()!!, 0.0)
        val negative = member("""{"udp_rtt_ms":-1,"jitter_ms":-2,"observed_peak_down":-3,"download":{"value":-4,"p10":-5,"valid":true,"fresh":true}}""")
        assertNull(negative.udpRttMs.knownMeasurement())
        assertNull(negative.jitterMs.knownMeasurement())
        assertNull(negative.observedDownloadValue())
        assertNull(negative.download.displayValue())
    }

    @Test
    fun floorStateRequiresEffectiveKnownRecordWithoutAppFreshness() {
        val base = member("{}")
        val proven = SmartFloorState("proven", 30.0, 1L, 32.0, true)
        for (kind in listOf("proven", "revoked")) {
            val state = proven.copy(kind = kind)
            assertEquals(state, base.copy(floorState = state).effectiveFloorState())
        }
        for (state in listOf(
            SmartFloorState(), proven.copy(effective = false), proven.copy(effective = null),
            proven.copy(kind = "unknown"), proven.copy(kind = null), proven.copy(target = null),
            proven.copy(target = -1.0), proven.copy(target = Double.NaN), proven.copy(target = Double.POSITIVE_INFINITY),
            proven.copy(value = null), proven.copy(value = -1.0), proven.copy(value = Double.NaN), proven.copy(value = Double.POSITIVE_INFINITY),
            proven.copy(at = null), proven.copy(at = 0L), proven.copy(at = -1L),
        )) {
            assertNull(base.copy(floorState = state).effectiveFloorState())
        }
        val expired = member("""{"floor_state":{"kind":"proven","target":30,"value":32,"at":1800000000,"effective":false}}""")
        assertNull(expired.effectiveFloorState())
        assertNull(member("""{"floor_state":{}}""").effectiveFloorState())
    }

    @Test
    fun legacyEpisodeRequiresExactLegacyEngineAndUnderLoadSource() {
        val observation = SmartLegacyEpisodeObservation(20.0, 1L)
        val base = member("{}").copy(legacyEpisodeObs = observation)
        for (engine in listOf(null, "profile", "legacy")) {
            for (source in listOf(null, "active", "passive", "under_load")) {
                val actual = base.copy(throughputSource = source).legacyEpisodeObservation(engine)
                if (engine == "legacy" && source == "under_load") {
                    assertEquals(observation, actual)
                } else {
                    assertNull(actual)
                }
            }
        }
        val legacy = base.copy(throughputSource = "under_load")
        for (invalid in listOf(
            null, SmartLegacyEpisodeObservation(), observation.copy(value = -1.0),
            observation.copy(value = Double.NaN), observation.copy(value = Double.POSITIVE_INFINITY),
            observation.copy(at = 0L), observation.copy(at = -1L), observation.copy(at = null),
        )) {
            assertNull(legacy.copy(legacyEpisodeObs = invalid).legacyEpisodeObservation("legacy"))
        }
        assertEquals(observation.copy(value = 0.0), legacy.copy(legacyEpisodeObs = observation.copy(value = 0.0)).legacyEpisodeObservation("legacy"))
        assertNull(member("""{"legacy_episode_obs":{},"throughput_source":"under_load"}""").legacyEpisodeObservation("legacy"))
    }

    @Test
    fun readsCoreGeneratedFloorAndDiagnosticGates() {
        val status = fixtureStatus("status-measurement-floor.json")
        assertEquals("profile", status.preference?.engine)
        val primary = status.members.first()
        assertEquals("revoked", primary.effectiveFloorState()?.kind)
        assertEquals("proven", status.members[1].effectiveFloorState()?.kind)
        assertEquals(25.0, primary.effectiveFloorState()?.target!!, 0.0)
        assertEquals(14.0, primary.udpRttMs.knownMeasurement()!!, 0.0)
        assertEquals(0.0, primary.jitterMs.knownMeasurement()!!, 0.0)
        assertEquals(8.0, primary.observedDownloadValue()!!, 0.0)
        for (name in listOf("status-measurement-floor-inactive.json", "status-measurement-floor-superseded.json")) {
            val inactive = fixtureStatus(name)
            assertTrue(inactive.members.all { it.effectiveFloorState() == null })
            assertEquals(false, inactive.members.first().floorState?.effective)
        }
    }

    @Test
    fun readsCoreGeneratedLegacyEpisodeOnlyWhileItIsTheLegacySource() {
        val status = fixtureStatus("status-measurement-legacy.json")
        val primary = status.members.first()
        assertEquals("legacy", status.preference?.engine)
        assertEquals("under_load", primary.throughputSource)
        assertEquals(20.0, primary.legacyEpisodeObservation(status.preference?.engine)?.value!!, 0.0)
        assertNull(primary.legacyEpisodeObservation("profile"))
        assertNull(primary.copy(throughputSource = "active").legacyEpisodeObservation("legacy"))
    }

    @Test
    fun readsCoreGeneratedUnverifiedAndStaleMeasurementsWithoutShowingSpeed() {
        val unverified = fixtureStatus("status-measurement-upload-unverified.json")
        val primary = unverified.members.first()
        assertEquals(false, unverified.uploadVerified)
        assertEquals("unverified", primary.uploadStatus)
        assertEquals(12.5, primary.upload.displayValue()!!, 0.0)
        assertTrue(primary.isUploadUnverified(unverified.uploadVerified))
        assertNull(primary.uploadValue(unverified.uploadVerified))
        val stale = fixtureStatus("status-measurement-stale.json")
        val old = stale.members.first()
        assertEquals(true, old.download?.valid)
        assertEquals(false, old.download?.fresh)
        assertEquals(true, old.upload?.valid)
        assertEquals(false, old.upload?.fresh)
        assertNull(old.download.displayValue())
        assertNull(old.download.displayP10())
        assertNull(old.uploadValue(stale.uploadVerified))
    }

    @Test
    fun removedFlatMemberAliasesNeverSupplyMeasurements() {
        val old = member("""{"download":null,"upload":null,"download_mbps":999,"upload_mbps":999,"download_p10_mbps":888}""")
        assertNull(old.download.displayValue())
        assertNull(old.download.displayP10())
        assertNull(old.uploadValue(true))
    }

    private fun fixtureStatus(name: String): SmartStatus = SmartJson.status(
        requireNotNull(javaClass.getResourceAsStream("/smart/$name")).bufferedReader().use { it.readText() },
    )

    private fun document(): JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/smart/status.json")).bufferedReader().use { it.readText() },
    ).jsonObject

    private fun member(fields: String): SmartMember {
        val document = document()
        val original = (document.getValue("members") as JsonArray).first().jsonObject
        val member = JsonObject(original + Json.parseToJsonElement(fields).jsonObject)
        return SmartJson.status(JsonObject(document + ("members" to JsonArray(listOf(member)))).toString()).members.single()
    }
}
