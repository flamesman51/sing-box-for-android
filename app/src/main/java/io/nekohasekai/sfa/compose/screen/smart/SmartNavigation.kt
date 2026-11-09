package io.nekohasekai.sfa.compose.screen.smart

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf

internal const val SMART_INSIGHTS_ROUTE = "smart/insights?group={group}"

internal fun smartInsightsRoute(group: String = ""): String = "smart/insights?group=${Uri.encode(group)}"

internal val LocalSmartInsightsNavigator = staticCompositionLocalOf<(String) -> Unit> {
    error("Smart insights navigation is not provided")
}
