package io.nekohasekai.sfa.vendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class InstallCancellationTest {
    @Test
    fun cancelBeforeCommitPreventsInstallation() {
        val cancellation = InstallCancellation()
        var committed = false

        cancellation.cancel()

        assertThrows(CancellationException::class.java) { cancellation.check() }
        assertThrows(CancellationException::class.java) { cancellation.commit { committed = true } }
        assertFalse(committed)
    }

    @Test
    fun cancelDuringStagingDoesNotWaitForIoAndPreventsItsLaterCommit() {
        val cancellation = InstallCancellation()
        val staging = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val task = FutureTask {
            staging.countDown()
            check(resume.await(10, TimeUnit.SECONDS))
            try {
                cancellation.commit { committed.set(true) }
                false
            } catch (_: CancellationException) {
                true
            }
        }
        val worker = Thread(task).apply { start() }
        try {
            assertTrue(staging.await(10, TimeUnit.SECONDS))
            cancellation.cancel()
            resume.countDown()
            assertTrue(task.get(10, TimeUnit.SECONDS))
            assertFalse(committed.get())
        } finally {
            resume.countDown()
            worker.join(10_000)
        }
    }

    @Test
    fun commitThatAlreadyStartedCannotBeRepeatedOrRetroactivelyCancelled() {
        val cancellation = InstallCancellation()
        var commits = 0

        cancellation.commit {
            cancellation.cancel()
            commits++
        }

        assertThrows(CancellationException::class.java) { cancellation.commit { commits++ } }
        assertEquals(1, commits)
    }
}
