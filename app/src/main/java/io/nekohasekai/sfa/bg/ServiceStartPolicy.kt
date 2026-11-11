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

internal enum class ReceiverStartDecision {
    Start,
    NotRequested,
    UnreadCrashReports,
}

internal object ServiceStartPolicy {
    fun receiverDecision(
        packageReplaced: Boolean,
        persistedShouldRun: Boolean,
        hasUnreadCrashes: Boolean,
    ): ReceiverStartDecision =
        when {
            !persistedShouldRun -> ReceiverStartDecision.NotRequested
            !packageReplaced && hasUnreadCrashes -> ReceiverStartDecision.UnreadCrashReports
            else -> ReceiverStartDecision.Start
        }

    fun shouldRun(
        reason: ServiceStartReason,
        persistedShouldRun: Boolean,
    ): Boolean =
        when (reason) {
            ServiceStartReason.User, ServiceStartReason.AlwaysOn -> true
            ServiceStartReason.StickyRestart, ServiceStartReason.Boot -> persistedShouldRun
        }

    fun shouldRunAfterStop(reason: ServiceStopReason): Boolean =
        when (reason) {
            ServiceStopReason.User, ServiceStopReason.Revoke, ServiceStopReason.FatalConfiguration -> false
        }
}
