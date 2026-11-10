package io.nekohasekai.sfa.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import go.Seq
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Alert
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.diagnostics.DiagnosticsJournal
import io.nekohasekai.sfa.diagnostics.DiagnosticsWork
import io.nekohasekai.sfa.ktx.hasPermission
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) : CommandServerHandler {
    companion object {
        private const val PROFILE_UPDATE_INTERVAL = 15L * 60 * 1000 // 15 minutes in milliseconds
        private const val TAG = "BoxService"
        private const val EXTRA_START_REASON = "start_reason"

        @Volatile
        var activeDiagnosticsProfileId: Long? = null
            private set

        @Volatile
        var activeDiagnosticsProfileSource: String? = null
            private set

        @OptIn(DelicateCoroutinesApi::class)
        fun start(reason: ServiceStartReason = ServiceStartReason.User) = GlobalScope.launch(Dispatchers.Main.immediate) {
            Settings.dataStore.initialize()
            val intent =
                Intent(Application.application, Settings.serviceClass())
                    .putExtra(EXTRA_START_REASON, reason.name)
            ContextCompat.startForegroundService(Application.application, intent)
        }

        @OptIn(DelicateCoroutinesApi::class)
        fun stop() {
            GlobalScope.launch(Dispatchers.Main.immediate) {
                Settings.dataStore.initialize()
                Settings.shouldRun = ServiceStartPolicy.shouldRunAfterStop(ServiceStopReason.User)
                Settings.dataStore.flush()
                Application.application.sendBroadcast(
                    Intent(Action.SERVICE_CLOSE).setPackage(
                        Application.application.packageName,
                    ),
                )
            }
        }

        internal fun startReason(intent: Intent?): ServiceStartReason = when {
            intent == null -> ServiceStartReason.StickyRestart
            intent.action == VpnService.SERVICE_INTERFACE -> ServiceStartReason.AlwaysOn
            intent.getStringExtra(EXTRA_START_REASON) == ServiceStartReason.Boot.name -> ServiceStartReason.Boot
            else -> ServiceStartReason.User
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private lateinit var commandServer: CommandServer
    private val idleModeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val idleModeUpdates = Channel<Boolean>(Channel.UNLIMITED)
    private var startupJob: Job? = null
    private var shutdownJob: Job? = null

    // A stop invalidates start requests that are still loading preferences.
    private var stopGeneration = 0
    private var lastStartId = 0
    private var activeStartReason = ServiceStartReason.User
    private var diagnosticsProfileId: Long? = null
    private var diagnosticsProfileSource: String? = null

    init {
        idleModeScope.launch {
            for (idle in idleModeUpdates) {
                if (idle) {
                    commandServer.pause()
                } else {
                    commandServer.wake()
                }
            }
        }
    }

    private var receiverRegistered = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun startCommandServer() {
        Libbox.promoteOOMDraft()
        Libbox.discardPowerReportDraft()
        val commandServer = CommandServer(this, platformInterface)
        commandServer.start()
        this.commandServer = commandServer
    }

    private var lastProfileName = ""

    private suspend fun startService() {
        try {
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            val selectedProfileId = Settings.selectedProfile
            diagnosticsProfileId = selectedProfileId.takeIf { it != -1L }
            diagnosticsProfileSource = null
            if (selectedProfileId == -1L) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            val profile = ProfileManager.get(selectedProfileId)
            if (profile == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }
            diagnosticsProfileSource = profile.typed.remoteURL

            val content = File(profile.typed.path).readText()
            if (content.isBlank()) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            lastProfileName = profile.name
            withContext(Dispatchers.Main) {
                notification.show(lastProfileName, R.string.status_starting)
            }

            DefaultNetworkMonitor.start()

            try {
                commandServer.startOrReloadService(
                    content,
                    OverrideOptions().apply {
                        autoRedirect = Settings.autoRedirect
                        if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                            val appList = Settings.getEffectivePerAppProxyList()
                            if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                                includePackage =
                                    PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                            } else {
                                excludePackage =
                                    PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }

            if (commandServer.needWIFIState()) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            withContext(Dispatchers.Main) {
                activeDiagnosticsProfileSource = diagnosticsProfileSource
                activeDiagnosticsProfileId = selectedProfileId
                DiagnosticsJournal.record("vpn_start", mapOf("reason" to activeStartReason.name), profileId = diagnosticsProfileId, profileSource = diagnosticsProfileSource)
                Log.i(TAG, "VPN started: ${activeStartReason.name}")
                status.value = Status.Started
                notification.show(lastProfileName, R.string.status_started)
            }
            notification.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun serviceStop() {
        GlobalScope.launch(Dispatchers.Main.immediate) {
            stopService()
        }
    }

    override fun serviceReload() {
        runBlocking {
            try {
                serviceReload0()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
            }
        }
    }

    suspend fun serviceReload0() {
        activeDiagnosticsProfileId = null
        activeDiagnosticsProfileSource = null
        val selectedProfileId = Settings.selectedProfile
        diagnosticsProfileId = selectedProfileId.takeIf { it != -1L }
        diagnosticsProfileSource = null
        if (selectedProfileId == -1L) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }

        val profile = ProfileManager.get(selectedProfileId)
        if (profile == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        diagnosticsProfileSource = profile.typed.remoteURL

        val content = File(profile.typed.path).readText()
        if (content.isBlank()) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        lastProfileName = profile.name
        try {
            commandServer.startOrReloadService(
                content,
                OverrideOptions().apply {
                    autoRedirect = Settings.autoRedirect
                    if (Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled) {
                        val appList = Settings.getEffectivePerAppProxyList()
                        if (Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE) {
                            includePackage = PlatformInterfaceWrapper.StringArray((appList + Application.application.packageName).iterator())
                        } else {
                            excludePackage = PlatformInterfaceWrapper.StringArray((appList - Application.application.packageName).iterator())
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (commandServer.needWIFIState()) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
        activeDiagnosticsProfileSource = diagnosticsProfileSource
        activeDiagnosticsProfileId = selectedProfileId
    }

    override fun getSystemProxyStatus(): SystemProxyStatus? {
        val status = SystemProxyStatus()
        if (service is VPNService) {
            status.available = service.systemProxyAvailable
            status.enabled = service.systemProxyEnabled
        }
        return status
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        serviceReload()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (::commandServer.isInitialized) {
            idleModeUpdates.trySend(Application.powerManager.isDeviceIdleMode)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService(
        reason: ServiceStopReason? = ServiceStopReason.User,
        alert: Alert? = null,
        message: String? = null,
    ) {
        DiagnosticsJournal.record("vpn_stop", mapOf("reason" to (reason?.name ?: "ProcessDestroyed"), "alert" to alert?.name), profileId = diagnosticsProfileId, profileSource = diagnosticsProfileSource)
        activeDiagnosticsProfileId = null
        activeDiagnosticsProfileSource = null
        Log.i(TAG, "VPN stopping: ${reason?.name ?: "ProcessDestroyed"}")
        if (reason != null) {
            stopGeneration++
        }
        if (status.value == Status.Stopping) {
            if (reason != null) {
                val previousShutdown = shutdownJob
                shutdownJob = GlobalScope.launch(Dispatchers.IO) {
                    Settings.dataStore.initialize()
                    Settings.shouldRun = ServiceStartPolicy.shouldRunAfterStop(reason)
                    Settings.dataStore.flush()
                    previousShutdown?.join()
                }
            }
            return
        }
        status.value = Status.Stopping
        // Preserve newer start requests that arrive while teardown is in progress.
        val stoppingStartId = lastStartId
        val startup = startupJob
        startup?.cancel()
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        shutdownJob = GlobalScope.launch(Dispatchers.IO) {
            Settings.dataStore.initialize()
            if (reason != null) {
                Settings.shouldRun = ServiceStartPolicy.shouldRunAfterStop(reason)
                Settings.dataStore.flush()
            }
            try {
                closeService()
                startup?.join()
                closeService()
                val pfd = fileDescriptor
                if (pfd != null) {
                    pfd.close()
                    fileDescriptor = null
                }
                DefaultNetworkMonitor.stop()
                if (::commandServer.isInitialized) {
                    commandServer.apply {
                        close()
//                        Seq.destroyRef(refnum)
                    }
                }
                Libbox.discardPowerReportDraft()
                PowerReportManager.refresh()
            } finally {
                withContext(Dispatchers.Main) {
                    if (alert != null) {
                        binder.broadcast { callback ->
                            callback.onServiceAlert(alert.ordinal, message)
                        }
                    }
                    status.value = Status.Stopped
                    if (stoppingStartId == 0) {
                        service.stopSelf()
                    } else {
                        service.stopSelf(stoppingStartId)
                    }
                }
            }
        }
    }

    private fun closeService() {
        if (!::commandServer.isInitialized) return
        runCatching {
            commandServer.closeService()
        }.onFailure {
            commandServer.setError("android: close service: ${it.message}")
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        DiagnosticsJournal.record("vpn_start_failed", mapOf("reason" to activeStartReason.name, "failure" to type.name, "message" to message), profileId = diagnosticsProfileId, profileSource = diagnosticsProfileSource)
        Log.e(TAG, "VPN start failed: ${type.name}")
        DiagnosticsWork.enqueue(service)
        withContext(Dispatchers.Main) {
            stopService(ServiceStopReason.FatalConfiguration, type, message)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(reason: ServiceStartReason, startId: Int): Int {
        lastStartId = startId
        val generation = stopGeneration
        DiagnosticsJournal.record("vpn_start_request", mapOf("reason" to reason.name, "start_id" to startId, "state" to status.value?.name))
        Log.i(TAG, "VPN start requested: ${reason.name}, state=${status.value}")
        if (status.value == Status.Stopped) {
            notification.show(lastProfileName, R.string.status_starting)
        }

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.Main.immediate) {
            try {
                Settings.dataStore.initialize()
                shutdownJob?.join()
                if (generation != stopGeneration) {
                    DiagnosticsJournal.record("vpn_start_skipped", mapOf("reason" to reason.name, "decision" to "superseded_by_stop"))
                    service.stopSelf(startId)
                    return@launch
                }
                val persistedShouldRun = Settings.shouldRun
                if (!ServiceStartPolicy.shouldRun(reason, persistedShouldRun)) {
                    DiagnosticsJournal.record("vpn_start_skipped", mapOf("reason" to reason.name, "decision" to "not_requested"))
                    notification.close()
                    if (status.value == Status.Stopped) {
                        service.stopSelf(startId)
                    } else {
                        stopService(reason = null)
                    }
                    return@launch
                }
                Settings.shouldRun = true
                if (status.value != Status.Stopped) {
                    DiagnosticsJournal.record("vpn_start_skipped", mapOf("reason" to reason.name, "decision" to "already_active"))
                    Settings.dataStore.flush()
                    return@launch
                }
                status.value = Status.Starting
                activeStartReason = reason
                startupJob = coroutineContext[Job]
                Settings.dataStore.flush()
                Application.application.coreReady.await()
                withContext(Dispatchers.IO) {
                    try {
                        startCommandServer()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        stopAndAlert(Alert.StartCommandServer, e.message)
                        return@withContext
                    }
                    ensureActive()
                    startService()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                stopAndAlert(Alert.StartService, error.message)
            }
        }
        return Service.START_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        stopGeneration++
        if (status.value != Status.Stopped) {
            stopService(reason = null)
        }
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        idleModeUpdates.cancel()
        idleModeScope.cancel()
        binder.close()
    }

    internal fun onRevoke() {
        stopService(ServiceStopReason.Revoke)
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "${BuildConfig.APPLICATION_ID}.notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_menu)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(
                        service,
                        MainActivity::class.java,
                    ).apply {
                        setAction(Action.OPEN_URL).setData(Uri.parse(notification.openURL))
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Application.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            Application.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            Application.notification.cancel(identifier, typeID)
        }
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message!!)
    }

    override fun connectSSHAgent(): Int = -1
}
