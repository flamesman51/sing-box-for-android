package io.nekohasekai.sfa.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.nekohasekai.sfa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CancellationException

object ServerUpdateChecker {
    private val artifactLock = Any()
    private val artifactName = Regex("(\\d+)-[0-9a-f]{16}\\.apk(?:\\.part)?")

    /** Blocking network check; a stale or failed response cannot erase an accepted candidate. */
    fun check(context: Context): UpdateInfo? {
        ServerUpdates.initialize()
        ServerUpdates.reconcileInstalled()
        val snapshot = ServerUpdates.state.value
        if (snapshot.origin != null && snapshot.manifestUrl != null) {
            try {
                val candidate = response(snapshot.manifestUrl, snapshot.origin) { connection ->
                    connection.inputStream.use {
                        ServerUpdatePolicy.parseManifest(
                            ServerUpdatePolicy.readManifest(it),
                            snapshot.manifestUrl,
                            snapshot.origin,
                            BuildConfig.APPLICATION_ID,
                            Build.SUPPORTED_ABIS.toList(),
                            System.currentTimeMillis(),
                        )
                    }
                }
                ServerUpdatePolicy.checkInterrupted()
                ServerUpdates.accept(candidate, snapshot.manifestUrl, installedVersion(context))
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                ServerUpdatePolicy.checkInterrupted()
                Log.w("ServerUpdates", "Server update check failed; retaining pending update")
            }
        }
        ServerUpdateWork.enqueueDownload(context)
        val current = ServerUpdates.state.value
        return current.pending?.let {
            UpdateInfo(it.versionCode, it.versionName, it.artifact.url, current.origin.orEmpty(), it.notes, false, it.artifact.size)
        }
    }

    suspend fun download(context: Context): File? = runInterruptible(Dispatchers.IO) {
        synchronized(artifactLock) {
            ServerUpdatePolicy.checkInterrupted()
            ServerUpdates.initialize()
            verifiedReadyApk(context)?.let { return@synchronized it }
            val snapshot = ServerUpdates.state.value
            val pending = snapshot.pending ?: return@synchronized null
            // Forgetting discovery must not strand an already accepted, immutable update.
            val origin = ServerUpdatePolicy.origin(pending.artifact.url) ?: throw IOException("Invalid artifact origin")
            if (pending.versionCode <= installedVersion(context)) return@synchronized null
            val directory = updatesDirectory(context)
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update storage")
            val apk = artifactFile(context, pending)
            if (apk.exists()) {
                if (verifyApk(context, apk, pending)) {
                    if (!apk.setReadOnly()) throw IOException("Cannot protect update artifact")
                    syncServerUpdateDirectory(directory)
                    if (ServerUpdates.publishReady(pending, apk.absolutePath)) {
                        deleteOlderArtifacts(context, apk)
                        ServerUpdateNotifier.refresh(context)
                        return@synchronized apk
                    }
                    return@synchronized null
                }
                if (!apk.delete()) throw IOException("Cannot remove invalid update artifact")
            }
            val temporary = File(directory, "${apk.name}.part")
            if (temporary.exists() && !temporary.delete()) throw IOException("Cannot remove temporary update artifact")
            try {
                response(pending.artifact.url, origin) { connection ->
                    connection.inputStream.use { input ->
                        FileOutputStream(temporary).use { output ->
                            val size = ServerUpdatePolicy.copyBounded(input, output, pending.artifact.size)
                            if (size != pending.artifact.size) throw IOException("Update artifact size mismatch")
                            output.fd.sync()
                        }
                    }
                }
                if (!verifyApk(context, temporary, pending)) throw IOException("Update artifact verification failed")
                ServerUpdatePolicy.checkInterrupted()
                if (!temporary.setReadOnly()) throw IOException("Cannot protect update artifact")
                if (apk.exists() || !temporary.renameTo(apk)) throw IOException("Cannot promote update artifact")
                syncServerUpdateDirectory(directory)
                if (!ServerUpdates.publishReady(pending, apk.absolutePath)) return@synchronized null
                deleteOlderArtifacts(context, apk)
                ServerUpdateNotifier.refresh(context)
                apk
            } finally {
                if (temporary.exists() && !temporary.delete()) Log.w("ServerUpdates", "Could not remove temporary update artifact")
            }
        }
    }

