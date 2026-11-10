package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.sfa.constant.Status
import java.security.MessageDigest

internal enum class SmartPolicyApplyState {
    Applied,
    Pending,
    NextStart,
    Unknown,
    NotUsed,
}

internal fun smartPolicyHash(value: String): String = if (value.isEmpty()) {
    ""
} else {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

internal fun smartPolicyApplyState(
    serviceStatus: Status,
    storedPolicy: String,
    snapshot: List<SmartStatus>?,
): SmartPolicyApplyState = when {
    serviceStatus == Status.Stopped -> SmartPolicyApplyState.NextStart
    serviceStatus != Status.Started || snapshot == null -> SmartPolicyApplyState.Unknown
    snapshot.isEmpty() -> SmartPolicyApplyState.NotUsed
    else -> {
        val expected = smartPolicyHash(storedPolicy)
        if (snapshot.all { (it.preference?.overrideHash ?: "") == expected }) {
            SmartPolicyApplyState.Applied
        } else {
            SmartPolicyApplyState.Pending
        }
    }
}

internal class SmartPolicyReconcileTracker {
    private var instance: Long? = null
    private val determinedPolicies = mutableSetOf<String>()

    fun markSaved(serviceInstance: Long, storedPolicy: String) {
        useInstance(serviceInstance)
        determinedPolicies.add(storedPolicy)
    }

    fun shouldNotify(
        serviceInstance: Long,
        serviceStatus: Status,
        storedPolicy: String,
        snapshot: List<SmartStatus>?,
    ): Boolean {
        useInstance(serviceInstance)
        if (serviceStatus != Status.Started || storedPolicy in determinedPolicies) return false
        val state = smartPolicyApplyState(serviceStatus, storedPolicy, snapshot)
        if (state == SmartPolicyApplyState.Unknown) return false
        determinedPolicies.add(storedPolicy)
        return state == SmartPolicyApplyState.Pending
    }

    private fun useInstance(serviceInstance: Long) {
        if (instance != serviceInstance) {
            instance = serviceInstance
            determinedPolicies.clear()
        }
    }
}
