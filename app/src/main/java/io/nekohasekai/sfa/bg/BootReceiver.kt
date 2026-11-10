package io.nekohasekai.sfa.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.diagnostics.DiagnosticsJournal
import io.nekohasekai.sfa.diagnostics.DiagnosticsProjection
import io.nekohasekai.sfa.diagnostics.DiagnosticsWork
import io.nekohasekai.sfa.update.ServerUpdateNotifier
import io.nekohasekai.sfa.update.ServerUpdates
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        val action = intent.action?.substringAfterLast('.')
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                Settings.dataStore.initialize()
                val packageReplaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                if (packageReplaced) {
                    DiagnosticsJournal.record(
                        "update_installed",
                        mapOf("from" to Application.application.previousVersionCode, "to" to BuildConfig.VERSION_CODE),
                    )
                    DiagnosticsWork.enqueue(context)
                }
                val hasUnreadCrashes = if (!packageReplaced && Settings.shouldRun) {
                    CrashReportManager.refresh()
                    CrashReportManager.unreadCount.value > 0
                } else {
                    false
                }
                val decision = ServiceStartPolicy.receiverDecision(packageReplaced, Settings.shouldRun, hasUnreadCrashes)
                DiagnosticsJournal.record("boot_receiver", mapOf("action" to action, "decision" to decision.name))
                Log.i("BootReceiver", "VPN restart: ${intent.action}, decision=${decision.name}")
                when (decision) {
                    ReceiverStartDecision.Start -> withContext(Dispatchers.Main) {
                        BoxService.start(ServiceStartReason.Boot).join()
                    }
                    ReceiverStartDecision.UnreadCrashReports -> Settings.shouldRun = false
                    ReceiverStartDecision.NotRequested -> Unit
                }
                if (packageReplaced) {
                    ServerUpdates.initialize()
                    ServerUpdates.reconcileInstalled()
                    ServerUpdateNotifier.refresh(context)
                }
            } catch (error: Exception) {
                DiagnosticsJournal.record("boot_receiver", mapOf("action" to action, "decision" to "failed") + DiagnosticsProjection.failure(error))
                Log.e("BootReceiver", "VPN restart receiver failed")
            } finally {
                Settings.dataStore.flush()
                pendingResult.finish()
            }
        }
    }
}
