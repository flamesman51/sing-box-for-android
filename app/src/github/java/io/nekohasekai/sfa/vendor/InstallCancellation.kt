package io.nekohasekai.sfa.vendor

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

internal class InstallCancellation {
    private val state = AtomicInteger(PREPARING)

    fun cancel() {
        state.compareAndSet(PREPARING, CANCELLED)
    }

    fun check() {
        if (state.get() == CANCELLED || Thread.currentThread().isInterrupted) {
            throw CancellationException("Installation cancelled")
        }
    }

    fun commit(block: () -> Unit) {
        check()
        // Cancellation and commit have one winner; staging never holds a UI-thread lock.
        if (!state.compareAndSet(PREPARING, COMMITTING)) throw CancellationException("Installation cancelled")
        block()
    }

    private companion object {
        const val PREPARING = 0
        const val CANCELLED = 1
        const val COMMITTING = 2
    }
}
