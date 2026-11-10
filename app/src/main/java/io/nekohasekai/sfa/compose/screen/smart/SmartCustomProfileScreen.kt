package io.nekohasekai.sfa.compose.screen.smart

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.text.DecimalFormatSymbols
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartCustomProfileScreen(navController: NavController, serviceStatus: Status) {
    val requiredBaseMessage = stringResource(R.string.smart_editor_require_base)
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.ROOT
    val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val catalogResult = remember { runCatching { smartProfileCatalog() } }
    val catalog = catalogResult.getOrNull()
    val snapshot = rememberSmartPolicySnapshot(serviceStatus)
    val stored = remember { Settings.smartPolicy }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var initialSource by rememberSaveable { mutableStateOf<String?>(null) }
    var warningGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(catalogResult.exceptionOrNull()?.message) }
    var initialized by rememberSaveable { mutableStateOf(false) }
    var jsonMode by rememberSaveable { mutableStateOf(false) }
    var jsonText by rememberSaveable { mutableStateOf("") }
    var jsonError by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<String?>(null) }
    var number by remember { mutableStateOf<SmartEditorNumber?>(null) }
    val notifyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    LaunchedEffect(catalog, snapshot, serviceStatus) {
        if (initialized || catalog == null) return@LaunchedEffect
        runCatching {
            val initial = when {
                stored.isNotEmpty() && smartPolicyIsCustom(stored) -> stored
                stored.isNotEmpty() -> catalog.first { it.name == smartPolicyBase(stored) }.policy.toString()
                else -> {
                    val first = snapshot?.firstOrNull()
                    val effective = first?.preference?.resolved
                    if (effective != null) {
                        if (snapshot.orEmpty().any { it.preference?.resolved != effective }) warningGroup = first?.group
                        effective.toString()
                    } else {
                        catalog.first { it.name == "phone" }.policy.toString()
                    }
                }
            }
            SmartEditorPolicy(initial)
            source = initial
            initialSource = initial
            initialized = true
        }.onFailure { failure = it.message ?: it.toString() }
    }
    val policy = remember(source, catalog) {
        source?.let { raw ->
            val draft = SmartEditorPolicy(raw)
            catalog?.first { it.name == (draft.text("preference.profile") ?: "default") }?.let { draft.withDefaults(it.policy) }
        }
    }
    fun leave() {
        if (jsonMode) {
            jsonMode = false
            jsonError = null
        } else {
            navController.navigateUp()
        }
    }
    fun back() {
        if (if (jsonMode) jsonText != source else source != initialSource) discard = true else leave()
    }
    fun accept(candidate: SmartEditorPolicy): Boolean = try {
        Libbox.checkSmartPolicy(candidate.source)
        source = candidate.source
        true
    } catch (exception: Exception) {
        failure = exception.message ?: exception.toString()
        false
    }
    BackHandler { back() }
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(if (jsonMode) R.string.smart_editor_json else R.string.smart_editor_title)) },
            navigationIcon = {
                IconButton(onClick = { back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
                }
            },
            actions = {
                TextButton(enabled = policy != null, onClick = {
                    if (jsonMode) {
                        try {
                            val validation = runCatching { Libbox.checkSmartPolicy(jsonText) }
                            val candidate = try {
                                SmartEditorPolicy(jsonText)
                            } catch (exception: Exception) {
                                validation.getOrThrow()
                                throw exception
                            }
                            require(catalog.orEmpty().any { it.name == candidate.text("preference.profile") }) {
                                requiredBaseMessage
                            }
                            validation.getOrThrow()
                            source = candidate.source
                            jsonMode = false
                            jsonError = null
                        } catch (exception: Exception) {
                            jsonError = exception.message ?: exception.toString()
                        }
                    } else if (policy != null) {
                        try {
                            Libbox.checkSmartPolicy(policy.source)
                            Settings.smartPolicy = policy.source
                            notifyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                            navController.popBackStack("settings/smart", false)
                        } catch (exception: Exception) {
                            failure = exception.message ?: exception.toString()
                        }
                    }
                }) { Text(stringResource(R.string.smart_editor_save)) }
                if (!jsonMode) {
                    IconButton(enabled = policy != null, onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.smart_editor_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.smart_editor_json)) },
                            onClick = {
                                menu = false
                                jsonText = source.orEmpty()
                                jsonError = null
                                jsonMode = true
                            },
                        )
                    }
                }
            },
        )
    }
    val padding = LocalScaffoldPadding.current
    val screen = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(
        top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + 8.dp,
    )
    if (jsonMode) {
        OutlinedTextField(
            value = jsonText,
            onValueChange = { jsonText = it; jsonError = null },
            modifier = screen.padding(horizontal = 16.dp),
            label = { Text(stringResource(R.string.smart_editor_json)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            isError = jsonError != null,
            supportingText = { jsonError?.let { Text(it) } },
        )
    } else if (policy == null) {
        Column(screen.padding(32.dp)) {
            if (failure == null) CircularProgressIndicator()
        }
    } else {
        Column(screen.verticalScroll(rememberScrollState())) {
            EditorCard(first = true) {
                EditorRow(
                    R.string.smart_editor_start_from,
                    smartProfileLabel(requireNotNull(policy.text("preference.profile"))),
                    onClick = { choice = "base" },
                    leading = { Icon(Icons.Outlined.ContentCopy, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
            warningGroup?.let {
                EditorFooter(stringResource(R.string.smart_editor_groups_differ, it))
            }
            EditorHeader(R.string.smart_editor_ranking)
            EditorCard {
                val rank = requireNotNull(policy.ranking())
                val ordered = rank + editorCriteria.filter { it.id !in rank }.map { it.id }
                var focusAfterMove by remember { mutableStateOf<String?>(null) }
                ordered.forEach { id ->
                    key(id) {
                        val criterion = editorCriteria.first { it.id == id }
                        val label = stringResource(criterion.label)
                        val index = rank.indexOf(id)
                        val included = index >= 0
                        val requester = remember { FocusRequester() }
                        val state = if (included) stringResource(R.string.smart_editor_priority, index + 1) else stringResource(R.string.smart_editor_not_used)
                        LaunchedEffect(rank, focusAfterMove) {
                            if (focusAfterMove == id) {
                                requester.requestFocus()
                                focusAfterMove = null
                            }
                        }
                        fun updateRank(next: List<String>) = accept(policy.withValue("preference.rank", JsonArray(next.map(::JsonPrimitive))))
                        fun move(delta: Int) {
                            val next = rank.toMutableList()
                            next.removeAt(index)
                            next.add(index + delta, id)
                            if (updateRank(next)) focusAfterMove = id
                        }
                        ListItem(
                            headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                            supportingContent = { EditorSupport(stringResource(criterion.description)) },
                            leadingContent = { Checkbox(checked = included, onCheckedChange = null, enabled = !included || rank.size > 1) },
                            trailingContent = {
                                if (included) Row {
                                    IconButton(enabled = index > 0, modifier = Modifier.size(48.dp), onClick = { move(-1) }) {
                                        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.smart_editor_move_up, label))
                                    }
                                    IconButton(enabled = index < rank.lastIndex, modifier = Modifier.size(48.dp), onClick = { move(1) }) {
                                        Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.smart_editor_move_down, label))
                                    }
                                }
                            },
                            modifier = Modifier.focusRequester(requester).semantics { stateDescription = state }.toggleable(
                                value = included,
                                enabled = !included || rank.size > 1,
                                role = Role.Checkbox,
                                onValueChange = { checked -> updateRank(if (checked) rank + id else rank - id) },
                            ),
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
            EditorFooter(stringResource(R.string.smart_editor_ranking_description))
            EditorHeader(R.string.smart_editor_limits)
            EditorCard {
                EditorNumberRow(policy, SmartEditorNumber.Target, separator, onClick = { number = it })
                EditorRow(R.string.smart_editor_min_stability, editorStabilityLabel(policy.text("preference.min_stability")), onClick = { choice = "stability" })
                EditorNumberRow(policy, SmartEditorNumber.Hysteresis, separator, onClick = { number = it })
            }
            EditorHeader(R.string.smart_editor_switching)
            EditorCard {
                EditorSwitch(policy, "preference.switch.improvement", R.string.smart_editor_improvement, onChange = { accept(it) })
                val enabled = policy.text("preference.switch.improvement") != "false"
                listOf(SmartEditorNumber.Dwell, SmartEditorNumber.Confirmations, SmartEditorNumber.ConfirmationInterval, SmartEditorNumber.SpeedMargin).forEach {
                    EditorNumberRow(policy, it, separator, enabled, onClick = { field -> number = field })
                }
                EditorNumberRow(policy, SmartEditorNumber.TierRestore, separator, onClick = { number = it })
            }
            EditorHeader(R.string.smart_editor_failures)
            EditorCard {
                EditorSwitch(policy, "interrupt_on_failover", R.string.smart_editor_interrupt, onChange = { accept(it) })
                listOf(SmartEditorNumber.Heartbeat, SmartEditorNumber.Standby, SmartEditorNumber.Background).forEach {
                    EditorNumberRow(policy, it, separator, onClick = { field -> number = field })
                }
            }
            EditorHeader(R.string.smart_editor_data)
            EditorCard {
                EditorSwitch(policy, "throughput.enabled", R.string.smart_editor_speed_tests, stringResource(R.string.smart_editor_speed_tests_description), onChange = { accept(it) })
                EditorRow(R.string.smart_editor_quic, editorQuicLabel(policy.text("udp.quic_policy")), onClick = { choice = "quic" })
            }
        }
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.smart_editor_discard_title)) },
            confirmButton = { TextButton(onClick = { discard = false; leave() }) { Text(stringResource(R.string.smart_editor_discard)) } },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    failure?.let { message ->
        AlertDialog(
            onDismissRequest = { failure = null },
            title = { Text(stringResource(R.string.smart_editor_cannot_save)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { failure = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    if (policy != null) {
        number?.let { field ->
            EditorNumberDialog(policy, field, separator, onDismiss = { number = null }, onSave = { source = it.source; number = null })
        }
        choice?.let { selected ->
            AlertDialog(
                onDismissRequest = { choice = null },
                title = { Text(stringResource(when (selected) {
                    "base" -> R.string.smart_editor_start_from
                    "stability" -> R.string.smart_editor_min_stability
                    else -> R.string.smart_editor_quic
                })) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                        if (selected == "stability") {
                            Text(stringResource(R.string.smart_editor_stability_description), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
                        }
                        when (selected) {
                            "base" -> catalog.orEmpty().forEach { entry ->
                                EditorChoice(smartProfileLabel(entry.name), policy.text("preference.profile") == entry.name) {
                                    if (accept(SmartEditorPolicy(entry.policy.toString()))) { warningGroup = null; choice = null }
                                }
                            }
                            "stability" -> listOf("none", "C", "B", "A").forEach { value ->
                                EditorChoice(editorStabilityLabel(value), policy.text("preference.min_stability") == value) {
                                    if (accept(policy.withValue("preference.min_stability", JsonPrimitive(value)))) choice = null
                                }
                            }
                            "quic" -> listOf("auto", "route_only", "off").forEach { value ->
                                val description = stringResource(when (value) {
                                    "auto" -> R.string.smart_editor_quic_auto_description
                                    "route_only" -> R.string.smart_editor_quic_route_description
                                    else -> R.string.smart_editor_quic_off_description
                                })
                                EditorChoice(editorQuicLabel(value), policy.text("udp.quic_policy") == value, description) {
                                    if (accept(policy.withValue("udp.quic_policy", JsonPrimitive(value)))) choice = null
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { choice = null }) { Text(stringResource(R.string.close)) } },
            )
        }
    }
}

private data class EditorCriterion(val id: String, val label: Int, val description: Int)

private val editorCriteria = listOf(
    EditorCriterion("stability", R.string.smart_editor_stability, R.string.smart_editor_stability_hint),
    EditorCriterion("speed_floor", R.string.smart_editor_target, R.string.smart_editor_target_hint),
    EditorCriterion("download", R.string.smart_editor_download, R.string.smart_editor_download_hint),
    EditorCriterion("throughput", R.string.smart_editor_throughput, R.string.smart_editor_throughput_hint),
    EditorCriterion("latency", R.string.smart_editor_latency, R.string.smart_editor_latency_hint),
    EditorCriterion("rtt_spread", R.string.smart_editor_spread, R.string.smart_editor_spread_hint),
    EditorCriterion("udp", R.string.smart_editor_udp, R.string.smart_editor_udp_hint),
    EditorCriterion("quic", R.string.smart_editor_quic_works, R.string.smart_editor_quic_hint),
    EditorCriterion("outages", R.string.smart_editor_outages, R.string.smart_editor_outages_hint),
)

@Composable
private fun EditorHeader(label: Int) {
    Spacer(Modifier.height(16.dp))
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
}

@Composable
private fun EditorCard(first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (first) 8.dp else 0.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

@Composable
private fun EditorSupport(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
}

@Composable
private fun EditorFooter(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp))
}

@Composable
private fun EditorRow(label: Int, supporting: String, enabled: Boolean = true, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { EditorSupport(supporting) },
        leadingContent = leading,
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        modifier = Modifier.alpha(if (enabled) 1f else 0.38f).clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun EditorSwitch(policy: SmartEditorPolicy, path: String, label: Int, description: String? = null, onChange: (SmartEditorPolicy) -> Unit) {
    val value = policy.text(path)
    ListItem(
        headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (description != null) ({ EditorSupport(description) }) else null,
        trailingContent = { Switch(checked = value == "true", onCheckedChange = { onChange(policy.withValue(path, JsonPrimitive(it))) }) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun EditorChoice(label: String, selected: Boolean, description: String? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (description != null) ({ Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun editorStabilityLabel(value: String?): String = when (requireNotNull(value)) {
    "none" -> stringResource(R.string.smart_editor_stability_any)
    "C" -> stringResource(R.string.smart_editor_stability_fair)
    "B" -> stringResource(R.string.smart_editor_stability_good)
    "A" -> stringResource(R.string.smart_editor_stability_excellent)
    else -> requireNotNull(value)
}

@Composable
private fun editorQuicLabel(value: String?): String = when (requireNotNull(value)) {
    "auto" -> stringResource(R.string.smart_editor_quic_auto)
    "route_only" -> stringResource(R.string.smart_editor_quic_route)
    "off" -> stringResource(R.string.smart_editor_quic_off)
    else -> requireNotNull(value)
}

private fun SmartEditorNumber.label(): Int = when (this) {
    SmartEditorNumber.Target -> R.string.smart_editor_target
    SmartEditorNumber.Hysteresis -> R.string.smart_editor_hysteresis
    SmartEditorNumber.Dwell -> R.string.smart_editor_dwell
    SmartEditorNumber.Confirmations -> R.string.smart_editor_confirmations
    SmartEditorNumber.ConfirmationInterval -> R.string.smart_editor_confirmation_interval
    SmartEditorNumber.SpeedMargin -> R.string.smart_editor_speed_margin
    SmartEditorNumber.TierRestore -> R.string.smart_editor_tier_restore
    SmartEditorNumber.Heartbeat -> R.string.smart_editor_heartbeat
    SmartEditorNumber.Standby -> R.string.smart_editor_standby
    SmartEditorNumber.Background -> R.string.smart_editor_background
}

@Composable
private fun EditorNumberRow(policy: SmartEditorPolicy, field: SmartEditorNumber, separator: Char, enabled: Boolean = true, onClick: (SmartEditorNumber) -> Unit) {
    val value = requireNotNull(field.display(policy, separator))
    val supporting = when (field) {
        SmartEditorNumber.Target -> stringResource(R.string.smart_editor_mbps, value)
        SmartEditorNumber.Hysteresis -> stringResource(R.string.smart_editor_below_target, value)
        SmartEditorNumber.Dwell -> stringResource(R.string.smart_editor_minutes, value)
        SmartEditorNumber.Confirmations -> {
            val count = policy.text(field.path)!!.toLong()
            val quantity = if (count <= Int.MAX_VALUE) count.toInt() else (100 + count % 100).toInt()
            pluralStringResource(R.plurals.smart_editor_checks, quantity, policy.text(field.path)!!)
        }
        SmartEditorNumber.ConfirmationInterval -> stringResource(R.string.smart_editor_seconds, value)
        SmartEditorNumber.SpeedMargin -> stringResource(R.string.smart_editor_faster, value)
        SmartEditorNumber.TierRestore -> if (SmartEditorNumber.durationNanos(policy.text(field.path)!!).signum() == 0) stringResource(R.string.smart_editor_never) else stringResource(R.string.smart_editor_after_seconds, value)
        SmartEditorNumber.Heartbeat, SmartEditorNumber.Standby -> stringResource(R.string.smart_editor_every_seconds, value)
        SmartEditorNumber.Background -> stringResource(R.string.smart_editor_every_minutes, value)
    }
    EditorRow(field.label(), supporting, enabled, onClick = { onClick(field) })
}

@Composable
private fun EditorNumberDialog(policy: SmartEditorPolicy, field: SmartEditorNumber, separator: Char, onDismiss: () -> Unit, onSave: (SmartEditorPolicy) -> Unit) {
    var text by rememberSaveable(field) { mutableStateOf(field.display(policy, separator).orEmpty()) }
    var error by rememberSaveable(field) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(field.label())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (field == SmartEditorNumber.Background) Text(stringResource(R.string.smart_editor_idle_description))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(stringResource(when (field) {
                        SmartEditorNumber.Dwell, SmartEditorNumber.Background -> R.string.smart_editor_unit_minutes
                        SmartEditorNumber.ConfirmationInterval, SmartEditorNumber.TierRestore, SmartEditorNumber.Heartbeat, SmartEditorNumber.Standby -> R.string.smart_editor_unit_seconds
                        SmartEditorNumber.Target -> R.string.smart_editor_unit_mbps
                        SmartEditorNumber.Hysteresis, SmartEditorNumber.SpeedMargin -> R.string.smart_editor_unit_percent
                        SmartEditorNumber.Confirmations -> R.string.smart_editor_confirmations
                    })) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val candidate = try {
                        field.update(policy, text.replace(separator, '.'))
                    } catch (_: NumberFormatException) {
                        policy.withValue(field.path, JsonPrimitive(text))
                    }
                    Libbox.checkSmartPolicy(candidate.source)
                    onSave(candidate)
                } catch (exception: Exception) {
                    error = exception.message ?: exception.toString()
                }
            }) { Text(stringResource(R.string.smart_editor_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
