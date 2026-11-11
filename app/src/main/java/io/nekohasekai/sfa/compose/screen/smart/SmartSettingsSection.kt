package io.nekohasekai.sfa.compose.screen.smart

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import io.nekohasekai.sfa.R

@Composable
fun SmartHistoryPermissionDialog(
    onDismiss: () -> Unit,
    onInteraction: () -> Unit = {},
    onPermissionsChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var revision by remember { mutableStateOf(0) }
    val permissions = rememberSmartHistoryPermissions(revision)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        onPermissionsChanged()
    }
    val requestable = !SmartSettings.historyForegroundRequested || activity?.let {
        (permissions.fine || ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)) &&
            (permissions.phone || ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.READ_PHONE_STATE))
    } == true
    val step = permissions.nextStep(Build.VERSION.SDK_INT, requestable)

    AlertDialog(
        onDismissRequest = {
            onInteraction()
            onDismiss()
        },
        icon = { Icon(Icons.Outlined.History, contentDescription = null) },
        title = { Text(stringResource(R.string.smart_history_permission)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.smart_permission_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.smart_permission_phone_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                PermissionStatusRow(stringResource(R.string.smart_permission_precise), permissions.fine)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    PermissionStatusRow(stringResource(R.string.smart_permission_background), permissions.background)
                }
                PermissionStatusRow(stringResource(R.string.smart_permission_phone), permissions.phone)
                if (step == SmartPermissionStep.BackgroundSettings && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.smart_permission_background_settings, context.packageManager.backgroundPermissionOptionLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onInteraction()
                when (step) {
                    SmartPermissionStep.Foreground -> {
                        launcher.launch(
                            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE),
                        )
                        SmartSettings.historyForegroundRequested = true
                        revision++
                    }
                    SmartPermissionStep.BackgroundRequest -> launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    SmartPermissionStep.ForegroundSettings, SmartPermissionStep.BackgroundSettings -> {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                    SmartPermissionStep.Done -> onDismiss()
                }
            }) {
                Text(
                    stringResource(
                        when (step) {
                            SmartPermissionStep.Foreground, SmartPermissionStep.BackgroundRequest -> R.string.smart_continue
                            SmartPermissionStep.ForegroundSettings, SmartPermissionStep.BackgroundSettings -> R.string.smart_permission_open_settings
                            SmartPermissionStep.Done -> R.string.smart_permission_done
                        },
                    ),
                )
            }
        },
        dismissButton = {
            if (step != SmartPermissionStep.Done) {
                TextButton(onClick = {
                    onInteraction()
                    onDismiss()
                }) { Text(stringResource(R.string.smart_permission_not_now)) }
            }
        },
    )
}

@Composable
private fun PermissionStatusRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = stringResource(if (granted) R.string.smart_yes else R.string.smart_no),
            modifier = Modifier.size(20.dp),
            tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

internal const val SMART_NOTIFICATION_TYPE = 7401
