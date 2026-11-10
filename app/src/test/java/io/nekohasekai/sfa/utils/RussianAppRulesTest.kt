package io.nekohasekai.sfa.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RussianAppRulesTest {
    private val rules = RussianAppRules(setOf("ru.verified.bank", "com.verified.payment"))

    @Test
    fun verifiedPackagesMatchExactlyWithoutCountryWidePrefixes() {
        assertTrue(rules.matchesPackage("ru.verified.bank"))
        assertTrue(rules.matchesPackage("com.verified.payment"))
        assertFalse(rules.matchesPackage("ru.verified.bank.clone"))
        assertFalse(rules.matchesPackage("ru.unrelated.app"))
        assertFalse(rules.matchesPackage("com.yandex.metrica.fake"))
    }

    @Test
    fun recognizedSdkNamespacesMatchManifestComponentsAndDexClasses() {
        for (name in listOf(
            "com.yandex.metrica.MetricaService",
            "io.appmetrica.analytics.AppMetrica",
            "com.vk.id.VKID",
            "com.vk.api.sdk.VK",
            "com.my.tracker.MyTracker",
            "ru.rustore.sdk.core.Client",
            "sberid.sdk.auth.view.SberIDButton",
            "sid.sdk.auth.view.SIDButton",
        )) {
            assertTrue(name, rules.matchesClass(name))
        }
    }

    @Test
    fun namespaceBoundariesAndUnrelatedLibrariesDoNotMatch() {
        for (name in listOf(
            "com.yandex.metrication.Other",
            "com.vk.identity.Other",
            "ru.rustore.sdkfake.Other",
            "com.yandex.mapkit.MapView",
            "com.vk.unrelated.Client",
            "com.google.firebase.FirebaseApp",
            "android.app.Activity",
            "ru.unrelated.ESIAClient",
        )) {
            assertFalse(name, rules.matchesClass(name))
        }
    }

    @Test
    fun existingAndUnknownSettingsDefaultToChinaAndChoicesRoundTrip() {
        assertEquals(setOf(AppScanRegion.China), AppScanRegion.fromSettings(emptySet()))
        assertEquals(setOf(AppScanRegion.China), AppScanRegion.fromSettings(setOf("unknown")))
        for (regions in listOf(setOf(AppScanRegion.China), setOf(AppScanRegion.Russia), AppScanRegion.entries.toSet())) {
            assertEquals(regions, AppScanRegion.fromSettings(regions.map { it.name }.toSet()))
        }
    }

    @Test
    fun legacyToggleMapsToASelectionAndEmptySelectionMeansOff() {
        for (regions in listOf(setOf("China"), setOf("Russia"), setOf("China", "Russia"))) {
            assertEquals(emptySet<AppScanRegion>(), managedRegionSelection(false, regions))
            val selection = managedRegionSelection(true, regions)
            assertEquals(regions, selection.map { it.name }.toSet())
            assertTrue(selection.isNotEmpty())
        }
        assertEquals(setOf(AppScanRegion.China), managedRegionSelection(true, emptySet()))
        assertEquals(setOf(AppScanRegion.China), managedRegionSelection(true, setOf("unknown")))
    }

    @Test
    fun dexParsingRejectsOversizedAndUnknownEntries() {
        assertTrue(canParseAppDex(0))
        assertTrue(canParseAppDex(MAX_SCANNED_DEX_BYTES))
        assertFalse(canParseAppDex(MAX_SCANNED_DEX_BYTES + 1))
        assertFalse(canParseAppDex(-1))
        assertFalse(canParseAppDex(Long.MAX_VALUE))
    }
}
