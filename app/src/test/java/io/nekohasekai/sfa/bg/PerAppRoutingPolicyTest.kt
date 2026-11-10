package io.nekohasekai.sfa.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerAppRoutingPolicyTest {
    private val own = "test.vpn"
    private val included = setOf("test.config.include")
    private val excluded = setOf("test.config.exclude")
    private val selected = setOf("test.selected", own)

    @Test
    fun configCombinationsChooseOnlyOneBuilderKind() {
        for (include in listOf(emptySet(), included)) {
            for (exclude in listOf(emptySet(), excluded)) {
                val decision = resolvePerAppRouting(include, exclude, false, false, selected, own)
                val expectedMode = when {
                    include.isNotEmpty() -> PerAppRoutingMode.Include
                    exclude.isNotEmpty() -> PerAppRoutingMode.Exclude
                    else -> PerAppRoutingMode.All
                }
                assertEquals(expectedMode, decision.mode)
                assertEquals(include.isNotEmpty() && exclude.isNotEmpty(), decision.conflictingConfig)
                assertEquals(
                    when (expectedMode) {
                        PerAppRoutingMode.Include -> included + own
                        PerAppRoutingMode.Exclude -> excluded
                        PerAppRoutingMode.All -> emptySet()
                    },
                    decision.packages,
                )
            }
        }
    }

    @Test
    fun appOverrideReplacesEveryConfigCombinationIncludingManagedExclude() {
        for (include in listOf(emptySet(), included)) {
            for (exclude in listOf(emptySet(), excluded)) {
                for (includeMode in listOf(false, true)) {
                    for (selection in listOf(emptySet(), selected)) {
                        val decision = resolvePerAppRouting(include, exclude, true, includeMode, selection, own)
                        assertEquals(if (includeMode) PerAppRoutingMode.Include else PerAppRoutingMode.Exclude, decision.mode)
                        assertEquals(if (includeMode) selection + own else selection - own, decision.packages)
                        assertFalse(decision.conflictingConfig)
                    }
                }
            }
        }
    }

    @Test
    fun configExcludeAndAppIncludeUseOnlyAppAllowList() {
        val decision = resolvePerAppRouting(emptySet(), excluded, true, true, setOf("test.selected"), own)
        assertEquals(PerAppRoutingMode.Include, decision.mode)
        assertEquals(setOf("test.selected", own), decision.packages)
    }

    @Test
    fun configIncludeAndAppExcludeUseOnlyAppDenyList() {
        val decision = resolvePerAppRouting(included, emptySet(), true, false, selected, own)
        assertEquals(PerAppRoutingMode.Exclude, decision.mode)
        assertEquals(setOf("test.selected"), decision.packages)
    }

    @Test
    fun ownPackageIsAlwaysAllowedInIncludeModeAndNeverDisallowed() {
        assertEquals(setOf(own), resolvePerAppRouting(emptySet(), excluded, true, true, emptySet(), own).packages)
        assertTrue(resolvePerAppRouting(included, emptySet(), false, false, emptySet(), own).packages.contains(own))
        assertFalse(resolvePerAppRouting(emptySet(), excluded + own, false, false, emptySet(), own).packages.contains(own))
        assertFalse(resolvePerAppRouting(included, excluded, true, false, selected, own).packages.contains(own))
    }
}
