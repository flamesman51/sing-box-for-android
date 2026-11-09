package io.nekohasekai.sfa.compose.screen.smart

import android.net.Uri

internal const val SMART_INSIGHTS_ROUTE = "tools/smart_insights?group={group}"

internal fun smartInsightsRoute(group: String = ""): String = "tools/smart_insights?group=${Uri.encode(group)}"
