package io.nekohasekai.sfa.compose.screen.smart

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.nekohasekai.sfa.R

@Composable
internal fun smartProfileSupportingText(
    stored: String,
    snapshot: List<SmartStatus>?,
    applyState: SmartPolicyApplyState?,
    remote: Boolean,
): String {
    if (!remote && applyState == SmartPolicyApplyState.NotUsed) return stringResource(R.string.smart_profile_not_used)
    val runningNames = snapshot.orEmpty().map { smartRunningProfileName(it.preference) }.distinct()
    val label = if (remote) {
        when (runningNames.size) {
            0 -> stringResource(R.string.smart_profile_from)
            1 -> smartProfileLabel(runningNames.single())
            else -> stringResource(R.string.smart_profile_varies)
        }
    } else if (stored.isNotEmpty()) {
        val base = smartProfileLabel(smartPolicyBase(stored))
        if (smartPolicyIsCustom(stored)) stringResource(R.string.smart_profile_custom_based, base) else base
    } else {
        when (runningNames.size) {
            0 -> stringResource(R.string.smart_profile_from)
            1 -> stringResource(R.string.smart_profile_from_running, smartProfileLabel(runningNames.single()))
            else -> stringResource(R.string.smart_profile_varies)
        }
    }
    return if (!remote && applyState == SmartPolicyApplyState.Pending) stringResource(R.string.smart_profile_pending, label) else label
}

internal fun smartRunningProfileName(preference: SmartPreference?): String = when {
    preference == null || preference.engine == "legacy" -> "default"
    preference.custom -> "custom"
    else -> preference.base
}
