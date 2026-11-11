package io.nekohasekai.sfa.database

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal class RemoteProfileValidator(private val directory: File, private val check: (String) -> Unit) {
    @Synchronized
    fun validate(profileId: Long, stored: String?, fetched: String, versionCode: Int) {
        val digest = MessageDigest.getInstance("SHA-256").digest(fetched.toByteArray(Charsets.UTF_8))
        val state = buildString {
            append(versionCode).append(':')
            for (byte in digest) {
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4])
                append("0123456789abcdef"[value and 0xf])
            }
        }
        val file = File(directory, profileId.toString())
        val previous = try {
            file.readText()
        } catch (_: IOException) {
            null
        }
        if (stored == fetched && previous == state) return
        check(fetched)
        if (previous == state) return
        val temporary = File(directory, "$profileId.new")
        try {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create profile validation directory")
            FileOutputStream(temporary).use { output ->
                output.write(state.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Cannot save profile validation")
        } finally {
            temporary.delete()
        }
    }

    @Synchronized
    fun clear(profileId: Long) {
        val file = File(directory, profileId.toString())
        if (file.exists() && !file.delete()) throw IOException("Cannot clear profile validation")
    }
}
