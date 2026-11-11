package io.nekohasekai.sfa.compose.screen.smart

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Keeps the original JSON until a leaf actually changes; unrelated fields are never rebuilt. */
class SmartEditorPolicy(
    val source: String,
) {
    val objectValue: JsonObject = Json.parseToJsonElement(source) as JsonObject

    fun value(path: String): JsonElement? =
        path.split('.').fold(objectValue as JsonElement?) { node, key ->
            (node as? JsonObject)?.get(key)
        }

    fun text(path: String): String? = (value(path) as? JsonPrimitive)?.content

    fun withDefaults(base: JsonObject): SmartEditorPolicy {
        fun merge(
            defaults: JsonObject,
            overrides: JsonObject,
        ): JsonObject {
            var changed: MutableMap<String, JsonElement>? = null
            for ((key, fallback) in defaults) {
                val existing = overrides[key]
                val effective =
                    when {
                        existing == null -> fallback
                        existing is JsonObject && fallback is JsonObject -> merge(fallback, existing)
                        else -> existing
                    }
                if (effective !== existing) {
                    val target = changed ?: overrides.toMutableMap().also { changed = it }
                    target[key] = effective
                }
            }
            return changed?.let(::JsonObject) ?: overrides
        }
        val effective = merge(base, objectValue)
        return if (effective === objectValue) this else SmartEditorPolicy(effective.toString())
    }

    fun withValue(
        path: String,
        value: JsonElement,
    ): SmartEditorPolicy {
        if (this.value(path) == value) return this
        fun replace(
            node: JsonObject,
            keys: List<String>,
        ): JsonObject =
            JsonObject(
                node.toMutableMap().apply {
                    put(keys.first(), if (keys.size == 1) value else replace(this[keys.first()] as? JsonObject ?: JsonObject(emptyMap()), keys.drop(1)))
                },
            )
        return SmartEditorPolicy(replace(objectValue, path.split('.')).toString())
    }

    fun ranking(): List<String>? = (value("preference.rank") as? JsonArray)?.map { (it as JsonPrimitive).content }
}

enum class SmartEditorNumber(
    val path: String,
    private val durationUnitNanos: Long = 0,
) {
    Target("throughput.target_mbps"),
    Streams("throughput.streams"),
    Hysteresis("preference.speed_floor_hysteresis"),
    Dwell("preference.switch.dwell", 60_000_000_000),
    Confirmations("preference.switch.confirmations"),
    ConfirmationInterval("preference.switch.confirmation_interval", 1_000_000_000),
    SpeedMargin("preference.switch.speed_margin"),
    TierRestore("preference.switch.tier_restore", 1_000_000_000),
    Heartbeat("probe.heartbeat_interval", 1_000_000_000),
    Standby("probe.standby_interval", 1_000_000_000),
    Background("probe.background_interval", 60_000_000_000),
    ;

    fun display(
        policy: SmartEditorPolicy,
        decimalSeparator: Char = '.',
    ): String? {
        val raw = policy.text(path) ?: return null
        val number =
            if (durationUnitNanos != 0L) {
                durationNanos(raw).divide(BigDecimal.valueOf(durationUnitNanos), MathContext(40, RoundingMode.HALF_EVEN))
            } else {
                val value = BigDecimal(raw)
                when (this) {
                    Hysteresis -> value.movePointRight(2)
                    SpeedMargin -> value.subtract(BigDecimal.ONE).movePointRight(2)
                    else -> value
                }
            }
        return number.stripTrailingZeros().toPlainString().replace('.', decimalSeparator)
    }

    fun update(
        policy: SmartEditorPolicy,
        input: String,
    ): SmartEditorPolicy {
        val canonical = input.trim().replace(',', '.')
        val number = BigDecimal(canonical)
        // In particular, 1 ns expressed in minutes has no finite decimal representation.
        if (display(policy)?.let { BigDecimal(it).compareTo(number) == 0 } == true) return policy
        val value =
            if (durationUnitNanos != 0L) {
                JsonPrimitive(number.multiply(BigDecimal.valueOf(durationUnitNanos)).setScale(0, RoundingMode.DOWN).toPlainString() + "ns")
            } else {
                val stored =
                    when (this) {
                        Hysteresis -> number.movePointLeft(2)
                        SpeedMargin -> number.movePointLeft(2).add(BigDecimal.ONE)
                        else -> number
                    }
                Json.parseToJsonElement(if (this == Streams || this == Confirmations) stored.stripTrailingZeros().toPlainString() else stored.toString())
            }
        return policy.withValue(path, value)
    }

    companion object {
        private val durationPart = Regex("([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(ns|us|µs|μs|ms|s|m|h|d)")
        private val units =
            mapOf(
                "ns" to 1L,
                "us" to 1_000L,
                "µs" to 1_000L,
                "μs" to 1_000L,
                "ms" to 1_000_000L,
                "s" to 1_000_000_000L,
                "m" to 60_000_000_000L,
                "h" to 3_600_000_000_000L,
                "d" to 86_400_000_000_000L,
            )

        fun durationNanos(value: String): BigDecimal {
            val unsigned = value.removePrefix("+").removePrefix("-")
            if (unsigned == "0") return BigDecimal.ZERO
            var end = 0
            var sum = BigDecimal.ZERO
            for (part in durationPart.findAll(unsigned)) {
                require(part.range.first == end) { "Invalid duration: $value" }
                val amount = part.groupValues[1]
                val unit = units.getValue(part.groupValues[2])
                val whole = BigDecimal(amount.substringBefore('.').ifEmpty { "0" }).multiply(BigDecimal.valueOf(unit))
                var fraction = BigDecimal.ZERO
                var scale = 1.0
                for (digit in amount.substringAfter('.', "")) {
                    if (fraction > BigDecimal.valueOf(Long.MAX_VALUE / 10)) break
                    val next = fraction.movePointRight(1).add(BigDecimal.valueOf((digit - '0').toLong()))
                    if (next > BigDecimal("9223372036854775808")) break
                    fraction = next
                    scale *= 10
                }
                // Match the core duration decoder's fractional-unit rounding, not decimal rounding.
                val fractionalNanos = (fraction.toDouble() * (unit.toDouble() / scale)).toLong()
                sum = sum.add(whole).add(BigDecimal.valueOf(fractionalNanos))
                end = part.range.last + 1
            }
            require(end == unsigned.length && end != 0) { "Invalid duration: $value" }
            return if (value.startsWith('-')) sum.negate() else sum
        }
    }
}
