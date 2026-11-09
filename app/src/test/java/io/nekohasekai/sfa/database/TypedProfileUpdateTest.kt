package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Date

class TypedProfileUpdateTest {
    @Test
    fun unchangedSuccessAdvancesCadenceWithoutChangingContentTimestamp() {
        val profile = TypedProfile().apply {
            lastUpdated = Date(1_000L)
            autoUpdateInterval = 60
            lastUpdateError = "Previous update failed"
        }
        val checkedAt = Date(3_601_000L)
        assertEquals(0L, profile.nextUpdateDelayMillis(checkedAt.time))

        profile.markUpdateSuccess(contentChanged = false, checkedAt = checkedAt)

        assertEquals(1_000L, profile.lastUpdated.time)
        assertEquals(checkedAt, profile.lastChecked)
        assertNull(profile.lastUpdateError)
        assertEquals(3_600_000L, profile.nextUpdateDelayMillis(checkedAt.time))
        assertEquals(0L, profile.nextUpdateDelayMillis(checkedAt.time + 3_600_000L))

        val changedAt = Date(checkedAt.time + 3_600_000L)
        profile.lastUpdateError = "Another update failed"
        profile.markUpdateSuccess(contentChanged = true, checkedAt = changedAt)

        assertEquals(changedAt, profile.lastUpdated)
        assertEquals(changedAt, profile.lastChecked)
        assertNull(profile.lastUpdateError)
    }
}
