package io.nekohasekai.sfa.compose.screen.smart

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import io.nekohasekai.sfa.R
import java.util.Locale

@Composable
internal fun SmartInsightsHeader(label: Int) {
    Spacer(Modifier.height(16.dp))
    Text(
        stringResource(label),
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun InsightsCard(first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (first) 8.dp else 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

private fun Modifier.rowCorners(first: Boolean, last: Boolean): Modifier = clip(
    RoundedCornerShape(
        topStart = if (first) 12.dp else 0.dp,
        topEnd = if (first) 12.dp else 0.dp,
        bottomStart = if (last) 12.dp else 0.dp,
        bottomEnd = if (last) 12.dp else 0.dp,
    ),
)

@Composable
private fun InsightsRow(
    headline: String,
    supporting: String? = null,
    modifier: Modifier = Modifier,
    overline: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    headlineMaxLines: Int = Int.MAX_VALUE,
    supportingMaxLines: Int = Int.MAX_VALUE,
) {
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(headline, style = MaterialTheme.typography.bodyLarge, maxLines = headlineMaxLines, overflow = TextOverflow.Ellipsis) },
        overlineContent = overline?.let { { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        supportingContent = supporting?.takeIf(String::isNotBlank)?.let {
            {
                Text(
                    it,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = supportingMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = leading,
        trailingContent = trailing,
    )
}

@Composable
private fun StateDot(color: Color) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

@Composable
private fun groupColor(state: String): Color = when (state) {
    "ok" -> MaterialTheme.colorScheme.primary
    "degraded" -> MaterialTheme.colorScheme.tertiary
    "all_dead", "offline" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun memberColor(state: String): Color = when (state) {
    "healthy" -> MaterialTheme.colorScheme.primary
    "suspect", "probation", "emergency" -> MaterialTheme.colorScheme.tertiary
    "quarantined" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun LeadingIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

private fun networkIcon(type: String): ImageVector = when (type) {
    "wifi" -> Icons.Outlined.Wifi
    "cellular" -> Icons.Outlined.SignalCellularAlt
    "ethernet" -> Icons.Outlined.SettingsEthernet
    else -> Icons.Outlined.Public
}

@Composable
private fun appLocale(): Locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()

@Composable
private fun smartTimeText(seconds: Long?): String? = formatSmartTime(LocalContext.current, seconds, appLocale())

@Composable
private fun numberText(value: Double?, resource: Int): String? = formatSmartNumber(value, stringResource(resource), appLocale())

@Composable
private fun megabytesText(value: Double?): String? = formatSmartMegabytes(value, stringResource(R.string.smart_mb_value), stringResource(R.string.smart_mb_whole_value), appLocale())

@Composable
private fun integerText(value: Double?, resource: Int): String? {
    val pattern = stringResource(resource)
    val locale = appLocale()
    return value?.takeIf(Double::isFinite)?.let { String.format(locale, pattern, it.toLong()) }
}

@Composable
internal fun SmartInsightsStatusCard(status: SmartStatus) {
    InsightsCard(first = true) {
        InsightsRow(
            headline = stringResource(smartGroupLabel(status.state)),
            supporting = stringResource(smartGroupExplanation(status.state)),
            modifier = Modifier.rowCorners(first = true, last = false),
            leading = { StateDot(groupColor(status.state)) },
        )
        status.preference?.takeIf { it.engine != "legacy" }?.let {
            Text(
                stringResource(R.string.smart_profile_insights, smartRunningProfileLabel(it)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        status.availabilityNotice()?.let { SmartAvailabilityNoticeText(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        InsightsRow(
            overline = stringResource(R.string.smart_current_server),
            headline = status.selected.tcp?.let { displayTag(it).first } ?: stringResource(R.string.smart_none),
            supporting = status.selected.standby?.let { stringResource(R.string.smart_standby_server, displayTag(it).first) },
            leading = { LeadingIcon(Icons.Outlined.Dns) },
        )
        val network = status.network
        val typeLabel = stringResource(smartNetworkTypeLabel(network.type))
        InsightsRow(
            headline = safeNetworkLabel(network.label, network.carrier, stringResource(smartNetworkFallbackLabel(network.type))),
            supporting = joinParts(typeLabel, stringResource(R.string.smart_direct_summary, stringResource(smartDirectLabel(status.direct.state)))),
            modifier = Modifier.rowCorners(first = false, last = true),
            leading = { LeadingIcon(networkIcon(network.type)) },
        )
    }
}

@Composable
internal fun SmartInsightsServersCard(status: SmartStatus) {
    var expanded by rememberSaveable(status.group) { mutableStateOf(hashMapOf<String, Boolean>()) }
    val members = status.members.sortedBy { it.rank }
    val addresses = duplicateServerAddresses(members.map { it.tag })
    InsightsCard {
        members.forEachIndexed { index, member ->
            val isExpanded = expanded[member.tag] == true
            val clickLabel = stringResource(if (isExpanded) R.string.smart_hide_details else R.string.smart_show_details)
            val expansionLabel = stringResource(if (isExpanded) R.string.smart_expanded else R.string.smart_collapsed)
            val role = when (member.tag) {
                status.selected.tcp -> stringResource(R.string.smart_current)
                status.selected.standby -> stringResource(R.string.smart_standby)
                else -> null
            }
            InsightsRow(
                headline = displayTag(member.tag).first,
                supporting = joinParts(addresses[member.tag], role, stringResource(smartMemberLabel(member.state)), integerText(member.rttMs, R.string.smart_rtt_value), numberText(member.downloadMbps, R.string.smart_speed_value)),
                modifier = Modifier
                    .rowCorners(index == 0, index == members.lastIndex && !isExpanded)
                    .clickable(onClickLabel = clickLabel) { expanded = HashMap(expanded).apply { put(member.tag, !isExpanded) } }
                    .semantics { stateDescription = expansionLabel },
                leading = { StateDot(memberColor(member.state)) },
                trailing = { Icon(if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                headlineMaxLines = 1,
                supportingMaxLines = 2,
            )
            AnimatedVisibility(visible = isExpanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(
                    Modifier.rowCorners(first = false, last = index == members.lastIndex).padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(member.tag, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MemberValues(member)
                    MemberCharts(status.timeline.members[member.tag].orEmpty())
                }
            }
        }
    }
}

@Composable
private fun MemberValues(member: SmartMember) {
    val locale = appLocale()
    val stabilityPattern = stringResource(R.string.smart_stability_value)
    val stability = member.stability?.takeIf(Double::isFinite)?.let {
        member.stabilityClass?.takeIf(String::isNotBlank)?.let { label -> String.format(locale, stabilityPattern, (it * 100).toLong(), label) }
            ?: integerText(it * 100, R.string.smart_percent_value)
    }
    val speedTime = smartTimeText(member.throughputAt)
    val speedSource = member.throughputSource?.let { stringResource(smartThroughputSourceLabel(it)) }
    val speedClass = if (speedSource != null && speedTime != null) {
        stringResource(R.string.smart_speed_class_value, member.throughputClass, speedSource, speedTime)
    } else {
        joinParts(String.format(locale, "%d", member.throughputClass), speedSource, speedTime)
    }
    val values = listOf(
        R.string.smart_tcp to stringResource(smartEvidenceLabel(member.tcp)),
        R.string.smart_udp to stringResource(smartEvidenceLabel(member.udp)),
        R.string.smart_quic to stringResource(smartEvidenceLabel(member.udp443)),
        R.string.smart_latency to integerText(member.rttMs, R.string.smart_rtt_value),
        R.string.smart_latency_p90 to integerText(member.rttP90Ms, R.string.smart_rtt_value),
        R.string.smart_stability to stability,
        R.string.smart_download to numberText(member.downloadMbps, R.string.smart_speed_value),
        R.string.smart_upload to numberText(member.uploadMbps, R.string.smart_speed_value),
        R.string.smart_speed_class to speedClass,
        R.string.smart_tier to String.format(locale, "%d", member.tier),
        R.string.smart_country to member.country?.takeIf(String::isNotBlank),
        R.string.smart_weight to numberText(member.weight, R.string.smart_weight_value),
        R.string.smart_probation_label to stringResource(R.string.smart_probation_value, member.probation),
        R.string.smart_set_aside_until to smartTimeText(member.quarantineUntil),
        R.string.smart_last_success to smartTimeText(member.lastOk),
        R.string.smart_last_failure to smartTimeText(member.lastFail),
    ).filter { !it.second.isNullOrBlank() }
    val columns = if (LocalDensity.current.fontScale <= 1.3f) 2 else 1
    values.chunked(columns).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (columns == 2 && pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun MemberCharts(buckets: List<List<Double?>>) {
    val series = listOf(
        Triple(buckets.map { it.getOrNull(0)?.times(100) }, R.string.smart_success_chart, 100.0),
        Triple(buckets.map { it.getOrNull(1) }, R.string.smart_rtt_chart, null),
        Triple(buckets.map { it.getOrNull(2) }, R.string.smart_speed_chart, null),
    ).filter { hasSamples(it.first) }
    if (series.isNotEmpty()) {
        Text(stringResource(R.string.smart_charts_caption), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        series.forEach { (values, label, maximum) -> TimelineChart(values, stringResource(label), maximum) }
    }
}

@Composable
internal fun SmartInsightsDetailsCard(status: SmartStatus) {
    InsightsCard {
        InsightsRow(
            headline = stringResource(R.string.smart_group),
            supporting = status.group,
            modifier = Modifier.rowCorners(first = true, last = false),
            leading = { LeadingIcon(Icons.Outlined.Hub) },
        )
        val quic = status.selected.udp443
        val quicLabel = if (quic.mode == "member") {
            stringResource(smartQuicLabel(quic.mode), quic.member?.let { displayTag(it).first } ?: stringResource(R.string.smart_none))
        } else {
            stringResource(smartQuicLabel(quic.mode))
        }
        val rejectReason = if (quic.mode == "reject") smartReasonLabel(quic.reason)?.takeUnless { it == R.string.smart_unknown }?.let { stringResource(it) } else null
        InsightsRow(
            headline = stringResource(R.string.smart_udp_quic),
            supporting = joinParts(
                stringResource(R.string.smart_udp_route, status.selected.udp?.let { displayTag(it).first } ?: stringResource(R.string.smart_none)),
                stringResource(R.string.smart_quic_route, quicLabel),
                rejectReason,
            ),
            leading = { LeadingIcon(Icons.Outlined.SwapVert) },
        )
        val budget = status.budget
        val used = megabytesText(budget.usedMb)
        val limit = megabytesText(budget.limitMb)
        val reserved = megabytesText(budget.reservedMb.takeIf { it != 0.0 })
        val budgetValues = if (used != null && limit != null) {
            stringResource(smartBudgetValuesResource(budget.reservedMb), used, limit, stringResource(smartNetworkTypeLabel(budget.networkType)), reserved.orEmpty())
        } else {
            joinParts(used, limit, stringResource(smartNetworkTypeLabel(budget.networkType)), reserved)
        }
        InsightsRow(
            headline = stringResource(R.string.smart_budget),
            supporting = budgetValues + "\n" + stringResource(if (budget.activeTests) R.string.smart_budget_note else R.string.smart_active_tests_off),
            leading = { LeadingIcon(Icons.Outlined.Speed) },
        )
        budgetProgress(budget.usedMb, budget.limitMb)?.let { progress ->
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 16.dp))
        }
        val direct = status.direct
        InsightsRow(
            headline = stringResource(R.string.smart_direct_title),
            supporting = joinParts(
                stringResource(smartDirectLabel(direct.state)),
                if (direct.state == "ok") integerText(direct.rttMs, R.string.smart_rtt_value) else null,
                if (direct.state == "ok") numberText(direct.downloadMbps, R.string.smart_speed_value) else null,
                smartTimeText(direct.checkedAt)?.let { stringResource(R.string.smart_checked, it) },
            ),
            modifier = Modifier.rowCorners(first = false, last = true),
            leading = { LeadingIcon(Icons.Outlined.Public) },
        )
    }
}

@Composable
internal fun SmartInsightsEmpty(label: Int) {
    Text(
        stringResource(label),
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SmartInsightsNetworksCard(networks: List<SmartNetworkHistory>) {
    val context = LocalContext.current
    val groups = groupSmartNetworks(networks) { context.getString(smartNetworkFallbackLabel(it)) }
    InsightsCard {
        groups.forEachIndexed { index, network ->
            val typeLabel = stringResource(smartNetworkTypeLabel(network.type))
            InsightsRow(
                headline = network.label,
                supporting = joinParts(
                    typeLabel,
                    if (network.count > 1) pluralStringResource(smartNetworkCountPlural(network.type), network.count, network.count) else null,
                    pluralStringResource(R.plurals.smart_server_count, network.servers, network.servers),
                    smartTimeText(network.lastUsed)?.let { stringResource(R.string.smart_last_seen, it) },
                ),
                modifier = Modifier.rowCorners(index == 0, index == groups.lastIndex),
                leading = { LeadingIcon(networkIcon(network.type)) },
            )
        }
    }
}

@Composable
internal fun SmartInsightsEventsCard(events: List<SmartEvent>, networks: List<SmartNetworkHistory>, currentNetwork: SmartNetwork, members: List<SmartMember>) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val addresses = duplicateServerAddresses(members.map { it.tag })
    val merged = mergeSmartEvents(events)
    val shown = if (showAll) merged else merged.take(20)
    val showMore = !showAll && merged.size > 20
    InsightsCard {
        shown.forEachIndexed { index, group ->
            val event = group.event
            val kindLabel = stringResource(smartEventLabel(event.kind))
            InsightsRow(
                headline = if (group.count > 1) stringResource(R.string.smart_event_repeated, kindLabel, group.count) else kindLabel,
                supporting = joinParts(
                    smartEventServers(event.from, event.to, addresses),
                    smartReasonLabel(event.reason)?.let { stringResource(it) },
                    networkLabelForKey(event.network, networks, currentNetwork) { context.getString(smartNetworkFallbackLabel(it)) },
                    if (group.count > 1) smartTimeText(group.oldestTime)?.let { stringResource(R.string.smart_event_since, it) } else null,
                ),
                modifier = Modifier.rowCorners(index == 0, index == shown.lastIndex && !showMore),
                trailing = {
                    smartTimeText(event.time)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
                supportingMaxLines = 3,
            )
        }
        if (showMore) {
            TextButton(onClick = { showAll = true }, modifier = Modifier.padding(start = 8.dp)) {
                Text(stringResource(R.string.smart_show_all, merged.size))
            }
        }
    }
}
