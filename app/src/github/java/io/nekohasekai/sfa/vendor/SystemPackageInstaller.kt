package io.nekohasekai.sfa.vendor

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.update.ReadyServerUpdate
import io.nekohasekai.sfa.update.ServerUpdateChecker
import io.nekohasekai.sfa.update.ServerUpdateNotifier.putReadyIdentity
import io.nekohasekai.sfa.update.ServerUpdates
import java.io.File
import java.io.FileInputStream
import android.content.pm.PackageInstaller as AndroidPackageInstaller

object SystemPackageInstaller {

    fun canSystemSilentInstall(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun install(context: Context, apkFile: File) {
        commit(context, apkFile, null) {}
    }

    internal fun installServer(context: Context, ready: ReadyServerUpdate, existingSessionId: Int = -1, cancellation: InstallCancellation, onSessionCreated: (Int) -> Unit): Int {
        cancellation.check()
        val apkFile = ServerUpdateChecker.verifiedReadyApk(context)
        check(apkFile != null && apkFile.absolutePath == ready.path && ServerUpdates.state.value.ready == ready) {
            context.getString(R.string.server_update_unavailable)
        }
        return commit(context, apkFile, ready, existingSessionId, cancellation, onSessionCreated)
    }

    private fun commit(
        context: Context,
        apkFile: File,
        ready: ReadyServerUpdate?,
        existingSessionId: Int = -1,
        cancellation: InstallCancellation? = null,
        onSessionCreated: (Int) -> Unit,
    ): Int {
        val packageInstaller = context.packageManager.packageInstaller
        val params = AndroidPackageInstaller.SessionParams(AndroidPackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(AndroidPackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }

        val existingSession = if (existingSessionId >= 0) {
            packageInstaller.getSessionInfo(existingSessionId).also {
                check(it != null && it.installerPackageName == context.packageName && it.appPackageName == context.packageName) {
                    context.getString(R.string.server_update_confirmation_unavailable)
                }
            }
        } else {
            null
        }
        cancellation?.check()
        val sessionId = existingSession?.sessionId ?: packageInstaller.createSession(params)
        if (existingSession?.isSealed == true) {
            onSessionCreated(sessionId)
            return sessionId
        }
        try {
            onSessionCreated(sessionId)
            cancellation?.check()
            packageInstaller.openSession(sessionId).use { session ->
                if (existingSession == null) {
                    session.openWrite("update.apk", 0, apkFile.length()).use { outputStream ->
                        FileInputStream(apkFile).use { inputStream ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                cancellation?.check()
                                val count = inputStream.read(buffer)
                                if (count < 0) break
                                outputStream.write(buffer, 0, count)
                            }
                        }
                        session.fsync(outputStream)
                    }
                }

                val intent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = InstallResultReceiver.ACTION_INSTALL_COMPLETE
                    putExtra(AndroidPackageInstaller.EXTRA_SESSION_ID, sessionId)
                    ready?.let { putReadyIdentity(it) }
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
                )
                check(ready == null || ServerUpdates.state.value.ready == ready) {
                    context.getString(R.string.server_update_unavailable)
                }
                if (cancellation == null) {
                    session.commit(pendingIntent.intentSender)
                } else {
                    cancellation.commit { session.commit(pendingIntent.intentSender) }
                }
            }
        } catch (exception: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw exception
        }
        return sessionId
    }
}
