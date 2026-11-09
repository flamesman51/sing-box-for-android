package io.nekohasekai.sfa.compose.screen.smart

import io.nekohasekai.libbox.Notification
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.R

internal fun prepareSmartNotification(notification: Notification): Notification? {
    if (notification.typeID != SMART_NOTIFICATION_TYPE) return notification
    if (!SmartSettings.notificationsEnabled) return null
    notification.title = Application.application.getString(R.string.smart_notification_unreachable)
    notification.typeName = Application.application.getString(R.string.smart_settings)
    return notification
}
