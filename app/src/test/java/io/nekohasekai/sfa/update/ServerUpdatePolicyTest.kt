package io.nekohasekai.sfa.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

class ServerUpdatePolicyTest {
    private val origin = "https://updates.example"
    private val manifestUrl = "$origin/secret/app-update.json"
    private val sha256 = "a".repeat(64)

    private fun manifest(apks: String = """{"abi":"arm64-v8a","path":"app.apk","size":3,"sha256":"$sha256"}"""): String = """{"version":1,"application_id":"example.app","version_code":760,"version_name":"1.2","published_at":1794000000,"apks":[$apks]}"""

    private fun candidate(
        version: Int = 760,
        firstSeen: Long = 1_000,
    ): PendingServerUpdate =
        PendingServerUpdate(
            version,
            "1.2",
            null,
            ServerArtifact("arm64-v8a", "$origin/secret/app.apk", 3, sha256),
            firstSeen,
        )

    @Test
    fun profileDiscoveryRequiresHttpsBeforeAndAfterRedirect() {
        assertEquals("$origin/final", ServerUpdatePolicy.profileDiscoveryUrl("$origin/requested", "$origin/final"))
        assertNull(ServerUpdatePolicy.profileDiscoveryUrl("http://localhost/profile", "$origin/final"))
        assertNull(ServerUpdatePolicy.profileDiscoveryUrl("$origin/requested", "http://updates.example/final"))
        assertEquals(
            "$origin/new/profile",
            ServerUpdatePolicy.profileDiscoveryUrl("https://profiles.example/old/profile", "$origin/new/profile"),
        )
    }

    @Test
    fun discoveryPinsFirstHttpsOriginAndAllowsOnlySameOriginRotation() {
        val first = ServerUpdatePolicy.discover(null, "https://UPDATES.example:443/profile", "/secret/app-update.json")
        assertEquals(origin, first?.origin)
        assertEquals(manifestUrl, first?.manifestUrl)
        assertEquals("$origin/new/update.json", ServerUpdatePolicy.discover(origin, "$origin/profile", "/new/update.json")?.manifestUrl)
        assertNull(ServerUpdatePolicy.discover(origin, "https://hostile.example/profile", "/update.json"))
        assertNull(ServerUpdatePolicy.discover(origin, "https://updates.example:444/profile", "/update.json"))
    }

    @Test
    fun discoveryRejectsEveryHttpHostAndNonPathOrAmbiguousHeaders() {
        for (host in listOf("example.com", "localhost", "127.0.0.1", "[::1]", "100.64.0.1")) {
            assertNull(ServerUpdatePolicy.discover(null, "http://$host/profile", "/update.json"))
        }
        for (header in listOf("update.json", "https://hostile.example/update.json", "//hostile.example/update.json", "/update.json#fragment", "/update.json?query", "/\\hostile.example/update.json")) {
            assertNull(header, ServerUpdatePolicy.discover(null, "$origin/profile", header))
        }
        assertNull(ServerUpdatePolicy.discover(null, "https://user:password@updates.example/profile", "/update.json"))
    }

    @Test
    fun manifestChoosesFirstListedCompatibleAbiAndExplicitUniversalFallback() {
        val arm = """{"abi":"arm64-v8a","path":"arm.apk","size":3,"sha256":"$sha256"}"""
        val x86 = """{"abi":"x86_64","path":"x86.apk","size":3,"sha256":"$sha256"}"""
        val universal = """{"abi":"universal","path":"all.apk","size":3,"sha256":"$sha256"}"""
        val result = ServerUpdatePolicy.parseManifest(manifest("$arm,$x86"), manifestUrl, origin, "example.app", listOf("x86_64", "arm64-v8a"), 1_000)
        assertEquals("arm64-v8a", result.artifact.abi)
        assertEquals("$origin/secret/arm.apk", result.artifact.url)
        assertEquals("universal", ServerUpdatePolicy.parseManifest(manifest(universal), manifestUrl, origin, "example.app", listOf("x86"), 1_000).artifact.abi)
        assertThrows(IllegalArgumentException::class.java) {
            ServerUpdatePolicy.parseManifest(manifest(arm), manifestUrl, origin, "example.app", listOf("x86"), 1_000)
        }
    }

