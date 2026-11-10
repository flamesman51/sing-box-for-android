package io.nekohasekai.sfa.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUpdateNotifierTest {
    @Test
    fun requestsPermissionForAutomaticServerUpdatesOnAndroid13AndLater() {
        for (sdkInt in 33..37) {
            assertTrue(ServerUpdateNotifier.permissionNeeded(sdkInt, UpdateSource.SERVER, true, false))
        }
    }

    @Test
    fun doesNotRequestPermissionBeforeAndroid13OrAfterGrant() {
        assertFalse(ServerUpdateNotifier.permissionNeeded(32, UpdateSource.SERVER, true, false))
        assertFalse(ServerUpdateNotifier.permissionNeeded(33, UpdateSource.SERVER, true, true))
    }

    @Test
    fun doesNotRequestPermissionForDisabledOrOtherUpdateSources() {
        assertFalse(ServerUpdateNotifier.permissionNeeded(33, UpdateSource.SERVER, false, false))
        assertFalse(ServerUpdateNotifier.permissionNeeded(33, UpdateSource.GITHUB, true, false))
        assertFalse(ServerUpdateNotifier.permissionNeeded(33, UpdateSource.FDROID, true, false))
    }
}
