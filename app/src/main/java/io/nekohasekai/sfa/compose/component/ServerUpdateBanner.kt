package io.nekohasekai.sfa.compose.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.SettingsKey
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sfa.update.ServerUpdateNotifier
import io.nekohasekai.sfa.update.ServerUpdateSnapshot
import io.nekohasekai.sfa.update.ServerUpdateWork
import io.nekohasekai.sfa.update.ServerUpdates
import io.nekohasekai.sfa.update.UpdateSource
import io.nekohasekai.sfa.vendor.Vendor
import java.util.concurrent.TimeUnit

private fun ServerUpdateSnapshot.isReady(): Boolean =
    ready != null &&
        ready.versionCode == pending?.versionCode && ready.sha256 == pending?.artifact?.sha256

@Composable
fun ServerUpdateBanner() {
    if (!Vendor.hasCustomUpdate) return
    val snapshot by ServerUpdates.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(snapshot.ready, snapshot.pending?.versionCode) {
        ServerUpdateNotifier.refresh(context)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var degraded by remember { mutableStateOf(ServerUpdateNotifier.notificationsDegraded(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    degraded = ServerUpdateNotifier.notificationsDegraded(context)
                    ServerUpdateNotifier.refresh(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val pending = snapshot.pending ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent = {
                    Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                headlineContent = {
                    Text(
                        stringResource(if (snapshot.isReady()) R.string.server_update_ready_heading else R.string.server_update_available_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                },
                supportingContent = {
                    Text(
                        stringResource(R.string.server_update_version, pending.versionName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (degraded) {
                Text(
                    stringResource(R.string.server_update_notifications_degraded),
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (degraded) {
                    TextButton(onClick = { context.startActivity(ServerUpdateNotifier.notificationSettingsIntent(context)) }) {
                        Text(stringResource(R.string.server_update_notification_settings))
                    }
                }
                ServerUpdateAction(snapshot)
            }
        }
    }
}

@Composable
fun ServerUpdateForegroundReminder(
    allowPermissionRequest: Boolean = true,
    onDialogVisibilityChanged: (Boolean) -> Unit = {},
) {
    if (!Vendor.hasCustomUpdate) return
    val snapshot by ServerUpdates.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var updateSource by remember { mutableStateOf(Settings.updateSource) }
    var autoUpdateEnabled by remember { mutableStateOf(Settings.autoUpdateEnabled) }
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var permissionInFlight by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissionInFlight = false
            ServerUpdateNotifier.refresh(context)
        }
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var showReminder by remember { mutableStateOf(false) }
    val reminderDue =
        snapshot.pending != null && resumed &&
            (snapshot.lastReminderAtMillis == 0L || System.currentTimeMillis() - snapshot.lastReminderAtMillis >= TimeUnit.HOURS.toMillis(24))
    SideEffect { onDialogVisibilityChanged(permissionInFlight || showReminder || reminderDue) }
    DisposableEffect(lifecycleOwner) {
        val preferenceListener =
            object : OnPreferenceDataStoreChangeListener {
                override fun onPreferenceDataStoreChanged(
                    store: PreferenceDataStore,
                    key: String,
                ) {
                    when (key) {
                        SettingsKey.UPDATE_SOURCE -> updateSource = Settings.updateSource
                        SettingsKey.AUTO_UPDATE_ENABLED -> autoUpdateEnabled = Settings.autoUpdateEnabled
                    }
                }
            }
        Settings.dataStore.registerChangeListener(preferenceListener)
        val observer =
            LifecycleEventObserver { _, event ->
                resumed = event == Lifecycle.Event.ON_RESUME ||
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            Settings.dataStore.unregisterChangeListener(preferenceListener)
        }
    }
    LaunchedEffect(resumed, updateSource, autoUpdateEnabled, allowPermissionRequest) {
        if (allowPermissionRequest && resumed && !permissionRequested &&
            ServerUpdateNotifier.permissionNeeded(
                Build.VERSION.SDK_INT,
                UpdateSource.fromString(updateSource),
                autoUpdateEnabled,
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            )
        ) {
            permissionRequested = true
            permissionInFlight = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(resumed, snapshot.pending?.versionCode) {
        if (snapshot.pending == null) {
            showReminder = false
        } else if (resumed) {
            val now = System.currentTimeMillis()
            val lastReminder = ServerUpdates.state.value.lastReminderAtMillis
            if (lastReminder == 0L || now - lastReminder >= TimeUnit.HOURS.toMillis(24)) {
                ServerUpdates.markReminderShown(now)
                showReminder = true
            }
        }
    }
    if (showReminder && resumed && snapshot.pending != null) {
        ServerUpdateAvailableDialog(onDismiss = { showReminder = false })
    }
}

@Composable
fun ServerUpdateAvailableDialog(onDismiss: () -> Unit) {
    val snapshot by ServerUpdates.state.collectAsState()
    val pending = snapshot.pending
    if (pending == null) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (snapshot.isReady()) R.string.server_update_ready_heading else R.string.server_update_available_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(
                        when {
                            snapshot.isReady() -> R.string.server_update_ready_body
                            Settings.autoUpdateEnabled -> R.string.server_update_pending_auto_body
                            else -> R.string.server_update_pending_manual_body
                        },
                        pending.versionName,
                    ),
                )
                pending.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { ServerUpdateAction(snapshot, onDismiss) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.server_update_later)) }
        },
    )
}

@Composable
private fun ServerUpdateAction(
    snapshot: ServerUpdateSnapshot,
    onAction: () -> Unit = {},
) {
    val context = LocalContext.current
    TextButton(onClick = {
        if (snapshot.isReady()) {
            context.startActivity(ServerUpdateNotifier.installIntent(context))
        } else {
            ServerUpdateWork.enqueueDownload(context, downloadNow = true)
            Toast.makeText(context, R.string.server_update_download_queued, Toast.LENGTH_LONG).show()
        }
        onAction()
    }) {
        Text(stringResource(if (snapshot.isReady()) R.string.server_update_install else R.string.server_update_download_now))
    }
}
