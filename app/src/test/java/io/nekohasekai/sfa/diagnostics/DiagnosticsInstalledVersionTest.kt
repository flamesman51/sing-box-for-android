package io.nekohasekai.sfa.diagnostics

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiagnosticsInstalledVersionTest {
    @Test
    fun storesVersionWithCommitRatherThanLifecycleQueuedApply() {
        val commits = AtomicInteger()
        val applies = AtomicInteger()
        val versions = DiagnosticsInstalledVersion()
        versions.load({ preferences(12, commits, applies) }, 13)
        assertEquals(1, commits.get())
        assertEquals(0, applies.get())
    }

    @Test
    fun updateEventWaitsForVersionReadWithoutBlockingRecovery() =
        runBlocking {
            val versions = DiagnosticsInstalledVersion()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val recorded = CountDownLatch(1)
            var detail: Map<String, Any?>? = null
            try {
                val event =
                    versions.recordUpdate(scope, 13) {
                        detail = it
                        recorded.countDown()
                    }
                assertFalse("Update event raced ahead of installed-version read", event.isCompleted)
                versions.load({ preferences(12, AtomicInteger(), AtomicInteger()) }, 13)
                assertTrue(recorded.await(5, TimeUnit.SECONDS))
                event.join()
                assertEquals(mapOf("from" to 12, "to" to 13), detail)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun failedPreferenceReadStillReleasesDiagnosticsContinuation() =
        runBlocking {
            val versions = DiagnosticsInstalledVersion()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var detail: Map<String, Any?>? = null
            try {
                val event = versions.recordUpdate(scope, 13) { detail = it }
                runCatching { versions.load({ error("Preferences unavailable") }, 13) }
                event.join()
                assertEquals(mapOf("from" to null, "to" to 13), detail)
            } finally {
                scope.cancel()
            }
        }

    private fun preferences(
        previous: Int,
        commits: AtomicInteger,
        applies: AtomicInteger,
    ): SharedPreferences {
        val editor =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, _ ->
                when (method.name) {
                    "putInt" -> {
                        proxy
                    }
                    "commit" -> {
                        commits.incrementAndGet()
                        true
                    }
                    "apply" -> {
                        applies.incrementAndGet()
                        null
                    }
                    else -> {
                        error("Unexpected editor method: ${method.name}")
                    }
                }
            } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getInt" -> previous
                "edit" -> editor
                else -> error("Unexpected preferences method: ${method.name}")
            }
        } as SharedPreferences
    }
}
