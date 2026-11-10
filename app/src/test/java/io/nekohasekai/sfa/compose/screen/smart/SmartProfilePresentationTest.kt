package io.nekohasekai.sfa.compose.screen.smart

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartProfilePresentationTest {
    @Test
    fun absentAndLegacyPreferencesUseClassicEvenIfLegacyStatusHasCustomFlag() {
        assertEquals("default", smartRunningProfileName(null))
        assertEquals("default", smartRunningProfileName(preference("legacy", "phone", true)))
    }

    @Test
    fun configurationLabelsUseRunningBaseAndPresenceBasedCustomFlag() {
        assertEquals("phone", smartRunningProfileName(preference("profile", "phone", false)))
        assertEquals("custom", smartRunningProfileName(preference("profile", "phone", true)))
        assertEquals("custom", smartRunningProfileName(preference("profile", "server", true)))
        assertEquals("future-profile", smartRunningProfileName(preference("profile", "future-profile", false)))
    }

    private fun preference(engine: String, base: String, custom: Boolean) = SmartPreference(
        engine = engine,
        base = base,
        custom = custom,
        source = "config",
        overrideHash = "",
        resolved = JsonObject(emptyMap()),
    )
}
