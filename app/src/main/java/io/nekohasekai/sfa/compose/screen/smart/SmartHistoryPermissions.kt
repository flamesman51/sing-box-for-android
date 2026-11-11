package io.nekohasekai.sfa.compose.screen.smart

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.nekohasekai.sfa.R

internal enum class SmartPermissionStep {
    Foreground,
    ForegroundSettings,
    BackgroundRequest,
    BackgroundSettings,
    Done,
}

internal data class SmartHistoryPermissions(val fine: Boolean, val background: Boolean, val phone: Boolean) {
    fun nextStep(sdkInt: Int, foregroundRequestable: Boolean): SmartPermissionStep = when {
        !fine || !phone -> if (foregroundRequestable) SmartPermissionStep.Foreground else SmartPermissionStep.ForegroundSettings
        sdkInt >= 29 && !background -> if (sdkInt == 29) SmartPermissionStep.BackgroundRequest else SmartPermissionStep.BackgroundSettings
        else -> SmartPermissionStep.Done
    }

    fun historyLabel(sdkInt: Int): Int = when {
        !fine -> R.string.smart_history_basic
        sdkInt >= 29 && !background -> R.string.smart_history_foreground
        else -> R.string.smart_history_detailed
    }
}

@Composable
internal fun rememberSmartHistoryPermissions(revision: Int = 0): SmartHistoryPermissions {
    val context = LocalContext.current
    var resumeRevision by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeRevision++ }
    return remember(context, revision, resumeRevision) {
        fun granted(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        SmartHistoryPermissions(
            fine = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            phone = granted(Manifest.permission.READ_PHONE_STATE),
        )
    }
}
