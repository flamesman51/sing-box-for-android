package io.nekohasekai.sfa.compose.screen.smart

import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.utils.RemoteControlManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartInsightsScreen(navController: NavController, group: String, serviceStatus: Status) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    var client by remember { mutableStateOf<CommandClient?>(null) }
    var status by remember(group, remoteServer) { mutableStateOf<SmartStatus?>(null) }
    var networks by remember(group, remoteServer) { mutableStateOf<SmartNetworks?>(null) }
    var pollingError by remember { mutableStateOf<Int?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exportData by remember { mutableStateOf<String?>(null) }
    var importData by remember { mutableStateOf<String?>(null) }
    var importTrust by remember { mutableStateOf(0.1) }
    var report by remember { mutableStateOf<SmartImportReport?>(null) }
    var rawWarning by remember { mutableStateOf(false) }
    var resetConfirmation by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var actionsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(lifecycle, group, remoteServer, serviceStatus) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (remoteServer == null && serviceStatus != Status.Started) {
                status = null
                networks = null
                pollingError = R.string.smart_not_running
                awaitCancellation()
            }
            val options = CommandClientOptions()
            val connection = try {
                remoteServer?.let {
                    Libbox.newRemoteCommandClient(SmartCommandHandler, options, CommandTarget.libboxOptions(it))
                } ?: CommandClient(SmartCommandHandler, options)
            } catch (error: Exception) {
                pollingError = smartErrorResource(error)
                awaitCancellation()
            }
            client = connection
            launch(Dispatchers.IO) {
                while (isActive) {
                    try {
                        // Handler-bound clients retain one channel across calls; standalone clients do not.
                        val nextStatus = SmartJson.status(connection.getSmartStatus(group))
                        val nextNetworks = SmartJson.networks(connection.getSmartNetworks(group))
                        withContext(Dispatchers.Main) {
                            status = nextStatus
                            networks = nextNetworks
                            pollingError = null
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        withContext(Dispatchers.Main) {
                            status = null
                            networks = null
                            pollingError = smartErrorResource(error)
                        }
                    }
                    delay(2000)
                }
            }
            try {
                awaitCancellation()
            } finally {
                client = null
                withContext(NonCancellable + Dispatchers.IO) { runCatching { connection.disconnect() } }
            }
        }
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val data = exportData
        exportData = null
        if (uri != null && data != null) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open document")
                        output.bufferedWriter(Charsets.UTF_8).use { it.write(data) }
                    }
                    notice = R.string.smart_export_saved
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    actionError = context.getString(smartErrorResource(error)) + "\n" + error.localizedMessage.orEmpty()
                } finally {
                    busy = false
                }
            }
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busy = true
                try {
                    val data = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open document")
                        input.use(::readSmartImport)
                    }
                    val carrier = status?.network?.carrier ?: if (remoteServer == null) {
                        runCatching {
                            context.getSystemService(TelephonyManager::class.java)?.networkOperatorName
                        }.getOrNull().orEmpty()
                    } else {
                        ""
                    }
                    importTrust = SmartJson.importTrust(data, if (remoteServer == null) Build.MODEL else "", carrier)
                    importData = data
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    actionError = context.getString(smartErrorResource(error)) + "\n" + error.localizedMessage.orEmpty()
                } finally {
                    busy = false
                }
            }
        }
    }

    fun export(raw: Boolean) {
        val connection = client ?: return
        scope.launch {
            busy = true
            try {
                exportData = withContext(Dispatchers.IO) {
                    val data = connection.exportSmartHistory(raw)
                    if (remoteServer == null) SmartJson.exportWithDevice(data, Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME) else data
                }
                createDocument.launch(if (raw) "smart-history-raw.json" else "smart-history.json")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = context.getString(smartErrorResource(error)) + "\n" + error.localizedMessage.orEmpty()
            } finally {
                busy = false
            }
        }
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.smart_insights)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.content_description_back))
                }
            },
            actions = {
                Box {
                    IconButton(onClick = { actionsExpanded = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.content_description_more_options))
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.smart_export)) },
                            leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                            enabled = client != null && !busy && status != null,
                            onClick = {
                                actionsExpanded = false
                                export(false)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.smart_export_raw)) },
                            leadingIcon = { Icon(Icons.Outlined.FileUpload, contentDescription = null) },
                            enabled = client != null && !busy && status != null,
                            onClick = {
                                actionsExpanded = false
                                rawWarning = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.smart_import)) },
                            leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                            enabled = !busy && client != null,
                            onClick = {
                                actionsExpanded = false
                                openDocument.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                            },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.smart_reset), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            enabled = !busy && client != null && status != null,
                            onClick = {
                                actionsExpanded = false
                                resetConfirmation = true
                            },
                        )
                    }
                }
            },
        )
    }
    val scaffoldPadding = LocalScaffoldPadding.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(
            top = scaffoldPadding.calculateTopPadding() + 8.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
        ),
    ) {
        item {
            if (busy || (status == null && pollingError == null)) CircularProgressIndicator()
            pollingError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            notice?.let { Text(stringResource(it)) }
        }
        status?.let { snapshot ->
            val history = networks?.networks.orEmpty()
            item { SmartInsightsStatusCard(snapshot) }
            item { SmartInsightsHeader(R.string.smart_servers) }
            item { SmartInsightsServersCard(snapshot) }
            item { SmartInsightsHeader(R.string.smart_details) }
            item { SmartInsightsDetailsCard(snapshot) }
            item { SmartInsightsHeader(R.string.smart_networks) }
            if (history.isEmpty()) {
                item { SmartInsightsEmpty(R.string.smart_history_empty) }
            } else {
                item { SmartInsightsNetworksCard(history) }
            }
            item { SmartInsightsHeader(R.string.smart_events) }
            if (snapshot.events.isEmpty()) {
                item { SmartInsightsEmpty(R.string.smart_events_empty) }
            } else {
                item { SmartInsightsEventsCard(snapshot.events, history, snapshot.network, snapshot.members) }
            }
        }
    }

    if (rawWarning) {
        SmartConfirmDialog(
            title = stringResource(R.string.smart_export_raw),
            message = stringResource(R.string.smart_raw_warning),
            enabled = client != null && !busy,
            onDismiss = { rawWarning = false },
            onConfirm = {
                rawWarning = false
                export(true)
            },
        )
    }
    if (resetConfirmation) {
        SmartConfirmDialog(
            title = stringResource(R.string.smart_reset),
            message = stringResource(R.string.smart_reset_warning),
            enabled = client != null && !busy && status != null,
            onDismiss = { resetConfirmation = false },
            onConfirm = {
                resetConfirmation = false
                val connection = client
                if (connection != null) {
                    scope.launch {
                        busy = true
                        try {
                            // Resolve the empty Tools tag to the displayed group; empty reset means ALL groups.
                            val tag = status?.group ?: group
                            if (tag.isEmpty()) throw IOException("no_smart_group")
                            withContext(Dispatchers.IO) { connection.resetSmartHistory(tag) }
                            notice = R.string.smart_reset_done
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            actionError = context.getString(smartErrorResource(error)) + "\n" + error.localizedMessage.orEmpty()
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
    }
    importData?.let { data ->
        SmartConfirmDialog(
            title = stringResource(R.string.smart_import),
            message = stringResource(R.string.smart_import_warning, smartNumber(importTrust)),
            enabled = client != null && !busy,
            onDismiss = { importData = null },
            onConfirm = {
                val connection = client
                if (connection != null) {
                    importData = null
                    scope.launch {
                        busy = true
                        try {
                            report = withContext(Dispatchers.IO) { SmartJson.importReport(connection.importSmartHistory(data, importTrust)) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            actionError = context.getString(smartErrorResource(error)) + "\n" + error.localizedMessage.orEmpty()
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
    }
    report?.let { result ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(stringResource(R.string.smart_import_report)) },
            text = {
                LazyColumn {
                    item {
                        Text(stringResource(R.string.smart_import_source, result.sourceInstallId, smartNumber(result.trust)))
                        Text(stringResource(if (result.replacedPreviousImport) R.string.smart_import_replaced else R.string.smart_import_new))
                    }
                    items(result.applied) { entry -> Text(stringResource(R.string.smart_import_applied, entry.network, entry.mappedTo, entry.members.toString(), smartNumber(entry.weight))) }
                    items(result.ignored) { entry -> Text(stringResource(R.string.smart_import_ignored, entry.network, smartLabel(entry.reason))) }
                }
            },
            confirmButton = { TextButton(onClick = { report = null }) { Text(stringResource(R.string.close)) } },
        )
    }
    actionError?.let { message ->
        AlertDialog(
            onDismissRequest = { actionError = null },
            title = { Text(stringResource(R.string.smart_error)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { actionError = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
internal fun SmartAvailabilityNoticeText(notice: SmartAvailabilityNotice, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            when (notice) {
                SmartAvailabilityNotice.NoServersResponding -> R.string.smart_no_servers_responding
                SmartAvailabilityNotice.NetworkDown -> R.string.smart_network_down
            },
        ),
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
internal fun TimelineChart(values: List<Double?>, label: String, fixedMax: Double? = null) {
    val maximum = fixedMax ?: (values.maxOfOrNull { it?.takeIf(Double::isFinite) ?: 0.0 } ?: 1.0).coerceAtLeast(1.0)
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.smart_chart_range, label, smartNumber(maximum))
    Text(description, style = MaterialTheme.typography.bodySmall)
    Text(
        stringResource(
            R.string.smart_chart_summary,
            values.count { it != null && it.isFinite() }.toString(),
            values.size.toString(),
            smartNumber(values.lastOrNull { it != null && it.isFinite() }),
        ),
        style = MaterialTheme.typography.bodySmall,
    )
    Canvas(modifier = Modifier.fillMaxWidth().height(88.dp).semantics { contentDescription = description }) {
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
        drawLine(axis, Offset.Zero, Offset(0f, size.height))
        var previous: Offset? = null
        values.forEachIndexed { index, value ->
            if (value == null || !value.isFinite()) {
                previous = null
            } else {
                val point = Offset(
                    index.toFloat() / (values.size - 1).coerceAtLeast(1) * size.width,
                    size.height * (1 - (value / maximum).coerceIn(0.0, 1.0)).toFloat(),
                )
                previous?.let { drawLine(color, it, point, strokeWidth = 2.dp.toPx()) }
                drawCircle(color, radius = 1.5.dp.toPx(), center = point)
                previous = point
            }
        }
    }
}

@Composable
private fun SmartConfirmDialog(title: String, message: String, enabled: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(stringResource(R.string.smart_continue)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun smartNumber(value: Double?): String = value?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—"

private fun smartErrorResource(error: Exception): Int {
    val message = error.message.orEmpty()
    return when {
        "not_running" in message || "Unavailable" in message -> R.string.smart_not_running
        "no_smart_group" in message -> R.string.smart_no_group
        "not_found" in message -> R.string.smart_not_found
        "too_large" in message -> R.string.smart_too_large
        "unsupported_version" in message -> R.string.smart_unsupported_version
        "invalid" in message -> R.string.smart_invalid
        else -> R.string.smart_error
    }
}

@Composable
private fun smartLabel(value: String): String {
    val resource = when (value) {
        "foreign_redacted_key" -> R.string.smart_foreign_key
        "unknown_member" -> R.string.smart_unknown_member
        "stale" -> R.string.smart_stale
        "invalid" -> R.string.smart_invalid
        else -> null
    }
    return resource?.let { stringResource(it) } ?: value
}

// No subscriptions are requested: this client is only a persistent JSON command channel.
internal object SmartCommandHandler : CommandClientHandler {
    override fun connected() {}
    override fun disconnected(message: String?) {}
    override fun clearLogs() {}
    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
    override fun setDefaultLogLevel(level: Int) {}
    override fun updateClashMode(newMode: String?) {}
    override fun writeConnectionEvents(events: ConnectionEvents?) {}
    override fun writeGroups(groups: OutboundGroupIterator?) {}
    override fun writeLogs(logs: LogIterator?) {}
    override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) {}
    override fun writeStatus(status: StatusMessage?) {}
}
