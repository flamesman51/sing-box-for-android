package io.nekohasekai.sfa.compose.screen.smart

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal const val SMART_IMPORT_LIMIT = 2 * 1024 * 1024

internal fun readSmartImport(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, SMART_IMPORT_LIMIT + 1 - output.size()))
        if (count < 0) return output.toString(Charsets.UTF_8.name())
        output.write(buffer, 0, count)
        if (output.size() > SMART_IMPORT_LIMIT) throw IOException("too_large")
    }
}
