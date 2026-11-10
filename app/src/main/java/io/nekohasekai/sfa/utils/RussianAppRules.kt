package io.nekohasekai.sfa.utils

enum class AppScanRegion {
    China,
    Russia,
    ;

    companion object {
        fun fromSettings(values: Set<String>): Set<AppScanRegion> = entries.filter { it.name in values }.toSet().ifEmpty { setOf(China) }
    }
}

class RussianAppRules(private val packages: Set<String>) {
    fun matchesPackage(packageName: String): Boolean = packageName in packages

    fun matchesClass(className: String): Boolean = sdkPrefixes.any { className.startsWith(it) }

    companion object {
        // SDK namespaces only: a generic ru.*, Yandex, or VK prefix would bypass unrelated apps.
        private val sdkPrefixes = listOf(
            "com.yandex.metrica",
            "io.appmetrica.analytics",
            "com.vk.id",
            "com.vk.api.sdk",
            "com.my.tracker",
            "ru.rustore.sdk",
            "sberid.sdk.auth",
            "sid.sdk.auth",
        ).map { "$it." }
    }
}
