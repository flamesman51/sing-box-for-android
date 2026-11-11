package io.nekohasekai.sfa.vendor

import android.content.Context
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.update.ServerUpdateNotifier
import io.nekohasekai.sfa.update.ServerUpdates
import io.nekohasekai.sfa.update.UpdateSource
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.xposed.XposedActivation
import java.io.File

enum class InstallMethod {
    PACKAGE_INSTALLER,
    SHIZUKU,
    ROOT,
}

object ApkInstaller {
    fun getConfiguredMethod(): InstallMethod {
        if (HookStatusClient.status.value?.active == true ||
            XposedActivation.isActivated(Application.application)
        ) {
            return InstallMethod.ROOT
        }
        return if (Settings.silentInstallEnabled) {
            InstallMethod.valueOf(Settings.silentInstallMethod)
        } else {
            InstallMethod.PACKAGE_INSTALLER
        }
    }

    suspend fun install(
        context: Context,
        apkFile: File,
        method: InstallMethod = getConfiguredMethod(),
    ) {
        if (UpdateSource.fromString(Settings.updateSource) == UpdateSource.SERVER ||
            ServerUpdates.state.value.ready
                ?.path == apkFile.absolutePath
        ) {
            context.startActivity(ServerUpdateNotifier.installIntent(context))
            return
        }
        when (method) {
            InstallMethod.SHIZUKU -> ShizukuInstaller.install(apkFile)
            InstallMethod.ROOT -> RootInstaller.install(apkFile)
            InstallMethod.PACKAGE_INSTALLER -> SystemPackageInstaller.install(context, apkFile)
        }
    }

    fun canSystemSilentInstall(): Boolean = SystemPackageInstaller.canSystemSilentInstall()

    suspend fun canSilentInstall(): Boolean {
        val method = getConfiguredMethod()
        return when (method) {
            InstallMethod.PACKAGE_INSTALLER -> canSystemSilentInstall()
            InstallMethod.SHIZUKU -> ShizukuInstaller.isAvailable() && ShizukuInstaller.checkPermission()
            InstallMethod.ROOT -> RootClient.checkRootAvailable()
        }
    }
}
