package io.nekohasekai.sfa.utils

enum class AppScanRegion {
    China,
    Russia,
    ;

    companion object {
        fun fromSettings(values: Set<String>): Set<AppScanRegion> = entries.filter { it.name in values }.toSet()
    }
}

const val MAX_SCANNED_DEX_BYTES = 15_000_000L

fun canParseAppDex(size: Long): Boolean = size in 0..MAX_SCANNED_DEX_BYTES

fun managedRegionSelection(
    enabled: Boolean,
    values: Set<String>,
): Set<AppScanRegion> = if (enabled) AppScanRegion.fromSettings(values).ifEmpty { setOf(AppScanRegion.China) } else emptySet()

class RussianAppRules(
    private val packages: Set<String>,
) {
    fun matchesPackage(packageName: String): Boolean = packageName in packages

    fun matchesClass(className: String): Boolean = sdkPrefixes.any { className.startsWith(it) }

    companion object {
        // SDK namespaces only: a generic ru.*, Yandex, or VK prefix would bypass unrelated apps.
        private val sdkPrefixes =
            listOf(
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
