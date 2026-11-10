package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.boolean
import io.nekohasekai.sfa.ktx.string

object SmartSettings {
    // An empty override uses the first successful automatic selection, not the current locale.
    var countryOverride by Settings.dataStore.string("smart_country_override")
    internal var automaticCountry by Settings.dataStore.string("smart_automatic_country")
    var notificationsEnabled by Settings.dataStore.boolean("smart_notifications_enabled") { true }
    internal var historyPermissionPrompted by Settings.dataStore.boolean("smart_history_permission_prompted")
    internal var historyForegroundRequested by Settings.dataStore.boolean("smart_history_foreground_requested")
}
