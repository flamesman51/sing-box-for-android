package io.nekohasekai.sfa.vendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubUpdateCheckerTest {
    private fun asset(name: String) = GitHubUpdateChecker.GitHubAsset(name = name)

    @Test
    fun prefersOtherArm64OverUniversalAndUnrelatedVariants() {
        val arm64 = asset("SFA-1.14.3-smart.1-arm64-v8a.apk")
        val assets =
            listOf(
                asset("SFA-1.14.3-smart.1-play-arm64-v8a.apk"),
                asset("SFA-1.14.3-smart.1-legacy-android-5-arm64-v8a.apk"),
                asset("SFA-1.14.3-smart.1-x86_64.apk"),
                asset("SFA-1.14.3-smart.1-universal.apk"),
                arm64,
            )
        assertEquals(arm64, GitHubUpdateChecker.selectApkAsset(assets, arrayOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test
    fun fallsBackToOtherUniversalOnArm64() {
        val universal = asset("SFA-1.14.3-smart.1-universal.apk")
        assertEquals(universal, GitHubUpdateChecker.selectApkAsset(listOf(universal), arrayOf("arm64-v8a")))
    }

    @Test
    fun doesNotOfferArm64OnlyBuildsToOtherDevicesOrUseReleaseWebPage() {
        val arm64 = asset("SFA-1.14.3-smart.1-arm64-v8a.apk")
        assertNull(GitHubUpdateChecker.selectApkAsset(listOf(arm64), arrayOf("x86_64")))
        assertNull(GitHubUpdateChecker.selectApkAsset(listOf(asset("SFA-version-metadata.json")), arrayOf("arm64-v8a")))
    }
}
