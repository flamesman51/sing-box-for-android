package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SmartPlatformTest {
    @Test
    fun countryUsesOverrideThenSavedSelectionThenAutomaticChain() {
        val countries = listOf("RU", "CN", "IR", "KZ", "DE", "US")
        for (firstAvailable in countries.indices) {
            val values = countries.mapIndexed { index, country -> country.takeIf { index >= firstAvailable } }
            assertEquals(
                "First available country at index $firstAvailable",
                countries[firstAvailable],
                selectSmartCountry(values[0], values[1], values[2], values[3], values[4], values[5]),
            )
        }
        assertEquals("", selectSmartCountry())
    }

    @Test
    fun countrySkipsMissingInvalidAndNonCountryRegions() {
        assertEquals("US", selectSmartCountry(" ", "ZZ", "001", "USA", "GMT+03:00", "us"))
        assertEquals("DE", selectSmartCountry(networkCountry = "12", simCountry = "DE"))
        assertEquals("", selectSmartCountry(localeCountry = "001"))
        assertEquals("", selectSmartCountry(countryOverride = "ır"))
    }

    @Test
    fun countryNormalizesWhitespaceAndAsciiCase() {
        assertEquals("RU", selectSmartCountry(networkCountry = " ru "))
        assertEquals("IR", selectSmartCountry(countryOverride = "iR"))
    }

    @Test
    fun countryNormalizationDoesNotDependOnDeviceLocale() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("IR", selectSmartCountry(countryOverride = "ir"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun firstAutomaticCountrySurvivesLocaleAndNetworkChanges() {
        val saved = selectSmartCountry(networkCountry = "ru", localeCountry = "US")
        assertEquals("RU", selectSmartCountry(automaticCountry = saved, networkCountry = "DE", localeCountry = "DE"))
        assertEquals("CN", selectSmartCountry(countryOverride = "CN", automaticCountry = saved))
        assertEquals("RU", selectSmartCountry(countryOverride = "", automaticCountry = saved))
    }

    @Test
    fun cellTimestampKeepsOriginalObservationAge() {
        val now = 1_800_000_000_000L
        val observed = 300_000_000_000L
        assertEquals(now - 150_000L, cellTimestampToUnixMillis(observed, 450_000_000_000L, now))
        assertEquals(
            now - 150_000L,
            cellTimestampToUnixMillis(observed, 480_000_000_000L, now + 30_000L),
        )
    }

    @Test
    fun cellTimestampConvertsNanosecondsWithoutOverflow() {
        val now = 1_800_000_000_000L
        assertEquals(now, cellTimestampToUnixMillis(1_000_001L, 2_000_000L, now))
        assertEquals(now - 1L, cellTimestampToUnixMillis(1_000_000L, 2_000_000L, now))
        assertEquals(now, cellTimestampToUnixMillis(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, now))
        assertEquals(Long.MAX_VALUE - 1, cellTimestampToUnixMillis(1L, 1_000_001L, Long.MAX_VALUE))
    }

    @Test
    fun cellTimestampRejectsUnknownFutureAndInvalidClockValues() {
        val now = 1_800_000_000_000L
        for (timestamp in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 3_000_000L)) {
            assertEquals("Timestamp $timestamp", 0L, cellTimestampToUnixMillis(timestamp, 2_000_000L, now))
        }
        assertEquals(0L, cellTimestampToUnixMillis(1L, -1L, now))
        assertEquals(0L, cellTimestampToUnixMillis(1L, 1L, 0L))
        assertEquals(0L, cellTimestampToUnixMillis(1L, 1L, -1L))
        assertEquals(0L, cellTimestampToUnixMillis(1L, 2_000_001L, 1L))
    }
}
