package io.nekohasekai.sfa.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUpdateInstallModelTest {
    private val ready = ReadyServerUpdate(760, "a".repeat(64), "/updates/760-aaaaaaaaaaaaaaaa.apk")

    private fun model() = ServerUpdateInstallModel().apply {
        sessionId = 42
        this.ready = this@ServerUpdateInstallModelTest.ready
        committed = true
    }

    @Test
    fun grantedInstallPermissionClearsThePendingRequest() {
        val model = ServerUpdateInstallModel().apply {
            waitingForPermission = true
            permissionExplanation = true
        }

        model.permissionResult(granted = true)

        assertFalse(model.waitingForPermission)
        assertFalse(model.permissionExplanation)
        assertFalse(model.preparing)
        assertFalse(model.committed)
    }

    @Test
    fun deniedInstallPermissionOffersTheExistingSettingsRetry() {
        val model = ServerUpdateInstallModel().apply {
            waitingForPermission = true
        }

        model.permissionResult(granted = false)

        assertFalse(model.waitingForPermission)
        assertTrue(model.permissionExplanation)
        assertFalse(model.committed)
    }

    @Test
    fun pendingConfirmationResultIsRetainedWithoutAResumedActivity() {
        val model = model()

        assertTrue(model.recordResult(42, ready, PackageInstaller.STATUS_PENDING_USER_ACTION, null))

        assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, model.resultStatus)
        assertTrue(model.committed)
        assertFalse(model.resumeSession)
    }

    @Test
    fun terminalResultIsRetainedUntilCoordinatorCanConsumeIt() {
        val model = model()

        assertTrue(model.recordResult(42, ready, PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, model.resultStatus)
        assertNull(model.confirmation)
        assertTrue(model.recordResult(42, ready, PackageInstaller.STATUS_SUCCESS, null))
        assertEquals(PackageInstaller.STATUS_SUCCESS, model.resultStatus)
    }

    @Test
    fun staleSessionOrReadyIdentityCannotChangeTheCurrentResult() {
        val model = model()

        assertFalse(model.recordResult(41, ready, PackageInstaller.STATUS_SUCCESS, null))
        assertFalse(model.recordResult(42, ready.copy(versionCode = 761), PackageInstaller.STATUS_SUCCESS, null))
        assertNull(model.resultStatus)
    }
}
