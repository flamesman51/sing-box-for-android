package io.nekohasekai.sfa.bg

enum class ServiceStartReason {
    User,
    AlwaysOn,
    StickyRestart,
    Boot,
}

internal enum class ServiceStopReason {
    User,
    Revoke,
    FatalConfiguration,
}

internal object ServiceStartPolicy {
    fun shouldRun(reason: ServiceStartReason, persistedShouldRun: Boolean): Boolean = when (reason) {
        ServiceStartReason.User, ServiceStartReason.AlwaysOn -> true
        ServiceStartReason.StickyRestart, ServiceStartReason.Boot -> persistedShouldRun
    }

    fun shouldRunAfterStop(reason: ServiceStopReason): Boolean = when (reason) {
        ServiceStopReason.User, ServiceStopReason.Revoke, ServiceStopReason.FatalConfiguration -> false
    }
}
