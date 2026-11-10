package io.nekohasekai.sfa.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsScrubberTest {
    @Test
    fun removesAddressesUrlsAndPrivatePathsBeforeTruncatingUtf8() {
        val result = DiagnosticsScrubber.text("dial https://node.example/credential?q=secret 192.0.2.10 [2001:db8::1]:443 node.example localhost /home/private/token")
        assertEquals("dial <url> <ip> [<ip>]:443 <host> <host> <path>", result)
        val limited = DiagnosticsScrubber.text("я".repeat(400), 511)
        assertTrue(limited.toByteArray(Charsets.UTF_8).size <= 511)
        assertFalse(limited.contains('\uFFFD'))
        assertEquals("2026-01-02T03:04:05.000Z", DiagnosticsScrubber.text("2026-01-02T03:04:05.000Z"))
    }

    @Test
    fun removesPunctuatedAndIpv4MappedAddresses() {
        for (address in listOf("192.0.2.10", "::ffff:192.0.2.10", "2001:db8::192.0.2.10", "2001:db8:1:2:3:4:192.0.2.10", "2001:db8:1:2:3:4:192.0.2.10%wlan0")) {
            for (suffix in listOf(".", ",", ";", ")", "]", ":443")) {
                val result = DiagnosticsScrubber.text("connect [$address$suffix")
                assertFalse(result, result.contains("192.0.2.10"))
                assertFalse(result, result.contains("ffff"))
                assertFalse(result, result.contains("2001:db8"))
                assertTrue(result, result.contains("<ip>"))
            }
        }
        assertEquals("connect <ip>.", DiagnosticsScrubber.text("connect 192.0.2.10."))
    }

    @Test
    fun preservesEveryTrailingPunctuationAfterRedactingEveryIpv6Form() {
        val addresses = listOf(
            "2001:db8:1:2:3:4:5:6",
            "2001:db8::1",
            "2001:db8::",
            "::",
            "::ffff:192.0.2.10",
            "2001:db8:1:2:3:4:192.0.2.10",
        )
        for (address in addresses) {
            for (suffix in listOf(".", ",", ":", ";", ")", "]", "\"", "'")) {
                assertEquals("$address$suffix", "connect <ip>$suffix", DiagnosticsScrubber.text("connect $address$suffix"))
            }
            assertEquals("connect <ip>", DiagnosticsScrubber.text("connect $address"))
        }
    }

    @Test
    fun keepsOnlyStrictJvmAndAlreadyScrubbedGoFrames() {
        val frames = DiagnosticsScrubber.stack(
            """
            java.lang.IllegalStateException: https://secret.example/token
                at io.example.Worker.run(Worker.kt:42)
            group.(*Smart).probe
            smart_probe.go:113
            debug.Stack
            stack.go:26
            goroutine 8 [running]:
            example.org/private/group.(*Smart).probe(0xc0000123, {0x123, 0x456})
                /home/private/project/group/smart_probe.go:113 +0x42
            log text containing a password
            """.trimIndent(),
        )
        assertEquals("at io.example.Worker.run(Worker.kt:42)\ngroup.(*Smart).probe\nsmart_probe.go:113\ndebug.Stack\nstack.go:26", frames)
        assertEquals(frames, DiagnosticsScrubber.stack(frames))
        assertFalse(frames.contains("private"))
        assertFalse(frames.contains("0xc"))
        assertFalse(frames.contains("secret"))
    }

    @Test
    fun rejectsExceptionTextDisguisedAsFramesAndNonIdentifierLocations() {
        val value = """
            dial private.example:443
            Unable to resolve host "myrouter"
            192.0.2.10.
            dial private.example:443
            at io.example.Worker.run(/private/Worker.kt:42)
            at io.example.Worker.run(Worker.kt:42) secret
            at io.example.Worker.run(192.0.2.10:443)
            group.(*Smart).probe
            /private/smart.go:42
            group.(*Smart).probe
            dial private.example:443
            smart.go:42
            at io.example.Worker.run(Worker.kt:42)
        """.trimIndent()
        assertEquals("at io.example.Worker.run(Worker.kt:42)", DiagnosticsScrubber.stack(value))
        assertEquals("", DiagnosticsScrubber.stack("group.run\n${"a".repeat(4097)}\nsmart.go:1"))
        assertEquals("", DiagnosticsScrubber.stack("group.run\nsmart.go:1", 8))
    }
}
