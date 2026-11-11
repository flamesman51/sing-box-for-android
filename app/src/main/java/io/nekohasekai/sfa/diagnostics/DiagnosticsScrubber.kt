package io.nekohasekai.sfa.diagnostics

import java.io.Reader
import java.net.URI
import java.net.URISyntaxException

object DiagnosticsScrubber {
    private val url = Regex("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s<>\\\"']+")
    private val ipv4 = Regex("(?<![\\w])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![\\w])")
    private val ipv6 = Regex("(?i)(?<![a-z0-9])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:.]*(?:%[a-z0-9_.-]+)?")
    private val host = Regex("(?i)(?<![\\w])(?:[\\p{L}\\p{N}_-]+\\.)+[\\p{L}][\\p{L}\\p{N}_-]*(?![\\w])|\\blocalhost\\b")
    private val path = Regex("(?<![\\w])(?:[A-Za-z]:[\\\\/]|/)[^\\s<>\\\"']+")
    private val namedHost = Regex("(?i)(\\b(?:host|hostname|server|address)\\s*[=:]\\s*)[^\\s,;<>]+")
    private const val JVM_IDENTIFIER = "[A-Za-z_$][A-Za-z0-9_$]*"
    private const val GO_IDENTIFIER = "[A-Za-z_][A-Za-z0-9_]*"
    private val jvm = Regex("at (?:$JVM_IDENTIFIER\\.)+$JVM_IDENTIFIER\\($JVM_IDENTIFIER\\.(?:kt|java):[0-9]{1,10}\\)")
    private val goFunction = Regex("$GO_IDENTIFIER\\.(?:\\(\\*?$GO_IDENTIFIER\\)\\.)?$GO_IDENTIFIER(?:\\.$GO_IDENTIFIER)*")
    private val goLocation = Regex("$GO_IDENTIFIER\\.go:[0-9]{1,10}")

    fun text(
        value: String,
        maxBytes: Int = 512,
    ): String {
        var result = url.replace(value.take(64 * 1024), "<url>")
        result =
            ipv6.replace(result) { match ->
                val candidate = match.value
                val trimmed = candidate.trimEnd('.', ',', ':', ';', ')', ']', '"', '\'')
                var end = trimmed.length
                while (true) {
                    if (validIpv6(candidate.substring(0, end))) return@replace "<ip>" + candidate.substring(end)
                    // A terminal :: belongs to the address, not its punctuation suffix.
                    if (end == candidate.length || candidate[end] != ':') break
                    end++
                }
                val port = trimmed.lastIndexOf(':')
                if (port > 0 && trimmed.substring(port + 1).all(Char::isDigit) && validIpv6(trimmed.substring(0, port))) {
                    "<ip>" + candidate.substring(port)
                } else {
                    candidate
                }
            }
        result = ipv4.replace(result, "<ip>")
        result = host.replace(result, "<host>")
        result = namedHost.replace(result) { "${it.groupValues[1]}<host>" }
        result = path.replace(result, "<path>")
        return utf8(result, maxBytes)
    }

    private fun validIpv6(value: String): Boolean =
        try {
            // URI parses bracketed numeric literals without DNS or network access.
            URI("http://[${value.substringBefore('%')}]/")
            true
        } catch (_: URISyntaxException) {
            false
        }

    fun utf8(
        value: String,
        maxBytes: Int,
    ): String {
        require(maxBytes >= 0)
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return value
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    fun stack(
        value: String,
        maxBytes: Int = 16 * 1024,
    ): String = stack(value.reader(), maxBytes)

    fun stack(
        reader: Reader,
        maxBytes: Int = 16 * 1024,
    ): String {
        val frames = StringBuilder()
        var pending: String? = null
        var readChars = 0
        var frameBytes = 0
        val line = StringBuilder()
        var overflow = false
        fun accept(raw: String) {
            val trimmed = raw.trim()
            val frame =
                when {
                    jvm.matches(trimmed) -> trimmed
                    pending != null && goLocation.matches(trimmed) -> "$pending\n$trimmed"
                    else -> null
                }
            pending = if (goFunction.matches(trimmed)) trimmed else null
            if (frame != null) {
                val bytes = frame.toByteArray(Charsets.UTF_8).size + if (frames.isEmpty()) 0 else 1
                if (frameBytes + bytes <= maxBytes) {
                    if (frames.isNotEmpty()) frames.append('\n')
                    frames.append(frame)
                    frameBytes += bytes
                }
            }
        }
        // Only complete validated frames survive; raw exception and log lines never do.
        while (readChars < 256 * 1024 && frameBytes < maxBytes) {
            val next = reader.read()
            if (next == -1) {
                if (!overflow && line.isNotEmpty()) accept(line.toString())
                break
            }
            readChars++
            if (next == '\n'.code) {
                if (overflow) pending = null else accept(line.toString())
                line.setLength(0)
                overflow = false
            } else if (line.length < 4096) {
                line.append(next.toChar())
            } else {
                overflow = true
            }
        }
        return frames.toString()
    }
}
