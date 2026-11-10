package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.sfa.R
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartHistoryPermissionsTest {
    @Test
    fun missingForegroundUsesTheRuntimeStepWhenRequestable() {
        assertEquals(SmartPermissionStep.Foreground, SmartHistoryPermissions(false, false, false).nextStep(37, true))
        assertEquals(SmartPermissionStep.Foreground, SmartHistoryPermissions(true, true, false).nextStep(29, true))
    }

    @Test
    fun permanentlyDeniedForegroundUsesAppSettings() {
        assertEquals(SmartPermissionStep.ForegroundSettings, SmartHistoryPermissions(false, true, true).nextStep(37, false))
        assertEquals(SmartPermissionStep.ForegroundSettings, SmartHistoryPermissions(true, false, false).nextStep(29, false))
    }

    @Test
    fun backgroundUsesRuntimeOnAndroidTenAndSettingsOnNewerVersions() {
        val foregroundGranted = SmartHistoryPermissions(true, false, true)
        assertEquals(SmartPermissionStep.BackgroundRequest, foregroundGranted.nextStep(29, false))
        assertEquals(SmartPermissionStep.BackgroundSettings, foregroundGranted.nextStep(30, false))
        assertEquals(SmartPermissionStep.BackgroundSettings, foregroundGranted.nextStep(37, false))
    }

    @Test
    fun completePermissionsFinishAndOlderAndroidNeedsNoBackgroundPermission() {
        assertEquals(SmartPermissionStep.Done, SmartHistoryPermissions(true, true, true).nextStep(37, false))
        assertEquals(SmartPermissionStep.Done, SmartHistoryPermissions(true, false, true).nextStep(24, false))
        assertEquals(SmartPermissionStep.Done, SmartHistoryPermissions(true, false, true).nextStep(28, false))
    }

    @Test
    fun foregroundIsAlwaysTheFirstMissingStep() {
        assertEquals(SmartPermissionStep.Foreground, SmartHistoryPermissions(false, false, true).nextStep(29, true))
        assertEquals(SmartPermissionStep.ForegroundSettings, SmartHistoryPermissions(true, false, false).nextStep(37, false))
    }

    @Test
    fun historyLabelDependsOnFineAndBackgroundButNeverPhoneState() {
        for (sdkInt in listOf(24, 28, 29, 30, 31, 33, 37)) {
            for (phone in listOf(false, true)) {
                for (background in listOf(false, true)) {
                    assertEquals(R.string.smart_history_basic, SmartHistoryPermissions(false, background, phone).historyLabel(sdkInt))
                    assertEquals(
                        if (sdkInt >= 29 && !background) R.string.smart_history_foreground else R.string.smart_history_detailed,
                        SmartHistoryPermissions(true, background, phone).historyLabel(sdkInt),
                    )
                }
            }
        }
    }

    @Test
    fun flagsUseRegionalIndicatorPairs() {
        assertEquals("\uD83C\uDDF7\uD83C\uDDFA", countryFlag("RU"))
        assertEquals("\uD83C\uDDE8\uD83C\uDDF3", countryFlag("CN"))
    }
}
