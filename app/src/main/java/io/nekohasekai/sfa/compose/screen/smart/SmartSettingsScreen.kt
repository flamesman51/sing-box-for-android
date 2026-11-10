package io.nekohasekai.sfa.compose.screen.smart

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.SmartPlatform
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.diagnostics.DiagnosticsWork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartSettingsScreen(navController: NavController, serviceStatus: Status) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    var country by remember { mutableStateOf(SmartSettings.countryOverride) }
    var automaticCountry by remember { mutableStateOf(SmartSettings.automaticCountry) }
    var notifications by remember { mutableStateOf(SmartSettings.notificationsEnabled) }
    var diagnostics by remember { mutableStateOf(Settings.sendDiagnostics) }
    var showPermissions by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableStateOf(0) }
    val permissions = rememberSmartHistoryPermissions(permissionRevision)
    val policy = smartStoredPolicy()
    val policySnapshot = rememberSmartPolicySnapshot(serviceStatus)
    val remotePolicy = smartPolicyIsRemote()
    val policyState = if (remotePolicy) null else smartPolicyApplyState(serviceStatus, policy, policySnapshot)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        country = SmartSettings.countryOverride
        if (country.isEmpty()) SmartPlatform.systemCountryCode()
        automaticCountry = SmartSettings.automaticCountry
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.smart_settings)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            },
        )
    }
    val scaffoldPadding = LocalScaffoldPadding.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.smart_selection_profile), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Text(
                        smartProfileSupportingText(policy, policySnapshot, policyState, remotePolicy),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                        maxLines = 2,
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = if (!remotePolicy) ({ Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
                modifier = Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .then(if (remotePolicy) Modifier else Modifier.clickable { navController.navigate("settings/smart/profile") }),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.smart_country), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    val name = when {
                        country.isNotEmpty() -> Locale("", country).getDisplayCountry(locale)
                        automaticCountry.isNotEmpty() -> stringResource(R.string.smart_country_automatic_resolved, Locale("", automaticCountry).getDisplayCountry(locale))
                        else -> stringResource(R.string.smart_country_auto)
                    }
                    Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                },
                leadingContent = { Icon(Icons.Outlined.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { navController.navigate("settings/smart/country") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.smart_history_permission), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Text(stringResource(permissions.historyLabel(Build.VERSION.SDK_INT)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                },
                leadingContent = { Icon(Icons.Outlined.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { showPermissions = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.smart_notifications), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Text(stringResource(R.string.smart_notifications_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                },
                leadingContent = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(checked = notifications, onCheckedChange = { enabled ->
                        notifications = enabled
                        SmartSettings.notificationsEnabled = enabled
                        if (!enabled) {
                            Application.notification.activeNotifications
                                .filter { it.id == SMART_NOTIFICATION_TYPE }
                                .forEach { Application.notification.cancel(it.tag, it.id) }
                        }
                    })
                },
                modifier = Modifier.clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.smart_diagnostics),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.smart_send_diagnostics), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Text(stringResource(R.string.smart_diagnostics_destination), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                },
                leadingContent = { Icon(Icons.Outlined.Analytics, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = {
                    Switch(checked = diagnostics, onCheckedChange = { enabled ->
                        diagnostics = enabled
                        scope.launch(Dispatchers.IO) {
                            Settings.sendDiagnostics = enabled
                            Settings.dataStore.flush()
                            DiagnosticsWork.schedule(context)
                        }
                    })
                },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Text(
            stringResource(R.string.smart_diagnostics_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.smart_insights), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Text(stringResource(R.string.smart_insights_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
                },
                leadingContent = { Icon(Icons.Outlined.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { navController.navigate(smartInsightsRoute()) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
    if (showPermissions) {
        SmartHistoryPermissionDialog(
            onDismiss = { showPermissions = false },
            onPermissionsChanged = { permissionRevision++ },
        )
    }
}
