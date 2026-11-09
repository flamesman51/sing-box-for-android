package io.nekohasekai.sfa.compose.screen.smart

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R
import java.util.Locale

@Composable
fun SmartSettingsSection() {
    val context = LocalContext.current
    var country by remember { mutableStateOf(SmartSettings.countryOverride) }
    var notifications by remember { mutableStateOf(SmartSettings.notificationsEnabled) }
    var countryDialog by remember { mutableStateOf(false) }
    var permissionDialog by remember { mutableStateOf(false) }
    var permissionRevision by remember { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionRevision++ }
    val fineGranted = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val backgroundGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
    val phoneGranted = remember(permissionRevision) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    val countries = remember {
        Locale.getISOCountries().sortedBy { Locale("", it).displayCountry }
    }

    Text(
        stringResource(R.string.smart_settings),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.smart_country)) },
            supportingContent = {
                Text(if (country.isEmpty()) stringResource(R.string.smart_country_auto) else Locale("", country).displayCountry)
            },
            modifier = Modifier.clickable { countryDialog = true },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.smart_history_permission)) },
            supportingContent = {
                Text(stringResource(if (fineGranted && backgroundGranted && phoneGranted) R.string.smart_permissions_granted else R.string.smart_permissions_optional))
            },
            modifier = Modifier.clickable { permissionDialog = true },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.smart_notifications)) },
            supportingContent = { Text(stringResource(R.string.smart_notifications_description)) },
            trailingContent = {
                Switch(
                    checked = notifications,
                    onCheckedChange = { enabled ->
                        notifications = enabled
                        SmartSettings.notificationsEnabled = enabled
                        if (!enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Application.notification.activeNotifications
                                .filter { it.id == SMART_NOTIFICATION_TYPE }
                                .forEach { Application.notification.cancel(it.tag, it.id) }
                        }
                    },
                )
            },
        )
    }
    if (countryDialog) {
        AlertDialog(
            onDismissRequest = { countryDialog = false },
            title = { Text(stringResource(R.string.smart_country)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.smart_country_auto)) },
                            supportingContent = { Text(stringResource(R.string.smart_country_description)) },
                            modifier = Modifier.clickable {
                                country = ""
                                SmartSettings.countryOverride = ""
                                countryDialog = false
                            },
                        )
                    }
                    items(countries, key = { it }) { code ->
                        ListItem(
                            headlineContent = { Text(Locale("", code).displayCountry) },
                            supportingContent = { Text(code) },
                            modifier = Modifier.clickable {
                                country = code
                                SmartSettings.countryOverride = code
                                countryDialog = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { countryDialog = false }) { Text(stringResource(R.string.smart_close)) } },
        )
    }
    if (permissionDialog) {
        AlertDialog(
            onDismissRequest = { permissionDialog = false },
            title = { Text(stringResource(R.string.smart_history_permission)) },
            text = {
                Column {
                    Text(stringResource(R.string.smart_permission_description))
                    Text(
                        stringResource(
                            R.string.smart_permission_status,
                            stringResource(if (fineGranted) R.string.smart_yes else R.string.smart_no),
                            stringResource(if (backgroundGranted) R.string.smart_yes else R.string.smart_no),
                            stringResource(if (phoneGranted) R.string.smart_yes else R.string.smart_no),
                        ),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    permissionDialog = false
                    if (!fineGranted || !phoneGranted) {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE),
                        )
                    } else if (!backgroundGranted && Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    } else {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }) { Text(stringResource(if (!fineGranted || !phoneGranted) R.string.smart_grant_permissions else R.string.smart_open_permissions)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        permissionDialog = false
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text(stringResource(R.string.smart_open_permissions)) }
                    TextButton(onClick = { permissionDialog = false }) { Text(stringResource(R.string.smart_cancel)) }
                }
            },
        )
    }
}

internal const val SMART_NOTIFICATION_TYPE = 7401
