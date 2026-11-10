package io.nekohasekai.sfa.diagnostics

import java.io.Reader

object DiagnosticsScrubber {
    private val url = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\\\"']+")
    private val ipv4 = Regex("(?<![\\w.])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![\\w.])")
    private val ipv6 = Regex("(?i)(?<![a-z0-9])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:.]*(?:%[a-z0-9_.-]+)?")
    private val host = Regex("(?i)(?<![\\w])(?:[\\p{L}\\p{N}_-]+\\.)+[\\p{L}][\\p{L}\\p{N}_-]*(?![\\w])|\\blocalhost\\b")
    private val path = Regex("(?<![\\w])(?:[A-Za-z]:[\\\\/]|/)[^\\s<>\\\"']+")
    private val namedHost = Regex("(?i)(\\b(?:host|hostname|server|address)\\s*[=:]\\s*)[^\\s,;<>]+")
    private val jvm = Regex("^at\\s+([A-Za-z0-9_.$]+)\\(([^():]+):([0-9]+)\\)$")
    private val location = Regex("^(.+\\.(?:go|kt|java|c|h|s|cpp)):(\\d+)(?:\\s.*)?$")
    private val function = Regex("[A-Za-z_$][A-Za-z0-9_.$()*/<>-]*")
    private val basename = Regex("[A-Za-z0-9_.-]+")
    private val normalized = Regex("^([A-Za-z_$][A-Za-z0-9_.$()*<>-]*) ([A-Za-z0-9_.-]+):([0-9]+)$")

    fun text(value: String, maxBytes: Int = 512): String {
        var result = url.replace(value.take(64 * 1024), "<url>")
        result = ipv6.replace(result) {
            val address = it.value.substringBefore('%')
            if ("::" in address || address.count { character -> character == ':' } == 7) "<ip>" else it.value
        }
        result = ipv4.replace(result, "<ip>")
        result = host.replace(result, "<host>")
        result = namedHost.replace(result) { "${it.groupValues[1]}<host>" }
        result = path.replace(result, "<path>")
        return utf8(result, maxBytes)
    }

    fun utf8(value: String, maxBytes: Int): String {
        require(maxBytes >= 0)
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun stack(value: String, maxBytes: Int = 16 * 1024): String = stack(value.reader(), maxBytes)

    fun stack(reader: Reader, maxBytes: Int = 16 * 1024): String {
        val frames = StringBuilder()
        var pending: String? = null
        var readChars = 0
        var frameBytes = 0
        val line = StringBuilder()
        fun accept(raw: String) {
            val trimmed = raw.trim()
            val javaFrame = jvm.matchEntire(trimmed)
            val normalizedFrame = normalized.matchEntire(trimmed)
            var frame: String? = null
            if (normalizedFrame != null) {
                frame = normalizedFrame.value
                pending = null
            } else if (javaFrame != null) {
                val file = javaFrame.groupValues[2].substringAfterLast('/').substringAfterLast('\\')
                if (basename.matches(file)) frame = "${javaFrame.groupValues[1]} $file:${javaFrame.groupValues[3]}"
                pending = null
            } else {
                val fileFrame = location.matchEntire(trimmed)
                if (fileFrame != null) {
                    val file = fileFrame.groupValues[1].substringAfterLast('/').substringAfterLast('\\')
                    if (pending != null && basename.matches(file)) frame = "$pending $file:${fileFrame.groupValues[2]}"
                    pending = null
                } else {
                    val opening = trimmed.lastIndexOf('(')
                    val symbol = if (opening > 0 && trimmed.endsWith(')')) trimmed.substring(0, opening).substringAfterLast('/') else ""
                    pending = symbol.takeIf { function.matches(it) }
                }
            }
            if (frame != null) {
                val bytes = frame.toByteArray(Charsets.UTF_8).size + if (frames.isEmpty()) 0 else 1
                if (frameBytes + bytes <= maxBytes) {
                    if (frames.isNotEmpty()) frames.append('\n')
                    frames.append(frame)
                    frameBytes += bytes
                }
            }
        }
        // Scan a bounded prefix for frames; never retain or return raw log lines.
        while (readChars < 256 * 1024 && frameBytes < maxBytes) {
            val next = reader.read()
            if (next == -1) break
            readChars++
            if (next == '\n'.code) {
                accept(line.toString())
                line.setLength(0)
            } else if (line.length < 4096) {
                line.append(next.toChar())
            }
        }
        if (line.isNotEmpty()) accept(line.toString())
        return frames.toString()
    }
}