    @Test
    fun manifestRejectsInvalidMetadataSizesDigestsAndEscapingArtifactPaths() {
        val valid = manifest()
        val invalid =
            listOf(
                valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("example.app", "other.app"),
                valid.replace("\"version_code\":760", "\"version_code\":0"),
                valid.replace("\"version_code\":760", "\"version_code\":2147483648"),
                valid.replace("\"version_name\":\"1.2\"", "\"version_name\":\"\""),
                valid.replace("\"size\":3", "\"size\":0"),
                valid.replace("\"size\":3", "\"size\":268435457"),
                valid.replace(sha256, "g".repeat(64)),
                valid.replace(sha256, "a".repeat(63)),
            ) +
                listOf("../app.apk", "%2e%2e/app.apk", "https://hostile.example/app.apk", "//hostile.example/app.apk", "/app.apk", "app.apk?x", "app.apk#x", "http://updates.example/app.apk").map {
                    valid.replace("app.apk", it)
                }
        for (text in invalid) {
            assertThrows(Exception::class.java) {
                ServerUpdatePolicy.parseManifest(text, manifestUrl, origin, "example.app", listOf("arm64-v8a"), 1_000)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerUpdatePolicy.parseManifest(valid, manifestUrl, "https://other.example", "example.app", listOf("arm64-v8a"), 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ServerUpdatePolicy.parseManifest(valid, manifestUrl.replace("https:", "http:"), origin, "example.app", listOf("arm64-v8a"), 1_000)
        }
        assertNotNull(ServerUpdatePolicy.parseManifest(valid.replace("\"size\":3", "\"size\":268435456"), manifestUrl, origin, "example.app", listOf("arm64-v8a"), 1_000))
    }

    @Test
    fun boundedReadStopsAtExactlyLimitPlusOneWithoutWritingExtraByte() {
        val input = ByteArrayInputStream(ByteArray(20))
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { ServerUpdatePolicy.copyBounded(input, output, 3) }
        assertEquals(16, input.available())
        assertEquals(3, output.size())
        assertEquals(3L, ServerUpdatePolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2, 3)), ByteArrayOutputStream(), 3))
        assertEquals(2L, ServerUpdatePolicy.copyBounded(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3))
        val manifestInput = ByteArrayInputStream(ByteArray(65_540))
        assertThrows(IOException::class.java) { ServerUpdatePolicy.readManifest(manifestInput) }
        assertEquals(3, manifestInput.available())
    }

    @Test
    fun boundedCopyHonorsCancellationBeforeReading() {
        val input = ByteArrayInputStream(ByteArray(20))
        assertThrows(CancellationException::class.java) {
            ServerUpdatePolicy.copyBounded(input, ByteArrayOutputStream(), 3) { throw CancellationException() }
        }
        assertEquals(20, input.available())
    }

    @Test
    fun staleAndInvalidResultsNeverClearPendingOrResetFirstSeen() {
        val pending = candidate()
        val ready = ReadyServerUpdate(760, sha256, "/private/760.apk")
        val state = ServerUpdateSnapshot(origin, manifestUrl, pending, ready)
        assertEquals(state, ServerUpdatePolicy.accept(state, null, 700))
        assertEquals(state, ServerUpdatePolicy.accept(state, candidate(759), 700))
        val refreshed = ServerUpdatePolicy.accept(state, pending.copy(firstSeenAtMillis = 999_999), 700)
        assertEquals(1_000L, refreshed.pending?.firstSeenAtMillis)
        assertEquals(ready, refreshed.ready)
        assertEquals(state, ServerUpdatePolicy.reconcile(state, 759))
        val installed = ServerUpdatePolicy.reconcile(state, 760)
        assertNull(installed.pending)
        assertNull(installed.ready)
        assertEquals(origin, installed.origin)
        assertEquals(manifestUrl, installed.manifestUrl)
        val forgotten = ServerUpdatePolicy.forget(state)
        assertNull(forgotten.origin)
        assertNull(forgotten.manifestUrl)
        assertEquals(pending, forgotten.pending)
        assertEquals(ready, forgotten.ready)
    }

    @Test
    fun newerCandidateReplacesIdentityButSameVersionCannotSwapArtifact() {
        val state = ServerUpdateSnapshot(pending = candidate(), ready = ReadyServerUpdate(760, sha256, "/private/760.apk"))
        val changed = candidate().copy(artifact = candidate().artifact.copy(sha256 = "b".repeat(64)))
        assertEquals(state, ServerUpdatePolicy.accept(state, changed, 700))
        val next = ServerUpdatePolicy.accept(state, candidate(761, 2_000), 700)
        assertEquals(761, next.pending?.versionCode)
        assertEquals(2_000L, next.pending?.firstSeenAtMillis)
        assertNull(next.ready)
    }

    @Test
    fun meteredFallbackStartsAtFortyEightHoursAndManualDownloadStartsNow() {
        val firstSeen = 1_000L
        val deadline = 172_801_000L
        assertTrue(ServerUpdatePolicy.requiresUnmetered(firstSeen, deadline - 1))
        assertFalse(ServerUpdatePolicy.requiresUnmetered(firstSeen, deadline))
        assertFalse(ServerUpdatePolicy.requiresUnmetered(firstSeen, deadline + 1))
        assertFalse(ServerUpdatePolicy.requiresUnmetered(firstSeen, firstSeen, downloadNow = true))
        assertEquals(172_800_000L, ServerUpdatePolicy.fallbackDelayMillis(firstSeen, firstSeen))
        assertEquals(1L, ServerUpdatePolicy.fallbackDelayMillis(firstSeen, deadline - 1))
        assertEquals(0L, ServerUpdatePolicy.fallbackDelayMillis(firstSeen, deadline))
    }

    @Test
    fun apkIdentityRequiresExactPackageVersionAndCurrentSignerSet() {
        assertTrue(ServerUpdatePolicy.matchesPackage("example.app", 760, setOf("a", "b"), "example.app", 760, 759, setOf("b", "a")))
        assertFalse(ServerUpdatePolicy.matchesPackage("other.app", 760, setOf("a"), "example.app", 760, 759, setOf("a")))
        assertFalse(ServerUpdatePolicy.matchesPackage("example.app", 761, setOf("a"), "example.app", 760, 759, setOf("a")))
        assertFalse(ServerUpdatePolicy.matchesPackage("example.app", 760, setOf("a"), "example.app", 760, 760, setOf("a")))
        assertFalse(ServerUpdatePolicy.matchesPackage("example.app", 760, setOf("a", "old"), "example.app", 760, 759, setOf("a")))
        assertFalse(ServerUpdatePolicy.matchesPackage("example.app", 760, emptySet(), "example.app", 760, 759, emptySet()))
    }
}
