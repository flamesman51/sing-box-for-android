package io.nekohasekai.sfa.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.concurrent.CancellationException

@Serializable
data class ServerArtifact(
    val abi: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

@Serializable
data class PendingServerUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String?,
    val artifact: ServerArtifact,
    val firstSeenAtMillis: Long,
)

@Serializable
data class ReadyServerUpdate(
    val versionCode: Int,
    val sha256: String,
    val path: String,
)

@Serializable
data class ServerUpdateSnapshot(
    val origin: String? = null,
    val manifestUrl: String? = null,
    val pending: PendingServerUpdate? = null,
    val ready: ReadyServerUpdate? = null,
    val lastReminderAtMillis: Long = 0,
)

internal object ServerUpdatePolicy {
    const val MAX_MANIFEST_BYTES = 64 * 1024L
    const val MAX_APK_BYTES = 256 * 1024 * 1024L
    private const val METERED_DELAY_MILLIS = 48 * 60 * 60 * 1000L
    private val digestPattern = Regex("[0-9a-fA-F]{64}")
    private val json = Json { ignoreUnknownKeys = true }

    data class Discovery(
        val origin: String,
        val manifestUrl: String,
    )

    fun origin(url: String): String? =
        try {
            val uri = URI(url)
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in -1..65535 || uri.port == 0 || '\\' in url) {
                null
            } else {
                URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port, null, null, null).toASCIIString()
            }
        } catch (_: Exception) {
            null
        }

    fun profileDiscoveryUrl(
        requestedUrl: String,
        finalUrl: String,
    ): String? = finalUrl.takeIf { origin(requestedUrl) != null && origin(finalUrl) != null }

    fun discover(
        pinnedOrigin: String?,
        profileUrl: String,
        header: String,
    ): Discovery? =
        try {
            val profileOrigin = origin(profileUrl)
            val path = URI(header.trim())
            if (profileOrigin == null || (pinnedOrigin != null && pinnedOrigin != profileOrigin) || path.isAbsolute || path.rawAuthority != null || !path.rawPath.orEmpty().startsWith("/") || path.rawPath.startsWith("//") || path.rawQuery != null || path.rawFragment != null || '\\' in path.path) {
                null
            } else {
                val resolved = URI(profileOrigin).resolve(path).toASCIIString()
                if (origin(resolved) == profileOrigin) Discovery(profileOrigin, resolved) else null
            }
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class Manifest(
        val version: Int,
        @SerialName("application_id") val applicationId: String,
        @SerialName("version_code") val versionCode: Int,
        @SerialName("version_name") val versionName: String,
        @SerialName("published_at") val publishedAt: Long,
        val notes: String? = null,
        val apks: List<Apk>,
    )

    @Serializable
    private data class Apk(
        val abi: String,
        val path: String,
        val size: Long,
        val sha256: String,
    )

    fun parseManifest(
        text: String,
        manifestUrl: String,
        pinnedOrigin: String,
        applicationId: String,
        supportedAbis: List<String>,
        nowMillis: Long,
    ): PendingServerUpdate {
        require(origin(manifestUrl) == pinnedOrigin) { "Invalid update origin" }
        val manifest = json.decodeFromString<Manifest>(text)
        require(manifest.version == 1 && manifest.applicationId == applicationId && manifest.versionCode > 0 && manifest.versionName.isNotBlank() && manifest.publishedAt > 0) { "Invalid update metadata" }
        val artifacts =
            manifest.apks.map {
                require(it.abi.isNotBlank() && it.size in 1L..MAX_APK_BYTES && digestPattern.matches(it.sha256)) { "Invalid update artifact" }
                ServerArtifact(it.abi, artifactUrl(manifestUrl, pinnedOrigin, it.path), it.size, it.sha256.lowercase())
            }
        val artifact = artifacts.firstOrNull { it.abi in supportedAbis } ?: artifacts.firstOrNull { it.abi == "universal" }
        requireNotNull(artifact) { "No compatible update artifact" }
        return PendingServerUpdate(manifest.versionCode, manifest.versionName, manifest.notes, artifact, nowMillis)
    }

    private fun artifactUrl(
        manifestUrl: String,
        pinnedOrigin: String,
        path: String,
    ): String {
        val relative = URI(path)
        require(!relative.isAbsolute && relative.rawAuthority == null && relative.rawQuery == null && relative.rawFragment == null && !relative.path.isNullOrBlank() && !relative.path.startsWith("/") && '\\' !in relative.path && relative.path.split('/').none { it == "." || it == ".." }) { "Invalid artifact path" }
        val url = URI(manifestUrl).resolve(relative).toASCIIString()
        require(origin(url) == pinnedOrigin) { "Invalid artifact origin" }
        return url
    }

    fun readManifest(input: InputStream): String {
        val output = ByteArrayOutputStream()
        copyBounded(input, output, MAX_MANIFEST_BYTES)
        return output.toString(Charsets.UTF_8.name())
    }

    /** Reads only one byte past the limit, without buffering untrusted input ahead. */
    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        checkCancelled: () -> Unit = ::checkInterrupted,
    ): Long {
        require(limit >= 0 && limit < Long.MAX_VALUE)
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val remaining = limit - total
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining.coerceAtLeast(1)).toInt())
            if (count < 0) return total
            if (count == 0) continue
            total += count
            if (total > limit) throw IOException("Update response exceeds size limit")
            output.write(buffer, 0, count)
        }
    }

    fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Update work cancelled")
    }

    fun forget(state: ServerUpdateSnapshot): ServerUpdateSnapshot = state.copy(origin = null, manifestUrl = null)

    fun reconcile(
        state: ServerUpdateSnapshot,
        installedVersion: Long,
    ): ServerUpdateSnapshot =
        if (state.pending != null && installedVersion >= state.pending.versionCode) {
            state.copy(pending = null, ready = null, lastReminderAtMillis = 0)
        } else if (state.ready != null && installedVersion >= state.ready.versionCode) {
            state.copy(ready = null)
        } else {
            state
        }

    fun accept(
        state: ServerUpdateSnapshot,
        candidate: PendingServerUpdate?,
        installedVersion: Long,
    ): ServerUpdateSnapshot {
        val current = reconcile(state, installedVersion)
        if (candidate == null || candidate.versionCode <= installedVersion) return current
        val previous = current.pending
        if (previous != null) {
            if (candidate.versionCode < previous.versionCode) return current
            if (candidate.versionCode == previous.versionCode) {
                // A published version is immutable; URL rotation must not replace its verified identity.
                if (candidate.artifact.sha256 != previous.artifact.sha256 || candidate.artifact.size != previous.artifact.size || candidate.artifact.abi != previous.artifact.abi) return current
                return current.copy(pending = candidate.copy(firstSeenAtMillis = previous.firstSeenAtMillis))
            }
        }
        return current.copy(pending = candidate, ready = null, lastReminderAtMillis = 0)
    }

    fun fallbackDelayMillis(
        firstSeenAtMillis: Long,
        nowMillis: Long,
    ): Long {
        val deadline = firstSeenAtMillis.coerceAtMost(Long.MAX_VALUE - METERED_DELAY_MILLIS) + METERED_DELAY_MILLIS
        return (deadline - nowMillis.coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun requiresUnmetered(
        firstSeenAtMillis: Long,
        nowMillis: Long,
        downloadNow: Boolean = false,
    ): Boolean = !downloadNow && fallbackDelayMillis(firstSeenAtMillis, nowMillis) > 0

    fun matchesPackage(
        packageName: String,
        versionCode: Long,
        signers: Set<String>,
        applicationId: String,
        expectedVersion: Int,
        installedVersion: Long,
        installedSigners: Set<String>,
    ): Boolean = packageName == applicationId && versionCode == expectedVersion.toLong() && versionCode > installedVersion && signers.isNotEmpty() && signers == installedSigners
}
