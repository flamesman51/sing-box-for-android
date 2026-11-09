package io.nekohasekai.sfa.utils

import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.ktx.unwrap
import io.nekohasekai.sfa.update.ServerUpdatePolicy
import io.nekohasekai.sfa.update.ServerUpdates
import java.io.Closeable
import java.util.Locale

class HTTPClient : Closeable {
    companion object {
        val userAgent by lazy {
            var userAgent = "SFA (sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }
    }

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
    }

    fun getProfile(url: String): String {
        val request = client.newRequest()
        request.setUserAgent(userAgent)
        request.setURL(url)
        val response = request.execute()
        val content = response.content.unwrap
        runCatching {
            val finalUrl = ServerUpdatePolicy.profileDiscoveryUrl(url, response.getURL())
            if (finalUrl != null) {
                ServerUpdates.discover(finalUrl, response.getHeader("X-SFA-Smart-Update"))
            }
        }.onFailure {
            Log.w("HTTPClient", "Ignoring invalid app-update discovery")
        }
        return content
    }

    override fun close() {
        client.close()
    }
}
