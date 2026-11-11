package io.nekohasekai.sfa.database

import io.nekohasekai.sfa.database.preference.KeyValueEntity
import io.nekohasekai.sfa.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sfa.ktx.string
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPolicyPersistenceTest {
    @Test
    fun realPreferenceDelegateDefaultsToEmptyAndRoundTripsExactStoredBytes() =
        runBlocking {
            val dao = MemoryDao()
            val store = RoomPreferenceDataStore { dao }
            val settings = PolicyPreferences(store)
            assertEquals("", settings.smartPolicy)

            val values =
                listOf(
                    "{\"preference\":{\"profile\":\"default\"}}",
                    "{\"preference\":{\"profile\":\"phone\"}}",
                    """ { "preference": { "profile": "phone", "rank": ["latency", "udp"], "speed_floor_hysteresis": 0.123456789 }, "throughput": { "target_mbps": 17.123456789, "enabled": false } }
""",
                    "{\"preference\":{\"profile\":\"future-é\"}}",
                    "",
                )
            for (value in values) {
                settings.smartPolicy = value
                assertEquals(value, settings.smartPolicy)
                store.flush()
                assertEquals(value, dao[Settings.SMART_POLICY_KEY]?.string)
                val reopened = PolicyPreferences(RoomPreferenceDataStore { dao })
                assertEquals(value, reopened.smartPolicy)
            }
        }

    private class PolicyPreferences(
        store: RoomPreferenceDataStore,
    ) {
        var smartPolicy by store.string(Settings.SMART_POLICY_KEY) { "" }
    }

    private class MemoryDao : KeyValueEntity.Dao {
        private val values = mutableMapOf<String, KeyValueEntity>()

        override fun all(): List<KeyValueEntity> = values.values.toList()

        override fun get(key: String): KeyValueEntity? = values[key]

        override fun put(value: KeyValueEntity): Long {
            values[value.key] = value
            return 1L
        }

        override fun delete(key: String): Int = if (values.remove(key) == null) 0 else 1

        override fun reset(): Int = values.size.also { values.clear() }

        override fun insert(list: List<KeyValueEntity>) {
            list.forEach { put(it) }
        }
    }
}
