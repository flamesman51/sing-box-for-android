package io.nekohasekai.sfa.bg

enum class PerAppRoutingMode { All, Include, Exclude }

data class PerAppRoutingDecision(
    val mode: PerAppRoutingMode,
    val packages: Set<String>,
    val conflictingConfig: Boolean = false,
)

fun resolvePerAppRouting(
    configInclude: Set<String>,
    configExclude: Set<String>,
    overrideEnabled: Boolean,
    overrideInclude: Boolean,
    overridePackages: Set<String>,
    ownPackage: String,
): PerAppRoutingDecision {
    if (overrideEnabled) {
        return if (overrideInclude) {
            PerAppRoutingDecision(PerAppRoutingMode.Include, overridePackages + ownPackage)
        } else {
            PerAppRoutingDecision(PerAppRoutingMode.Exclude, overridePackages - ownPackage)
        }
    }
    return when {
        configInclude.isNotEmpty() -> PerAppRoutingDecision(
            PerAppRoutingMode.Include,
            configInclude + ownPackage,
            conflictingConfig = configExclude.isNotEmpty(),
        )
        configExclude.isNotEmpty() -> PerAppRoutingDecision(PerAppRoutingMode.Exclude, configExclude - ownPackage)
        else -> PerAppRoutingDecision(PerAppRoutingMode.All, emptySet())
    }
}
