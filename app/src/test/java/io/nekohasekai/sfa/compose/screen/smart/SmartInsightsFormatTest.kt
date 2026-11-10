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
    fun safeNetworkLabelRemovesMacsAndUsesLocalizedFallbacks() {
        assertEquals("Guest", safeNetworkLabel("Guest aA:bB:cC:dD:eE:fF", "Carrier", "Wi‑Fi"))
        assertEquals("Carrier", safeNetworkLabel("AA:BB:CC:DD:EE:FF", "Carrier", "Wi‑Fi"))
        assertEquals("Carrier", safeNetworkLabel("  ", "Carrier", "Mobile"))
        assertEquals("Мобильная сеть", safeNetworkLabel(null, " ", "Мобильная сеть"))
        assertEquals("Wi‑Fi", safeNetworkLabel("wifi/bssid:0123456789abcdef", null, "Wi‑Fi"))
        assertEquals("Wi‑Fi", safeNetworkLabel("0123456789abcdef", "AA:BB:CC:DD:EE:FF", "Wi‑Fi"))
    }

    @Test
    fun networkLookupNeverFallsBackToTheKey() {
        val network = SmartNetworkHistory("network-key", "wifi", "Guest AA:BB:CC:DD:EE:FF", null, null, 1, 1.0, emptyList())
        assertEquals("Guest", networkLabelForKey("network-key", listOf(network)) { "Wi‑Fi" })
        assertEquals("Другая", networkLabelForKey("network-key", listOf(network.copy(label = null))) { "Другая" })
        assertNull(networkLabelForKey("missing", listOf(network)) { "Wi‑Fi" })
        assertNull(networkLabelForKey(null, listOf(network)) { "Wi‑Fi" })
    }

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
