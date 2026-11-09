package io.nekohasekai.sfa.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.vendor.Vendor

object ServerUpdateNotifier {
    const val CHANNEL_ID = "${BuildConfig.APPLICATION_ID}.app-updates"
    const val ACTION_INSTALL = "${BuildConfig.APPLICATION_ID}.INSTALL_SERVER_UPDATE"
    const val ACTION_CONFIRM = "${BuildConfig.APPLICATION_ID}.CONFIRM_APP_UPDATE"
    const val EXTRA_VERSION = "server_update_version"
    const val EXTRA_SHA256 = "server_update_sha256"
    const val EXTRA_PATH = "server_update_path"
    private const val READY_TAG = "server-update-ready"
    private const val CONFIRM_TAG = "app-update-confirmation"
    private const val INSTALL_ACTIVITY = "io.nekohasekai.sfa.update.ServerUpdateInstallActivity"

    fun installIntent(context: Context): Intent = activityIntent(context, ACTION_INSTALL).apply {
        ServerUpdates.state.value.ready?.let { putReadyIdentity(it) }
    }

    fun refresh(context: Context) {
        if (!Vendor.hasCustomUpdate) {
            NotificationManagerCompat.from(context).cancel(READY_TAG, 0)
            return
        }
        createChannel(context)
        val snapshot = ServerUpdates.state.value
        val ready = snapshot.ready
        val pending = snapshot.pending
        val manager = NotificationManagerCompat.from(context)
        if (ready == null || pending == null || ready.versionCode != pending.versionCode ||
            ready.sha256 != pending.artifact.sha256
        ) {
            manager.cancel(READY_TAG, 0)
            return
        }
        val install = PendingIntent.getActivity(
            context,
            0,
            installIntent(context).apply { putReadyIdentity(ready) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context,
            READY_TAG,
            0,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle(context.getString(R.string.server_update_ready_title, pending.versionName))
                .setContentText(context.getString(R.string.server_update_ready_notice))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .setContentIntent(install)
                .addAction(0, context.getString(R.string.server_update_install), install),
        )
    }

    fun postConfirmation(context: Context, sessionId: Int, confirmation: Intent, ready: ReadyServerUpdate?) {
        createChannel(context)
        val intent = activityIntent(context, ACTION_CONFIRM).apply {
            data = android.net.Uri.parse("sfa-update://confirm/$sessionId")
            putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(Intent.EXTRA_INTENT, confirmation)
            ready?.let { putReadyIdentity(it) }
        }
        val action = PendingIntent.getActivity(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            context,
            CONFIRM_TAG,
            sessionId,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_menu)
                .setContentTitle(context.getString(R.string.server_update_confirm_title))
                .setContentText(context.getString(R.string.server_update_confirm_notice))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(action)
                .addAction(0, context.getString(R.string.server_update_install), action),
        )
    }

    fun cancelConfirmation(context: Context, sessionId: Int) {
        NotificationManagerCompat.from(context).cancel(CONFIRM_TAG, sessionId)
    }

    fun notificationsDegraded(context: Context): Boolean {
        createChannel(context)
        if (!canNotify(context)) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            (
                context.getSystemService(NotificationManager::class.java)
                    .getNotificationChannel(CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_NONE
                ) < NotificationManager.IMPORTANCE_HIGH
    }

    fun notificationSettingsIntent(context: Context): Intent = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> Intent(
            if (canNotify(context)) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS,
        ).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)
        }
        else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
    }

    fun Intent.putReadyIdentity(ready: ReadyServerUpdate) {
        putExtra(EXTRA_VERSION, ready.versionCode)
        putExtra(EXTRA_SHA256, ready.sha256)
        putExtra(EXTRA_PATH, ready.path)
        if (action == ACTION_INSTALL) {
            data = android.net.Uri.parse("sfa-update://install/${ready.versionCode}/${ready.sha256}")
        }
    }

    fun readyIdentity(intent: Intent): ReadyServerUpdate? {
        val version = intent.getIntExtra(EXTRA_VERSION, 0)
        val sha256 = intent.getStringExtra(EXTRA_SHA256) ?: return null
        val path = intent.getStringExtra(EXTRA_PATH) ?: return null
        return ReadyServerUpdate(version, sha256, path).takeIf { version > 0 }
    }

    private fun activityIntent(context: Context, action: String): Intent = Intent(action)
        .setClassName(context, INSTALL_ACTIVITY)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.server_update_channel), NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )

    private fun post(context: Context, tag: String, id: Int, builder: NotificationCompat.Builder) {
        if (!canNotify(context)) return
        try {
            NotificationManagerCompat.from(context).notify(tag, id, builder.build())
        } catch (_: SecurityException) {
            // Notification permission can be revoked between the check and notify.
        }
    }
}
