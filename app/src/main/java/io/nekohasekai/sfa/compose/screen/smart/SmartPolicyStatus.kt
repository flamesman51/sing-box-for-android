package io.nekohasekai.sfa.compose.screen.smart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.preference.PreferenceDataStore
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.preference.OnPreferenceDataStoreChangeListener
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

private data class SmartPolicyRuntime(
    val stored: String = "",
    val snapshot: SmartPolicySnapshot = SmartPolicySnapshot(),
)

private val LocalSmartPolicyRuntime = compositionLocalOf { SmartPolicyRuntime() }
private val smartPolicyReconciler = SmartPolicyReconcileTracker()

@Composable
internal fun rememberSmartPolicySnapshot(serviceStatus: Status): SmartPolicySnapshot = smartPolicySnapshotForTarget(serviceStatus, smartPolicyIsRemote(), LocalSmartPolicyRuntime.current.snapshot)

@Composable
internal fun smartStoredPolicy(): String = LocalSmartPolicyRuntime.current.stored

@Composable
internal fun smartPolicyIsRemote(): Boolean = RemoteControlManager.remoteServer.collectAsState().value != null

@Composable
fun SmartLocalProfileRoute(navController: NavController, content: @Composable () -> Unit) {
    if (smartPolicyIsRemote()) {
        LaunchedEffect(Unit) { navController.popBackStack("settings/smart", false) }
    } else {
        content()
    }
}

internal suspend fun runSmartPolicySession(
    onUnavailable: () -> Unit,
    disconnect: suspend () -> Unit,
    poll: suspend () -> Unit,
) {
    try {
        poll()
    } catch (error: CancellationException) {
        // Lifecycle cancellation preserves the snapshot while the connection is being closed.
        throw error
    } catch (_: Exception) {
        onUnavailable()
    } finally {
        disconnect()
    }
}

/** Keeps reconciliation alive across screens until a complete status snapshot is available. */
@Composable
fun SmartPolicyStatusProvider(serviceStatus: Status, content: @Composable () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val remoteServer by RemoteControlManager.remoteServer.collectAsState()
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    var stored by remember { mutableStateOf(Settings.smartPolicy) }
    var snapshot by remember(serviceStatus, remoteServer) {
        mutableStateOf(SmartPolicySnapshot(loading = smartPolicyStatusEnabled(serviceStatus, remoteServer != null)))
    }
    var instance by remember(serviceStatus, remoteServer) { mutableStateOf<Long?>(null) }
    val savedPolicies = remember(serviceStatus, remoteServer) { mutableSetOf<String>() }
    DisposableEffect(serviceStatus, remoteServer) {
        val listener = object : OnPreferenceDataStoreChangeListener {
            override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
                if (key != Settings.SMART_POLICY_KEY) return
                val next = Settings.smartPolicy
                scope.launch {
                    if (next != stored) {
                        // Saves already invoke the upstream notifier; reconciliation is only for startup.
                        if (remoteServer == null && serviceStatus == Status.Started) savedPolicies.add(next)
                        stored = next
                    }
                }
            }
        }
        Settings.dataStore.registerChangeListener(listener)
        onDispose { Settings.dataStore.unregisterChangeListener(listener) }
    }
    LaunchedEffect(lifecycle, serviceStatus, remoteServer) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshot = SmartPolicySnapshot(loading = smartPolicyStatusEnabled(serviceStatus, remoteServer != null))
            if (!smartPolicyStatusEnabled(serviceStatus, remoteServer != null)) awaitCancellation()
            val sessionScope = this
            while (isActive) {
                var tags: List<String>? = null
                var connected = true
                var active = true
                val handler = object : CommandClientHandler by SmartCommandHandler {
                    override fun writeGroups(groups: OutboundGroupIterator?) {
                        val next = groups?.let { iterator ->
                            buildList {
                                while (iterator.hasNext()) {
                                    val group = iterator.next()
                                    if (group.type == "smart") add(group.tag)
                                }
                            }
                        }
                        sessionScope.launch {
                            if (!active) return@launch
                            if (tags != next) snapshot = SmartPolicySnapshot(loading = true)
                            tags = next
                        }
                    }
                    override fun disconnected(message: String?) {
                        sessionScope.launch {
                            if (!active) return@launch
                            connected = false
                            tags = null
                            snapshot = SmartPolicySnapshot()
                        }
                    }
                }
                var connection: CommandClient? = null
                runSmartPolicySession(
                    onUnavailable = { snapshot = SmartPolicySnapshot() },
                    disconnect = {
                        active = false
                        withContext(NonCancellable + Dispatchers.IO) { runCatching { connection?.disconnect() } }
                    },
                ) {
                    // Subscribe after identifying the instance, so reloads cannot reuse an old group list.
                    val sessionInstance = withContext(Dispatchers.IO) { CommandTarget.standaloneClient().startedAt }
                    val options = CommandClientOptions().apply { addCommand(Libbox.CommandGroup) }
                    val channel = remoteServer?.let {
                        Libbox.newRemoteCommandClient(handler, options, CommandTarget.libboxOptions(it))
                    } ?: CommandClient(handler, options)
                    connection = channel
                    withContext(Dispatchers.IO) { channel.connect() }
                    while (isActive && connected) {
                        val currentTags = tags
                        if (currentTags != null) {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val before = channel.startedAt
                                    check(before == sessionInstance) { "Service changed before status snapshot" }
                                    val statuses = currentTags.map { SmartJson.status(channel.getSmartStatus(it)) }
                                    check(before == channel.startedAt) { "Service changed during status snapshot" }
                                    before to statuses
                                }
                                if (currentTags == tags) {
                                    instance = result.first
                                    snapshot = SmartPolicySnapshot(result.second)
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                snapshot = SmartPolicySnapshot()
                                connected = false
                            }
                        }
                        delay(2000)
                    }
                }
                delay(2000)
            }
        }
    }
    LaunchedEffect(serviceStatus, instance, stored, snapshot, remoteServer) {
        instance?.takeIf { remoteServer == null }?.let {
            savedPolicies.forEach { policy -> smartPolicyReconciler.markSaved(it, policy) }
            savedPolicies.clear()
            if (smartPolicyReconciler.shouldNotify(it, serviceStatus, stored, snapshot.groups)) {
                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
            }
        }
    }
    CompositionLocalProvider(LocalSmartPolicyRuntime provides SmartPolicyRuntime(stored, snapshot), content = content)
}
