package io.nekohasekai.sfa.diagnostics

import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class DiagnosticsInstalledVersion {
    private val previousVersion = CompletableDeferred<Int?>()

    fun load(preferences: () -> SharedPreferences, installedVersion: Int) {
        var previous: Int? = null
        try {
            val stored = preferences()
            previous = stored.getInt("installed_version", -1).takeIf { it >= 0 }
            // apply() registers a finisher that Android drains during service transitions.
            stored.edit().putInt("installed_version", installedVersion).commit()
        } finally {
            previousVersion.complete(previous)
        }
    }

    fun recordUpdate(scope: CoroutineScope, installedVersion: Int, record: (Map<String, Any?>) -> Unit) = scope.launch {
        record(mapOf("from" to previousVersion.await(), "to" to installedVersion))
    }
}
