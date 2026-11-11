package io.nekohasekai.sfa.bg

import android.app.Service
import android.content.Intent
import io.nekohasekai.libbox.Notification
import io.nekohasekai.sfa.compose.screen.smart.prepareSmartNotification

class ProxyService :
    Service(),
    PlatformInterfaceWrapper {
    private val service = BoxService(this, this)

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ) = service.onStartCommand(BoxService.startReason(intent), startId)

    override fun onBind(intent: Intent) = service.onBind()

    override fun onDestroy() = service.onDestroy()

    override fun sendNotification(notification: Notification) {
        prepareSmartNotification(notification)?.let(service::sendNotification)
    }

    override fun cancelNotification(
        identifier: String,
        typeID: Int,
    ) = service.cancelNotification(identifier, typeID)
}
