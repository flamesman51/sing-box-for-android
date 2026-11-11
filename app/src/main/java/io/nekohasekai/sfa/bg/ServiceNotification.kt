package io.nekohasekai.sfa.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.MainActivity
import io.nekohasekai.sfa.constant.Action
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.ReadyServerUpdate
import io.nekohasekai.sfa.update.ServerUpdateNotifier
import io.nekohasekai.sfa.update.ServerUpdateNotifier.putReadyIdentity
import io.nekohasekai.sfa.update.ServerUpdates
import io.nekohasekai.sfa.utils.CommandClient
import io.nekohasekai.sfa.vendor.Vendor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServiceNotification(
    private val status: MutableLiveData<Status>,
    private val service: Service,
) : BroadcastReceiver(),
    CommandClient.Handler {
    companion object {
        private const val notificationId = 1
        const val notificationChannel = "${BuildConfig.APPLICATION_ID}.service"
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        fun checkPermission(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return Application.notification.areNotificationsEnabled()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandClient =
        CommandClient(scope, CommandClient.ConnectionType.Status, this, localOnly = true)
    private var receiverRegistered = false
    private var readyObserver: Job? = null
    private var active = false
    private var contentText = ""
    private var installIdentity: ReadyServerUpdate? = null
    private var installPendingIntent: PendingIntent? = null
    private val expandedStyle = NotificationCompat.BigTextStyle()

    private val mainIntent by lazy {
        PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags,
        )
    }

    private val stopAction by lazy {
        NotificationCompat.Action
            .Builder(
                0,
                service.getText(R.string.stop),
                PendingIntent.getBroadcast(
                    service,
                    0,
                    Intent(Action.SERVICE_CLOSE).setPackage(service.packageName),
                    flags,
                ),
            ).build()
    }
    private val notificationBuilder by lazy {
        NotificationCompat
            .Builder(service, notificationChannel)
            .setShowWhen(false)
            .setOngoing(true)
            .setContentTitle(service.getString(R.string.app_name))
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_menu)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun show(
        lastProfileName: String,
        @StringRes contentTextId: Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    notificationChannel,
                    "Service Notifications",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        active = true
        contentText = service.getString(contentTextId)
        notificationBuilder.setContentTitle(lastProfileName.takeIf { it.isNotBlank() } ?: service.getString(R.string.app_name))
        service.startForeground(notificationId, render())
        if (readyObserver == null) {
            readyObserver =
                scope.launch {
                    ServerUpdates.state.map { Triple(it.ready, it.pending?.versionCode, it.pending?.artifact?.sha256) }.distinctUntilChanged().collect {
                        if (active) Application.notificationManager.notify(notificationId, render())
                    }
                }
        }
    }

    suspend fun start() {
        if (Settings.dynamicNotification && checkPermission()) {
            commandClient.connect()
            withContext(Dispatchers.Main) {
                registerReceiver()
            }
        }
    }

    private fun registerReceiver() {
        service.registerReceiver(
            this,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        receiverRegistered = true
    }

    private fun render(): Notification {
        val snapshot = ServerUpdates.state.value
        val ready =
            snapshot.ready?.takeIf {
                Vendor.hasCustomUpdate && it.versionCode == snapshot.pending?.versionCode && it.sha256 == snapshot.pending?.artifact?.sha256
            }
        if (ready != installIdentity) {
            installIdentity = ready
            installPendingIntent =
                ready?.let {
                    PendingIntent.getActivity(
                        service,
                        0,
                        ServerUpdateNotifier.installIntent(service).apply { putReadyIdentity(it) },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                }
        }
        val content =
            if (ready != null) {
                contentText + "\n" + service.getString(R.string.server_update_ready_notice)
            } else {
                contentText
            }
        notificationBuilder
            .clearActions()
            .addAction(stopAction)
            .setContentText(content)
            .setStyle(expandedStyle.bigText(content))
            .setContentIntent(installPendingIntent ?: mainIntent)
        if (ready != null) {
            notificationBuilder.addAction(0, service.getText(R.string.update), installPendingIntent)
        }
        return notificationBuilder.build()
    }

    override fun updateStatus(status: StatusMessage) {
        val content =
            Libbox.formatBytes(status.uplink) + "/s ↑\t" + Libbox.formatBytes(status.downlink) + "/s ↓"
        scope.launch {
            if (active) {
                contentText = content
                Application.notificationManager.notify(notificationId, render())
            }
        }
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                commandClient.connect()
            }

            Intent.ACTION_SCREEN_OFF -> {
                commandClient.disconnect()
            }
        }
    }

    fun close() {
        active = false
        readyObserver?.cancel()
        readyObserver = null
        commandClient.disconnect()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (receiverRegistered) {
            service.unregisterReceiver(this)
            receiverRegistered = false
        }
    }
}
