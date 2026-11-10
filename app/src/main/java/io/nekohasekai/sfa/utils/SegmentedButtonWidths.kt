package io.nekohasekai.sfa.utils

internal fun fittingSegmentWidths(labelWidths: List<Int>, density: Float, availableWidth: Float): List<Float>? {
    val widths = labelWidths.map { maxOf(it + 26f * density, 72f * density) }
    return widths.takeIf { it.sum() <= availableWidth }
}