    /** Every installation rechecks the exact ready identity, not a cached nonempty file. */
    fun verifiedReadyApk(context: Context): File? = synchronized(artifactLock) {
        ServerUpdates.initialize()
        val snapshot = ServerUpdates.state.value
        val pending = snapshot.pending ?: return@synchronized null
        val ready = snapshot.ready ?: return@synchronized null
        val expected = artifactFile(context, pending)
        val identityMatches = ready.versionCode == pending.versionCode && ready.sha256 == pending.artifact.sha256 && ready.path == expected.absolutePath
        val valid = identityMatches && try {
            verifyApk(context, expected, pending)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            false
        }
        if (!valid) {
            ServerUpdates.discardReady(ready)
            if (identityMatches && expected.exists() && !expected.delete()) Log.w("ServerUpdates", "Could not remove invalid update artifact")
            return@synchronized null
        }
        if (ServerUpdates.state.value.pending != pending || ServerUpdates.state.value.ready != ready) return@synchronized null
        expected
    }

    @Suppress("DEPRECATION")
    internal fun installedVersion(context: Context): Long = version(context.packageManager.getPackageInfo(BuildConfig.APPLICATION_ID, 0))

    @Suppress("DEPRECATION")
    private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty()
    } else {
        info.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File, pending: PendingServerUpdate): Boolean {
        if (!file.isFile || file.length() != pending.artifact.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                ServerUpdatePolicy.checkInterrupted()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (!MessageDigest.isEqual(digest.digest(), decodeDigest(pending.artifact.sha256))) return false
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: return false
        val installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, flags)
        return ServerUpdatePolicy.matchesPackage(archive.packageName, version(archive), signers(archive), BuildConfig.APPLICATION_ID, pending.versionCode, version(installed), signers(installed))
    }

    private fun decodeDigest(value: String): ByteArray {
        require(value.length == 64) { "Invalid update digest" }
        return ByteArray(32) { index ->
            val high = value[index * 2].digitToInt(16)
            val low = value[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }
    }

    private fun updatesDirectory(context: Context) = File(context.noBackupFilesDir, "updates")

    private fun artifactFile(context: Context, pending: PendingServerUpdate) = File(updatesDirectory(context), "${pending.versionCode}-${pending.artifact.sha256.take(16)}.apk")

    internal fun deleteInstalledArtifacts(context: Context) = synchronized(artifactLock) {
        val installed = installedVersion(context)
        updatesDirectory(context).listFiles()?.forEach { file ->
            val version = artifactName.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if (version != null && version <= installed && !file.delete()) Log.w("ServerUpdates", "Could not remove installed update artifact")
        }
    }

    private fun deleteOlderArtifacts(context: Context, keep: File) {
        val keepVersion = artifactName.matchEntire(keep.name)?.groupValues?.get(1)?.toLongOrNull() ?: return
        updatesDirectory(context).listFiles()?.forEach { file ->
            val version = artifactName.matchEntire(file.name)?.groupValues?.get(1)?.toLongOrNull()
            if (file != keep && version != null && version <= keepVersion && !file.delete()) Log.w("ServerUpdates", "Could not remove older update artifact")
        }
    }

    private fun <T> response(url: String, origin: String, read: (HttpURLConnection) -> T): T {
        require(ServerUpdatePolicy.origin(url) == origin) { "Invalid update origin" }
        ServerUpdatePolicy.checkInterrupted()
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("Update request failed")
            return read(connection)
        } finally {
            connection.disconnect()
        }
    }
}
