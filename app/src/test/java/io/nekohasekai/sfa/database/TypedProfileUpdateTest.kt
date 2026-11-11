package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class TypedProfileUpdateTest {
    @Test
    fun successfulCheckResetsLastUpdatedAndCadence() {
        val profile =
            TypedProfile().apply {
                lastUpdated = Date(1_000L)
                autoUpdateInterval = 60
                lastUpdateError = "Previous update failed"
            }
        val checkedAt = Date(3_601_000L)
        assertEquals(0L, profile.nextUpdateDelayMillis(checkedAt.time))

        // An unchanged download still counts as an update: "Last updated" must reset.
        profile.markUpdateSuccess(checkedAt)

        assertEquals(checkedAt, profile.lastUpdated)
        assertNull(profile.lastUpdateError)
        assertEquals(3_600_000L, profile.nextUpdateDelayMillis(checkedAt.time))
    }
}
