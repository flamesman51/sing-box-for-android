package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.sfa.constant.Status
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmartProfileInitializationTest {
    private val phone = policy("""{"preference":{"profile":"phone"},"throughput":{"target_mbps":20}}""")
    private val desktop = policy("""{"preference":{"profile":"desktop"},"throughput":{"target_mbps":40}}""")
    private val catalog = listOf(SmartProfileEntry("phone", phone), SmartProfileEntry("desktop", desktop))

    @Test
    fun loadingDefersUntilResolvedSnapshotAndPreservesDifferingGroupsWarning() {
        assertNull(smartProfileInitialValues("", catalog, SmartPolicySnapshot(loading = true)))
        val resolved = policy("""{"preference":{"profile":"desktop","rank":["udp","latency"]},"throughput":{"target_mbps":17.123456789}}""")
        val snapshot = SmartPolicySnapshot(listOf(group("first", resolved), group("second", phone)))
        assertEquals(SmartProfileInitialValues(resolved.toString(), "first"), smartProfileInitialValues("", catalog, snapshot))
    }

    @Test
    fun unavailableStatusUsesPhoneIncludingWhenServiceIsStopped() {
        assertEquals(SmartProfileInitialValues(phone.toString()), smartProfileInitialValues("", catalog, SmartPolicySnapshot()))
        val stopped = smartPolicySnapshotForTarget(Status.Stopped, remote = false, SmartPolicySnapshot(loading = true))
        assertEquals(SmartProfileInitialValues(phone.toString()), smartProfileInitialValues("", catalog, stopped))
    }

    @Test
    fun storedCustomWinsExactlyEvenWhileStatusIsLoading() {
        val stored = """ { "preference": { "profile": "desktop", "rank": ["udp"] }, "throughput": { "target_mbps": 12.3456789 } } """
        assertEquals(SmartProfileInitialValues(stored), smartProfileInitialValues(stored, catalog, SmartPolicySnapshot(loading = true)))
    }

    @Test
    fun storedNamedUsesCatalogBeforeRunningPolicyOrLoading() {
        val stored = smartNamedPolicy("desktop")
        assertEquals(SmartProfileInitialValues(desktop.toString()), smartProfileInitialValues(stored, catalog, SmartPolicySnapshot(loading = true)))
        assertEquals(
            SmartProfileInitialValues(desktop.toString()),
            smartProfileInitialValues(stored, catalog, SmartPolicySnapshot(listOf(group("running", phone)))),
        )
    }

    @Test
    fun equalResolvedPoliciesDoNotShowWarning() {
        val snapshot = SmartPolicySnapshot(listOf(group("first", desktop), group("second", desktop)))
        assertEquals(SmartProfileInitialValues(desktop.toString()), smartProfileInitialValues("", catalog, snapshot))
    }

    @Test
    fun noGroupsOrFirstGroupWithoutResolvedPolicyUsesPhone() {
        for (groups in listOf(emptyList(), listOf(group("legacy", null), group("second", desktop)))) {
            assertEquals(SmartProfileInitialValues(phone.toString()), smartProfileInitialValues("", catalog, SmartPolicySnapshot(groups)))
        }
    }

    private fun policy(source: String): JsonObject = Json.parseToJsonElement(source) as JsonObject

    private fun group(tag: String, resolved: JsonObject?): SmartStatus {
        val fixture = requireNotNull(javaClass.getResourceAsStream("/smart/status.json"))
            .bufferedReader().use { it.readText() }
        return SmartJson.status(fixture).copy(
            group = tag,
            preference = resolved?.let { SmartPreference("profile", "desktop", true, "config", "", it) },
        )
    }
}
