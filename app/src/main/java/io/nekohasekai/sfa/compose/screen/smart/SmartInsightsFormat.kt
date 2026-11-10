package io.nekohasekai.sfa.compose.screen.smart

import android.content.Context
import android.text.format.DateFormat
import io.nekohasekai.sfa.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val smartMac = Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}")
private val smartTrailingMac = Regex(" (?i:[0-9a-f]{2}(?::[0-9a-f]{2}){5})$")
private val smartNetworkKey = Regex("(?i)(?:wifi|cell|eth)(?:/.*)?|[0-9a-f]{16,}")

internal fun displayTag(tag: String): Pair<String, String?> {
    val split = tag.lastIndexOf(" [")
    return if (tag.endsWith(']') && split >= 0) tag.substring(0, split) to tag.substring(split + 2, tag.length - 1) else tag to null
}

internal fun safeNetworkLabel(label: String?, carrier: String?, typeLabel: String): String {
    fun clean(value: String?): String? = value?.replace(smartTrailingMac, "")?.trim()?.takeUnless {
        it.isBlank() || smartMac.matches(it) || smartNetworkKey.matches(it)
    }
    return clean(label) ?: clean(carrier) ?: typeLabel
}

internal fun networkLabelForKey(key: String?, networks: List<SmartNetworkHistory>, typeLabel: (String) -> String): String? = networks.firstOrNull { it.key == key }?.let { safeNetworkLabel(it.label, it.carrier, typeLabel(it.type)) }

internal fun joinParts(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")

internal fun budgetProgress(used: Double, limit: Double): Float? {
    if (limit <= 0 || !limit.isFinite() || !used.isFinite()) return null
    val value = used / limit
    return value.takeIf(Double::isFinite)?.toFloat()?.coerceIn(0f, 1f)
}

internal fun hasSamples(values: List<Double?>): Boolean = values.any { it?.isFinite() == true }

internal fun smartTimeDateSkeleton(epochSeconds: Long, nowSeconds: Long, timeZone: java.util.TimeZone = java.util.TimeZone.getDefault()): String? {
    val date = Calendar.getInstance(timeZone).apply { timeInMillis = epochSeconds * 1000 }
    val now = Calendar.getInstance(timeZone).apply { timeInMillis = nowSeconds * 1000 }
    return if (date.get(Calendar.ERA) == now.get(Calendar.ERA) && date.get(Calendar.YEAR) == now.get(Calendar.YEAR) && date.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) null else "dMMM"
}

internal fun formatSmartTime(context: Context, epochSeconds: Long?, locale: Locale): String? = epochSeconds?.takeIf { it > 0 }?.let {
    val date = Date(it * 1000)
    val time = DateFormat.getTimeFormat(context).format(date)
    smartTimeDateSkeleton(it, System.currentTimeMillis() / 1000)?.let { skeleton ->
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date) + " " + time
    } ?: time
}

internal fun formatSmartNumber(value: Double?, pattern: String, locale: Locale): String? = value?.takeIf(Double::isFinite)?.let { String.format(locale, pattern, it) }

internal fun smartGroupLabel(state: String): Int = when (state) {
    "starting" -> R.string.smart_starting
    "ok" -> R.string.smart_ok
    "degraded" -> R.string.smart_degraded
    "all_dead" -> R.string.smart_all_dead
    "offline" -> R.string.smart_offline
    "paused" -> R.string.smart_paused
    else -> R.string.smart_unknown
}

internal fun smartGroupExplanation(state: String): Int = when (state) {
    "starting" -> R.string.smart_starting_explanation
    "ok" -> R.string.smart_ok_explanation
    "degraded" -> R.string.smart_degraded_explanation
    "all_dead" -> R.string.smart_all_dead_explanation
    "offline" -> R.string.smart_offline_explanation
    "paused" -> R.string.smart_paused_explanation
    else -> R.string.smart_unknown
}

internal fun smartMemberLabel(state: String): Int = when (state) {
    "unknown" -> R.string.smart_not_checked_yet
    "healthy" -> R.string.smart_healthy
    "suspect" -> R.string.smart_suspect
    "probation" -> R.string.smart_probation
    "quarantined" -> R.string.smart_quarantined
    "emergency" -> R.string.smart_emergency
    else -> R.string.smart_unknown
}

internal fun smartEvidenceLabel(state: String): Int = when (state) {
    "ok" -> R.string.smart_evidence_ok
    "failed" -> R.string.smart_failed
    "unknown" -> R.string.smart_evidence_unknown
    else -> R.string.smart_unknown
}

internal fun smartDirectLabel(state: String): Int = when (state) {
    "ok" -> R.string.smart_direct_ok
    "failed" -> R.string.smart_direct_failed
    "unknown" -> R.string.smart_direct_unknown
    else -> R.string.smart_unknown
}

internal fun smartNetworkTypeLabel(type: String): Int = when (type) {
    "wifi" -> R.string.smart_wifi
    "cellular" -> R.string.smart_cellular
    "ethernet" -> R.string.smart_ethernet
    "other" -> R.string.smart_other
    else -> R.string.smart_unknown
}

internal fun smartQuicLabel(mode: String): Int = when (mode) {
    "primary" -> R.string.smart_primary
    "member" -> R.string.smart_member
    "reject" -> R.string.smart_reject
    "off" -> R.string.smart_off
    else -> R.string.smart_unknown
}

internal fun smartThroughputSourceLabel(source: String?): Int = when (source) {
    "active" -> R.string.smart_speedtest
    "passive" -> R.string.smart_passive
    else -> R.string.smart_unknown
}

internal fun smartEventLabel(kind: String): Int = when (kind) {
    "network_change" -> R.string.smart_network_change
    "emergency" -> R.string.smart_emergency_event
    "speedtest" -> R.string.smart_speedtest
    "recover" -> R.string.smart_recover
    "quarantine" -> R.string.smart_quarantine_event
    "failover" -> R.string.smart_failover
    "all_dead" -> R.string.smart_all_dead_event
    "import" -> R.string.smart_import_event
    "reset" -> R.string.smart_reset_done
    "switch" -> R.string.smart_switch
    "tier_restore" -> R.string.smart_tier_restore
    else -> R.string.smart_unknown
}

internal fun smartReasonLabel(reason: String): Int? = when (reason) {
    "" -> null
    "default network changed" -> R.string.smart_reason_network
    "no eligible primary" -> R.string.smart_reason_no_primary
    "active throughput measurement" -> R.string.smart_reason_scheduled
    "fresh degradation pair" -> R.string.smart_reason_slowdown
    "three half-open successes" -> R.string.smart_reason_three_checks
    "first reachable member" -> R.string.smart_reason_first_response
    "first success this network" -> R.string.smart_reason_first_success
    "two probe operators failed" -> R.string.smart_reason_two_checks
    "primary corroborated unreachable" -> R.string.smart_reason_unreachable
    "best reachable member" -> R.string.smart_reason_best
    "contained validation; no health evidence" -> R.string.smart_reason_internal_error
    "all members failed while direct internet works" -> R.string.smart_reason_no_response
    "imported historical priors" -> R.string.smart_reason_import
    "learned history reset; live health and budget retained" -> R.string.smart_reason_reset
    "confirmed stability or throughput improvement" -> R.string.smart_reason_improved
    "demand challenger meets target" -> R.string.smart_reason_target
    "preferred tier healthy for 60 seconds" -> R.string.smart_reason_preferred
    else -> R.string.smart_unknown
}
