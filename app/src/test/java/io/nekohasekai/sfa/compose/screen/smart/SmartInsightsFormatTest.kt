package io.nekohasekai.sfa.compose.screen.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class SmartInsightsFormatTest {
    @Test
    fun displayTagSplitsOnlyATrailingAddress() {
        assertEquals("Server" to "address", displayTag("Server [address]"))
        assertEquals("Server [region]" to "address", displayTag("Server [region] [address]"))
        assertEquals("Server" to null, displayTag("Server"))
        assertEquals("Server [address] suffix" to null, displayTag("Server [address] suffix"))
        assertEquals("Server[address]" to null, displayTag("Server[address]"))
    }

    @Test
    fun duplicateDisplayNamesIncludeOnlyTheirBracketAddresses() {
        val first = "Shared [first]"
        val second = "Shared [second]"
        val unique = "Unique [third]"
        assertEquals(mapOf(first to "first", second to "second"), duplicateServerAddresses(listOf(first, second, unique)))
        assertEquals(mapOf(first to "first"), duplicateServerAddresses(listOf(first, "Shared")))
        assertTrue(duplicateServerAddresses(listOf(unique)).isEmpty())
        assertTrue(duplicateServerAddresses(listOf("Shared", "Shared []")).isEmpty())
    }

    @Test
    fun eventServersUseAnArrowOnlyWithBothEndpoints() {
        assertEquals("First → Second", smartEventServers("First [first]", "Second [second]"))
        assertEquals("First", smartEventServers("First [first]", null))
        assertEquals("Second", smartEventServers(null, "Second [second]"))
        assertEquals("Second", smartEventServers("", "Second [second]"))
        assertEquals("First", smartEventServers("First [first]", " "))
        assertNull(smartEventServers(null, null))
        assertNull(smartEventServers("", " "))
    }

    @Test
    fun adjacentDuplicateEventsKeepTheNewestEventAndOldestTime() {
        val newest = event(30)
        assertEquals(listOf(SmartEventGroup(newest, 3, 10)), mergeSmartEvents(listOf(event(10), newest, event(20))))
        assertEquals(listOf(SmartEventGroup(newest, 1, 30)), mergeSmartEvents(listOf(newest)))
        assertTrue(mergeSmartEvents(emptyList()).isEmpty())
    }

    @Test
    fun identicalEventsSeparatedByAnotherEventAreNotMerged() {
        val middle = event(20).copy(kind = "recover")
        assertEquals(
            listOf(SmartEventGroup(event(30), 1, 30), SmartEventGroup(middle, 1, 20), SmartEventGroup(event(10), 1, 10)),
            mergeSmartEvents(listOf(event(10), event(30), middle)),
        )
    }

    @Test
    fun everyEventIdentityFieldMustMatchBeforeMerging() {
        val newest = event(30)
        val differentEvents = listOf(
            newest.copy(time = 20, kind = "recover"),
            newest.copy(time = 20, from = "Server [second]"),
            newest.copy(time = 20, to = "Server [second]"),
            newest.copy(time = 20, reason = "different reason"),
            newest.copy(time = 20, network = "different-network"),
        )
        differentEvents.forEach { different ->
            assertEquals(listOf(SmartEventGroup(newest, 1, 30), SmartEventGroup(different, 1, 20)), mergeSmartEvents(listOf(newest, different)))
        }
        assertEquals(2, mergeSmartEvents(listOf(newest, newest.copy(time = 20, to = ""))).size)
    }

    @Test
    fun repeatedEventsCountAsOneRowBeforeTheTwentyRowLimit() {
        val repeated = List(25) { event(100L - it) }
        assertEquals(1, mergeSmartEvents(repeated).size)
        val distinct = List(20) { event(20L - it).copy(reason = "Reason $it") }
        val merged = mergeSmartEvents(repeated + distinct)
        assertEquals(21, merged.size)
        assertEquals(20, merged.take(20).size)
        assertEquals(25, merged.first().count)
        assertEquals(100L, merged.first().event.time)
        assertEquals(76L, merged.first().oldestTime)
    }

    private fun event(time: Long) = SmartEvent(time, "quarantine", "Server [first]", null, "network-key", "reason")

    @Test
    fun safeNetworkLabelRemovesMacsAndUsesLocalizedFallbacks() {
        assertEquals("Guest", safeNetworkLabel("Guest aA:bB:cC:dD:eE:fF", "Carrier", "Wi‑Fi"))
        assertEquals("Carrier", safeNetworkLabel("AA:BB:CC:DD:EE:FF", "Carrier", "Wi‑Fi"))
        assertEquals("Carrier", safeNetworkLabel("  ", "Carrier", "Mobile"))
        assertEquals("Мобильная сеть", safeNetworkLabel(null, " ", "Мобильная сеть"))
        assertEquals("Wi‑Fi", safeNetworkLabel("wifi/bssid:0123456789abcdef", null, "Wi‑Fi"))
        assertEquals("Wi‑Fi", safeNetworkLabel("0123456789abcdef", "AA:BB:CC:DD:EE:FF", "Wi‑Fi"))
        assertEquals("Unnamed Wi‑Fi network", safeNetworkLabel("", "", "Unnamed Wi‑Fi network"))
        assertEquals("Мобильная сеть без имени", safeNetworkLabel(null, null, "Мобильная сеть без имени"))
    }

    @Test
    fun networkLookupNeverFallsBackToTheKey() {
        val network = SmartNetworkHistory("network-key", "wifi", "Guest AA:BB:CC:DD:EE:FF", null, null, 1, 1.0, emptyList())
        assertEquals("Guest", networkLabelForKey("network-key", listOf(network), null) { "Wi‑Fi" })
        assertEquals("Другая", networkLabelForKey("network-key", listOf(network.copy(label = null)), null) { "Другая" })
        assertNull(networkLabelForKey("missing", listOf(network), null) { "Wi‑Fi" })
        assertNull(networkLabelForKey(null, listOf(network), null) { "Wi‑Fi" })
    }

    @Test
    fun currentNetworkLabelTakesPrecedenceOverHistoryForMatchingKeys() {
        val history = listOf(network("network-key", "wifi", "History", null, 1, 1))
        val current = SmartNetwork("network-key", "wifi", "Current Guest AA:BB:CC:DD:EE:FF", null, null, null)
        val fallback = { _: String -> "Unnamed Wi‑Fi network" }
        assertEquals("Current Guest", networkLabelForKey("network-key", history, current, fallback))
        assertEquals("Current Guest", networkLabelForKey("network-key", emptyList(), current, fallback))
        assertEquals("History", networkLabelForKey("network-key", history, current.copy(key = "different-network"), fallback))
        assertEquals("Carrier", networkLabelForKey("network-key", history, current.copy(label = null, carrier = "Carrier"), fallback))
        assertEquals("Unnamed Wi‑Fi network", networkLabelForKey("network-key", history, current.copy(label = null), fallback))
        assertNull(networkLabelForKey("missing", history, current, fallback))
    }

    @Test
    fun networkGroupsUseSafeLabelAndTypeWithMaximumCountsAndNewestFirst() {
        val networks = listOf(
            network("first", "wifi", "Guest AA:BB:CC:DD:EE:FF", null, 30, 2),
            network("second", "wifi", "Guest BB:CC:DD:EE:FF:AA", null, 20, 5),
            network("third", "cellular", "Guest", null, 40, 3),
            network("fourth", "wifi", "Separate", null, 10, 1),
        )
        assertEquals(
            listOf(
                SmartNetworkGroup("Guest", "cellular", 1, 3, 40),
                SmartNetworkGroup("Guest", "wifi", 2, 5, 30),
                SmartNetworkGroup("Separate", "wifi", 1, 1, 10),
            ),
            groupSmartNetworks(networks) { "Fallback" },
        )
        assertTrue(groupSmartNetworks(emptyList()) { "Fallback" }.isEmpty())
    }

    @Test
    fun networkGroupsAndEventsShareUnnamedNetworkFallbacks() {
        val networks = listOf(
            network("first", "wifi", "", "", 10, 1),
            network("second", "wifi", "AA:BB:CC:DD:EE:FF", null, 20, 2),
            network("third", "cellular", null, " ", 30, 1),
            network("fourth", "other", null, null, 40, 1),
            network("fifth", "cellular", null, "Carrier", 50, 2),
        )
        val fallback = { type: String ->
            when (type) {
                "wifi" -> "Unnamed Wi‑Fi network"
                "cellular" -> "Unnamed mobile network"
                else -> "Other"
            }
        }
        assertEquals(
            listOf(
                SmartNetworkGroup("Carrier", "cellular", 1, 2, 50),
                SmartNetworkGroup("Other", "other", 1, 1, 40),
                SmartNetworkGroup("Unnamed mobile network", "cellular", 1, 1, 30),
                SmartNetworkGroup("Unnamed Wi‑Fi network", "wifi", 2, 2, 20),
            ),
            groupSmartNetworks(networks, fallback),
        )
        assertEquals("Unnamed Wi‑Fi network", networkLabelForKey("first", networks, null, fallback))
        assertEquals("Unnamed mobile network", networkLabelForKey("third", networks, null, fallback))
        assertEquals("Other", networkLabelForKey("fourth", networks, null, fallback))
    }

    private fun network(key: String, type: String, label: String?, carrier: String?, lastSeen: Long, servers: Int) = SmartNetworkHistory(
        key,
        type,
        label,
        carrier,
        null,
        lastSeen,
        1.0,
        List(servers) { SmartNetworkMember("Server $it", null, 1.0, null, null) },
    )

    @Test
    fun joinPartsOmitsNullAndBlankParts() {
        assertEquals("Current · Healthy · 12 ms", joinParts(null, "Current", "", "Healthy", " ", "12 ms", null))
        assertEquals("", joinParts(null, "", " "))
    }

    @Test
    fun budgetProgressOmitsInvalidValuesAndClampsValidRatios() {
        assertNull(budgetProgress(0.0, 0.0))
        assertNull(budgetProgress(1.0, -1.0))
        assertNull(budgetProgress(Double.NaN, 10.0))
        assertNull(budgetProgress(1.0, Double.NaN))
        assertNull(budgetProgress(Double.POSITIVE_INFINITY, 10.0))
        assertNull(budgetProgress(1.0, Double.POSITIVE_INFINITY))
        assertEquals(0f, budgetProgress(-1.0, 10.0))
        assertEquals(1f, budgetProgress(12.0, 10.0))
        assertEquals(0.25f, budgetProgress(2.5, 10.0))
    }

    @Test
    fun samplesRequireAtLeastOneFiniteValue() {
        assertFalse(hasSamples(listOf(null, null)))
        assertFalse(hasSamples(emptyList()))
        assertFalse(hasSamples(listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)))
        assertTrue(hasSamples(listOf(null, 0.0)))
    }

    @Test
    fun numbersUseTheAppLocaleAndOmitNonFiniteValues() {
        assertEquals("12.5 Mbit/s", formatSmartNumber(12.5, "%.1f Mbit/s", Locale.US))
        assertEquals("12,5 Мбит/с", formatSmartNumber(12.5, "%.1f Мбит/с", Locale.forLanguageTag("ru")))
        assertEquals("1,234.5 MB", formatSmartNumber(1234.5, "%,.1f MB", Locale.US))
        assertNull(formatSmartNumber(null, "%.1f", Locale.US))
        assertNull(formatSmartNumber(Double.NaN, "%.1f", Locale.US))
        assertNull(formatSmartNumber(Double.POSITIVE_INFINITY, "%.1f", Locale.US))
    }

    @Test
    fun megabytesUseWholeNumbersAtOneHundredAndLocalizedDecimalsBelow() {
        fun format(value: Double?, locale: Locale = Locale.US): String? = formatSmartMegabytes(value, "%,.1f MB", "%,.0f MB", locale)
        assertEquals("0.0 MB", format(0.0))
        assertEquals("99.9 MB", format(99.9))
        assertEquals("100 MB", format(100.0))
        assertEquals("1,235 MB", format(1234.6))
        assertEquals("12,5 MB", format(12.5, Locale.forLanguageTag("ru")))
        assertEquals("1\u00a0235 MB", format(1234.6, Locale.forLanguageTag("ru")))
        assertNull(format(null))
        assertNull(format(Double.NaN))
        assertNull(format(Double.POSITIVE_INFINITY))
        assertNull(format(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun timePatternAddsDayAndMonthOnlyOutsideToday() {
        val zone = TimeZone.getTimeZone("UTC")
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = zone }
        fun seconds(value: String): Long = parser.parse(value)!!.time / 1000
        val now = seconds("2026-01-01 12:00")
        assertNull(smartTimeDateSkeleton(seconds("2026-01-01 00:01"), now, zone))
        assertNull(smartTimeDateSkeleton(seconds("2026-01-01 23:59"), now, zone))
        assertEquals("dMMM", smartTimeDateSkeleton(seconds("2025-12-31 23:59"), now, zone))
        assertEquals("dMMM", smartTimeDateSkeleton(seconds("2025-01-01 12:00"), now, zone))
        assertEquals("dMMM", smartTimeDateSkeleton(seconds("2026-01-02 00:00"), now, zone))
        val offset = TimeZone.getTimeZone("GMT+02:00")
        assertNull(smartTimeDateSkeleton(seconds("2025-12-31 23:30"), seconds("2026-01-01 00:30"), offset))
    }
}
