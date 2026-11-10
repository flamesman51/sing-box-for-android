package io.nekohasekai.sfa.update

import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.diagnostics.DiagnosticsJournal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object UpdateState {
    val hasUpdate = mutableStateOf(false)
    val updateInfo = mutableStateOf<UpdateInfo?>(null)
    val isChecking = mutableStateOf(false)

    val isDownloading = mutableStateOf(false)
    val downloadProgress = mutableStateOf<Float?>(null)
    val downloadError = mutableStateOf<String?>(null)

    val cachedApkFile = mutableStateOf<File?>(null)

    sealed class InstallStatus {
        data object Idle : InstallStatus()
        data object Installing : InstallStatus()
        data object Success : InstallStatus()
        data class Failed(val error: String) : InstallStatus()
    }

    val installStatus = mutableStateOf<InstallStatus>(InstallStatus.Idle)

    fun setUpdate(info: UpdateInfo?) {
        val effectiveInfo = if (info == null && UpdateSource.fromString(Settings.updateSource) == UpdateSource.SERVER) {
            serverUpdateInfo()
        } else {
            info
        }
        updateInfo.value = effectiveInfo
        hasUpdate.value = effectiveInfo != null
        saveToCache(effectiveInfo)
    }

    fun setInstallStatus(status: InstallStatus) {
        installStatus.value = status
        if (status is InstallStatus.Failed) DiagnosticsJournal.record("update_failed", mapOf("stage" to "install", "type" to "InstallError", "category" to "other"))
    }

    fun clear() {
        hasUpdate.value = false
        updateInfo.value = null
        isDownloading.value = false
        downloadProgress.value = null
        downloadError.value = null
        installStatus.value = InstallStatus.Idle
        cachedApkFile.value = null
        clearCache()
    }

    fun resetDownload() {
        isDownloading.value = false
        downloadProgress.value = null
        downloadError.value = null
    }

    suspend fun loadFromCache() = withContext(Dispatchers.IO) {
        if (UpdateSource.fromString(Settings.updateSource) == UpdateSource.SERVER) {
            ServerUpdates.initialize()
            ServerUpdates.reconcileInstalled()
            setUpdate(serverUpdateInfo())
            return@withContext
        }
        val json = Settings.cachedUpdateInfo
        if (json.isBlank()) return@withContext

        val info = UpdateInfo.fromJson(json) ?: return@withContext
        if (info.versionCode <= BuildConfig.VERSION_CODE) {
            clearCache()
            return@withContext
        }

        updateInfo.value = info
        hasUpdate.value = true

        val apkPath = Settings.cachedApkPath
        if (apkPath.isNotBlank()) {
            val apkFile = File(apkPath)
            if (apkFile.exists() && apkFile.length() > 0) {
                cachedApkFile.value = apkFile
            } else {
                Settings.cachedApkPath = ""
            }
        }
    }

    private fun serverUpdateInfo(): UpdateInfo? {
        val pending = ServerUpdates.state.value.pending ?: return null
        return UpdateInfo(
            versionCode = pending.versionCode,
            versionName = pending.versionName,
            downloadUrl = pending.artifact.url,
            releaseUrl = "",
            releaseNotes = pending.notes,
            isPrerelease = false,
            fileSize = pending.artifact.size,
        )
    }

    private fun saveToCache(info: UpdateInfo?) {
        Settings.cachedUpdateInfo = info?.toJson() ?: ""
    }

    fun saveApkPath(file: File) {
        Settings.cachedApkPath = file.absolutePath
        cachedApkFile.value = file
        DiagnosticsJournal.record("update_downloaded", mapOf("from" to BuildConfig.VERSION_CODE, "to" to updateInfo.value?.versionCode))
    }

    private fun clearCache() {
        Settings.cachedUpdateInfo = ""
        Settings.cachedApkPath = ""
        Settings.lastShownUpdateVersion = 0
    }
}
