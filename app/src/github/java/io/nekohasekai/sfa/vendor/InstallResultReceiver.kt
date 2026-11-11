package io.nekohasekai.sfa.vendor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.update.ServerUpdateInstallActivity
import io.nekohasekai.sfa.update.ServerUpdateNotifier
import io.nekohasekai.sfa.update.UpdateState

class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_INSTALL_COMPLETE = "${BuildConfig.APPLICATION_ID}.INSTALL_COMPLETE"
        private const val TAG = "InstallResultReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != ACTION_INSTALL_COMPLETE) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val ready = ServerUpdateNotifier.readyIdentity(intent)

        Log.d(TAG, "Install result: status=$status, message=$message")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                if (!ServerUpdateInstallActivity.deliverResult(sessionId, ready, status, confirmIntent)) {
                    confirmIntent?.let { ServerUpdateNotifier.postConfirmation(context, sessionId, it, ready) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                ServerUpdateNotifier.cancelConfirmation(context, sessionId)
                ServerUpdateInstallActivity.deliverResult(sessionId, ready, status, null)
                Log.d(TAG, "Installation successful")
                UpdateState.setInstallStatus(UpdateState.InstallStatus.Success)
            }
            else -> {
                ServerUpdateNotifier.cancelConfirmation(context, sessionId)
                ServerUpdateInstallActivity.deliverResult(sessionId, ready, status, null)
                Log.e(TAG, "Installation failed: $status - $message")
                UpdateState.setInstallStatus(UpdateState.InstallStatus.Failed(message ?: context.getString(R.string.server_update_install_failed)))
            }
        }
    }
}
