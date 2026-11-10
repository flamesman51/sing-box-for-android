package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceStartPolicyTest {
    @Test
    fun packageReplacementRestoresRequestedVpnDespiteUnreadCrashes() {
        assertEquals(ReceiverStartDecision.Start, ServiceStartPolicy.receiverDecision(true, true, true))
        assertEquals(ReceiverStartDecision.UnreadCrashReports, ServiceStartPolicy.receiverDecision(false, true, true))
        assertEquals(ReceiverStartDecision.Start, ServiceStartPolicy.receiverDecision(false, true, false))
        assertEquals(ReceiverStartDecision.NotRequested, ServiceStartPolicy.receiverDecision(true, false, true))
    }

    @Test
    fun startReasonDecisionTable() {
        val cases =
            listOf(
                Triple(ServiceStartReason.User, false, true),
                Triple(ServiceStartReason.User, true, true),
                Triple(ServiceStartReason.AlwaysOn, false, true),
                Triple(ServiceStartReason.AlwaysOn, true, true),
                Triple(ServiceStartReason.StickyRestart, false, false),
                Triple(ServiceStartReason.StickyRestart, true, true),
                Triple(ServiceStartReason.Boot, false, false),
                Triple(ServiceStartReason.Boot, true, true),
            )

        for ((reason, persistedShouldRun, expected) in cases) {
            assertEquals(
                "$reason with persisted should-run=$persistedShouldRun",
                expected,
                ServiceStartPolicy.shouldRun(reason, persistedShouldRun),
            )
        }
    }

    @Test
    fun processDeathResumesRequestedService() {
        val persistedShouldRun = ServiceStartPolicy.shouldRun(ServiceStartReason.User, false)

        assertTrue(ServiceStartPolicy.shouldRun(ServiceStartReason.StickyRestart, persistedShouldRun))
        assertTrue(ServiceStartPolicy.shouldRun(ServiceStartReason.Boot, persistedShouldRun))
    }

    @Test
    fun stopRevokeAndFatalConfigurationPreventRecoveryDuringStartupOrAfterStart() {
        for (reason in ServiceStopReason.entries) {
            var persistedShouldRun = ServiceStartPolicy.shouldRun(ServiceStartReason.User, false)
            assertTrue(persistedShouldRun)

            persistedShouldRun = ServiceStartPolicy.shouldRunAfterStop(reason)

            assertFalse("$reason clears should-run", persistedShouldRun)
            assertFalse(ServiceStartPolicy.shouldRun(ServiceStartReason.StickyRestart, persistedShouldRun))
            assertFalse(ServiceStartPolicy.shouldRun(ServiceStartReason.Boot, persistedShouldRun))
            assertTrue(ServiceStartPolicy.shouldRun(ServiceStartReason.User, persistedShouldRun))
            assertTrue(ServiceStartPolicy.shouldRun(ServiceStartReason.AlwaysOn, persistedShouldRun))
        }
    }
}
