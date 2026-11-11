package io.nekohasekai.sfa.diagnostics

import io.nekohasekai.sfa.Application
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI

object ReportCapabilities {
    private val store by lazy {
        Store(File(Application.application.noBackupFilesDir, "report_capabilities"))
    }

    fun destination(profileId: Long, remoteURL: String): String? = store.destination(profileId, remoteURL)

    internal fun accepted(profileId: Long, remoteURL: String, finalURL: String, header: String?) = store.accepted(profileId, remoteURL, finalURL, header)

    internal fun withdrawIfUnsupported(profileId: Long, remoteURL: String, finalURL: String, header: String?) = store.withdrawIfUnsupported(profileId, remoteURL, finalURL, header)

    internal fun profileChanged(profileId: Long, remoteURL: String?) = store.profileChanged(profileId, remoteURL)

    internal fun clear(profileId: Long) = store.clear(profileId)

    internal class Store(private val directory: File) {
        @Synchronized
        fun destination(profileId: Long, remoteURL: String): String? {
            if (profileId <= 0) return null
            val saved = read(profileId) ?: return null
            return saved.second.takeIf { saved.first == remoteURL && isHttps(it) }
        }

        @Synchronized
        fun withdrawIfUnsupported(profileId: Long, remoteURL: String, finalURL: String, header: String?) {
            if ((header != "1" || !isHttps(finalURL)) && read(profileId)?.first == remoteURL) clear(profileId)
        }

        @Synchronized
        fun accepted(profileId: Long, remoteURL: String, finalURL: String, header: String?) {
            require(profileId > 0)
            if (header != "1" || !isHttps(finalURL)) {
                clear(profileId)
                return
            }
            if (read(profileId) == (remoteURL to finalURL)) return
            val temporary = File(directory, "$profileId.new")
            try {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException()
                FileOutputStream(temporary).use { output ->
                    val data = DataOutputStream(output)
                    data.writeUTF(remoteURL)
                    data.writeUTF(finalURL)
                    data.flush()
                    output.fd.sync()
                }
                if (!temporary.renameTo(File(directory, profileId.toString()))) throw IOException()
            } catch (_: Exception) {
                throw IOException("Cannot save report capability")
            } finally {
                temporary.delete()
            }
        }

        @Synchronized
        fun profileChanged(profileId: Long, remoteURL: String?) {
            if (remoteURL == null || read(profileId)?.first != remoteURL) clear(profileId)
        }

        @Synchronized
        fun clear(profileId: Long) {
            val file = File(directory, profileId.toString())
            if (file.exists() && !file.delete()) throw IOException("Cannot clear report capability")
        }

        private fun read(profileId: Long): Pair<String, String>? = try {
            DataInputStream(File(directory, profileId.toString()).inputStream()).use {
                it.readUTF() to it.readUTF()
            }
        } catch (_: Exception) {
            null
        }

        private fun isHttps(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrEmpty() && uri.rawUserInfo == null && uri.rawFragment == null
        } catch (_: Exception) {
            false
        }
    }
}
