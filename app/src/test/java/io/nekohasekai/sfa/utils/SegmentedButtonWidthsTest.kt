package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentedButtonWidthsTest {
    @Test
    fun shortLabelsKeepSeventyTwoDpMinimumAndFitAtTheExactBoundary() {
        assertEquals(listOf(72f, 72f), fittingSegmentWidths(listOf(10, 20), 1f, 144f))
        assertNull(fittingSegmentWidths(listOf(10, 20), 1f, 143f))
    }

    @Test
    fun longLabelsGetTheirOwnWidthAndPaddingRatherThanEqualThirds() {
        assertEquals(listOf(72f, 72f, 146f), fittingSegmentWidths(listOf(30, 40, 120), 1f, 290f))
        assertNull(fittingSegmentWidths(listOf(30, 40, 120), 1f, 289f))
    }

    @Test
    fun densityScalesPaddingAndMinimumWhileMeasuredLabelsIncludeFontScale() {
        assertEquals(listOf(144f, 212f), fittingSegmentWidths(listOf(50, 160), 2f, 356f))
        assertNull(fittingSegmentWidths(listOf(100, 320), 2f, 356f))
    }
}
