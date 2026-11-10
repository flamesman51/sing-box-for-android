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
    fun keepsOnlyJvmAndGoFunctionsAndBasenameLocations() {
        val frames = DiagnosticsScrubber.stack(
            """
            java.lang.IllegalStateException: https://secret.example/token
                at io.example.Worker.run(Worker.kt:42)
            goroutine 8 [running]:
            example.org/private/group.(*Smart).probe(0xc0000123, {0x123, 0x456})
                /home/private/project/group/smart_probe.go:113 +0x42
            log text containing a password
            """.trimIndent(),
        )
        assertEquals("io.example.Worker.run Worker.kt:42\ngroup.(*Smart).probe smart_probe.go:113", frames)
        assertEquals(frames, DiagnosticsScrubber.stack(frames))
        assertFalse(frames.contains("private"))
        assertFalse(frames.contains("0xc"))
        assertFalse(frames.contains("secret"))
    }
}
