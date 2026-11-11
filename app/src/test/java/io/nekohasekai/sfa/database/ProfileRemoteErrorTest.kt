package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileRemoteErrorTest {
    @Test
    fun lateFailurePreservesCurrentEditsAndIgnoresDeletedOrRetargetedProfiles() {
        val current =
            Profile(
                id = 1,
                name = "Edited name",
                typed =
                    TypedProfile().apply {
                        type = TypedProfile.Type.Remote
                        remoteURL = "https://feed.example/profile"
                        autoUpdateInterval = 30
                    },
            )
        val dao = MemoryProfiles(current)

        dao.updateRemoteError(1, "https://feed.example/profile", "Fetch failed")

        assertEquals("Edited name", current.name)
        assertEquals(30, current.typed.autoUpdateInterval)
        assertEquals("Fetch failed", current.typed.lastUpdateError)
        current.typed.remoteURL = "https://feed.example/replacement"
        assertNull(dao.updateRemoteError(1, "https://feed.example/profile", "Stale failure"))
        assertEquals("Fetch failed", current.typed.lastUpdateError)
        dao.delete(current)
        assertNull(dao.updateRemoteError(1, "https://feed.example/replacement", "Deleted failure"))
    }

    private class MemoryProfiles(
        profile: Profile,
    ) : Profile.Dao {
        private val profiles = mutableMapOf(profile.id to profile)

        override fun insert(profile: Profile): Long {
            if (profile.id == 0L) profile.id = nextFileID() ?: 1L
            profiles[profile.id] = profile
            return profile.id
        }

        override fun update(profile: Profile): Int {
            if (profile.id !in profiles) return 0
            profiles[profile.id] = profile
            return 1
        }

        override fun update(profile: List<Profile>): Int = profile.sumOf { update(it) }

        override fun delete(profile: Profile): Int = if (profiles.remove(profile.id) != null) 1 else 0

        override fun delete(profile: List<Profile>): Int = profile.sumOf { delete(it) }

        override fun get(profileId: Long): Profile? = profiles[profileId]

        override fun list(): List<Profile> = profiles.values.sortedBy { it.userOrder }

        override fun clear() = profiles.clear()

        override fun nextOrder(): Long? = profiles.values.maxOfOrNull { it.userOrder }?.plus(1)

        override fun nextFileID(): Long? = profiles.keys.maxOrNull()?.plus(1)
    }
}
