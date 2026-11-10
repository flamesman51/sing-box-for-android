package io.nekohasekai.sfa.compose.screen.smart

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartProfilePickerScreen(navController: NavController, serviceStatus: Status) {
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    val stored = remember { Settings.smartPolicy }
    val catalog = remember { runCatching { smartProfileCatalog() } }
    var error by remember { mutableStateOf(catalog.exceptionOrNull()?.localizedMessage) }
    val selected = if (stored.isEmpty()) "" else if (smartPolicyIsCustom(stored)) "custom" else smartPolicyBase(stored)
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.smart_selection_profile)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
                }
            },
        )
    }
    val padding = LocalScaffoldPadding.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).selectableGroup(),
        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 8.dp),
    ) {
        item {
            SmartProfileChoice("", stringResource(R.string.smart_profile_from_description), selected == "") {
                Settings.smartPolicy = ""
                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                navController.navigateUp()
            }
        }
        var previousSection: Int? = null
        catalog.getOrNull().orEmpty().forEach { profile ->
            val section = smartProfileSection(profile.name)
            if (section != previousSection && section != null) {
                val spaced = previousSection != null
                item(key = "section:$section") { SmartProfileHeader(section, spaced = spaced) }
            }
            previousSection = section
            item(key = profile.name) {
                SmartProfileChoice(profile.name, smartProfileDescription(profile), selected == profile.name) {
                    Settings.smartPolicy = smartNamedPolicy(profile.name)
                    notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                    navController.navigateUp()
                }
            }
        }
        item {
            SmartProfileHeader(R.string.smart_profile_section_advanced, spaced = true)
            SmartProfileChoice("custom", stringResource(R.string.smart_profile_custom_description), selected == "custom", custom = true) {
                navController.navigate("settings/smart/profile/custom")
            }
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun SmartProfileHeader(resource: Int, spaced: Boolean) {
    if (spaced) Spacer(Modifier.height(16.dp))
    Text(stringResource(resource), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
}

@Composable
private fun SmartProfileChoice(name: String, description: String, selected: Boolean, custom: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(smartProfileLabel(name), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = if (custom) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }) else null,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun smartProfileLabel(name: String): String = when (name) {
    "" -> stringResource(R.string.smart_profile_from)
    "default" -> stringResource(R.string.smart_profile_classic)
    "stability-downtime" -> stringResource(R.string.smart_profile_always_online)
    "stability-connections" -> stringResource(R.string.smart_profile_connections)
    "balance-speed-720p" -> stringResource(R.string.smart_profile_balanced, "720p")
    "balance-speed-1080p" -> stringResource(R.string.smart_profile_balanced, "1080p")
    "balance-speed-1440p" -> stringResource(R.string.smart_profile_balanced, "1440p")
    "balance-speed-4k" -> stringResource(R.string.smart_profile_balanced, "4K")
    "speed-max" -> stringResource(R.string.smart_profile_fastest)
    "speed-upload" -> stringResource(R.string.smart_profile_fastest_upload)
    "responsive" -> stringResource(R.string.smart_profile_responsive)
    "realtime-calls" -> stringResource(R.string.smart_profile_calls)
    "realtime-gaming" -> stringResource(R.string.smart_profile_gaming)
    "data-saver" -> stringResource(R.string.smart_profile_data_saver)
    "phone" -> stringResource(R.string.smart_profile_phone)
    "desktop" -> stringResource(R.string.smart_profile_desktop)
    "server" -> stringResource(R.string.smart_profile_server)
    "custom" -> stringResource(R.string.smart_profile_custom)
    else -> name
}

@Composable
internal fun smartRunningProfileLabel(preference: SmartPreference?): String {
    if (preference == null || preference.engine == "legacy") return smartProfileLabel("default")
    val base = smartProfileLabel(preference.base)
    return if (preference.custom) stringResource(R.string.smart_profile_custom_based, base) else base
}

internal fun smartProfileSection(name: String): Int? = when {
    name.startsWith("stability-") -> R.string.smart_profile_section_stability
    name.startsWith("balance-speed-") -> R.string.smart_profile_section_video
    name == "speed-max" || name == "speed-upload" || name == "responsive" -> R.string.smart_profile_section_speed
    name.startsWith("realtime-") -> R.string.smart_profile_section_realtime
    name == "data-saver" -> R.string.smart_profile_section_saving
    name == "phone" || name == "desktop" || name == "server" -> R.string.smart_profile_section_devices
    else -> null
}

@Composable
private fun smartProfileDescription(profile: SmartProfileEntry): String = when (profile.name) {
    "default" -> stringResource(R.string.smart_profile_classic_description)
    "stability-downtime" -> stringResource(R.string.smart_profile_always_online_description)
    "stability-connections" -> stringResource(R.string.smart_profile_connections_description)
    "speed-max" -> stringResource(R.string.smart_profile_fastest_description)
    "speed-upload" -> stringResource(R.string.smart_profile_fastest_upload_description)
    "responsive" -> stringResource(R.string.smart_profile_responsive_description)
    "realtime-calls" -> stringResource(R.string.smart_profile_calls_description)
    "realtime-gaming" -> stringResource(R.string.smart_profile_gaming_description)
    "data-saver" -> stringResource(R.string.smart_profile_data_saver_description)
    "phone" -> smartProfileLabel("balance-speed-1080p")
    "desktop" -> smartProfileLabel("balance-speed-4k")
    "server" -> stringResource(R.string.smart_profile_server_description)
    else -> stringResource(R.string.smart_profile_balanced_description, profile.policy.getValue("throughput").jsonObject.getValue("target_mbps").jsonPrimitive.content)
}
