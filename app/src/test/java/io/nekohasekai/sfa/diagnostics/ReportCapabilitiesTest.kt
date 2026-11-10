package io.nekohasekai.sfa.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReportCapabilitiesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = "https://feed.example/source"
    private val destination = "https://reports.example/final"

    @Test
    fun acceptedFetchUsesFinalHttpsDestinationAndSurvivesReload() {
        val directory = temporaryFolder.newFolder()
        val capabilities = ReportCapabilities.Store(directory)
        capabilities.accepted(1, source, destination, "1")

        assertEquals(destination, capabilities.destination(1, source))
        assertEquals(destination, ReportCapabilities.Store(directory).destination(1, source))
        assertNull(capabilities.destination(1, "https://feed.example/other"))
    }

    @Test
    fun successfulFetchWithoutExactHeaderRevokesPreviousCapability() {
        val capabilities = ReportCapabilities.Store(temporaryFolder.newFolder())
        for (header in listOf(null, "", "0", "true", " 1", "1 ", "1, 1")) {
            capabilities.accepted(1, source, destination, "1")
            capabilities.accepted(1, source, destination, header)
            assertNull(capabilities.destination(1, source))
        }
    }

    @Test
    fun withdrawalTakesEffectBeforeConfigurationIsAccepted() {
        val directory = temporaryFolder.newFolder()
        val capabilities = ReportCapabilities.Store(directory)
        for ((finalURL, header) in listOf(destination to null, destination to "0", "http://reports.example/final" to "1")) {
            capabilities.accepted(1, source, destination, "1")
            capabilities.withdrawIfUnsupported(1, source, finalURL, header)

            // Rejected configuration never reaches accepted(), so withdrawal must already be durable.
            assertNull(ReportCapabilities.Store(directory).destination(1, source))
        }
    }

    @Test
    fun successfulFetchCannotDiscoverCapabilityBeforeConfigurationAcceptance() {
        val capabilities = ReportCapabilities.Store(temporaryFolder.newFolder())
        capabilities.withdrawIfUnsupported(1, source, destination, "1")
        assertNull(capabilities.destination(1, source))
    }

    @Test
    fun oldSourceWithdrawalDoesNotEraseNewSourceCapability() {
        val capabilities = ReportCapabilities.Store(temporaryFolder.newFolder())
        val newSource = "https://feed.example/replacement"
        capabilities.accepted(1, newSource, destination, "1")

        capabilities.withdrawIfUnsupported(1, source, destination, null)
        capabilities.withdrawIfUnsupported(1, source, "http://reports.example/final", "1")

        assertEquals(destination, capabilities.destination(1, newSource))
    }

    @Test
    fun editedUrlCannotRestoreOldCapabilityWhenChangedBack() {
        val directory = temporaryFolder.newFolder()
        val capabilities = ReportCapabilities.Store(directory)
        capabilities.accepted(1, source, destination, "1")

        capabilities.profileChanged(1, "https://feed.example/replacement")
        capabilities.profileChanged(1, source)

        assertNull(ReportCapabilities.Store(directory).destination(1, source))
        capabilities.accepted(1, source, destination, "1")
        assertEquals(destination, capabilities.destination(1, source))
    }

    @Test
    fun unchangedSourceRetainsCapabilityButLocalProfileAndDeletionRevokeIt() {
        val capabilities = ReportCapabilities.Store(temporaryFolder.newFolder())
        capabilities.accepted(1, source, destination, "1")
        capabilities.profileChanged(1, source)
        assertEquals(destination, capabilities.destination(1, source))

        capabilities.profileChanged(1, null)
        assertNull(capabilities.destination(1, source))
        capabilities.accepted(1, source, destination, "1")
        capabilities.clear(1)
        assertNull(capabilities.destination(1, source))
    }

    @Test
    fun finalHttpOrInvalidDestinationRevokesCapability() {
        val capabilities = ReportCapabilities.Store(temporaryFolder.newFolder())
        for (finalURL in listOf("http://reports.example/final", "", "/relative", "https:///missing-host")) {
            capabilities.accepted(1, source, destination, "1")
            capabilities.accepted(1, source, finalURL, "1")
            assertNull(capabilities.destination(1, source))
        }
    }

    @Test
    fun httpFetchRedirectedToFinalHttpsMayDiscoverCapability() {
        val capabilities = ReportCapabilities.Store(temporaryFolder.newFolder())
        val httpSource = "http://feed.example/source"
        capabilities.accepted(1, httpSource, destination, "1")
        assertEquals(destination, capabilities.destination(1, httpSource))
    }

    @Test
    fun selectedProfileLookupNeverUsesAnotherProfilesCapability() {
        val capabilities = ReportCapabilities.Store(temporaryFolder.newFolder())
        val otherDestination = "https://other.example/final"
        capabilities.accepted(1, source, destination, "1")
        assertNull(capabilities.destination(2, source))
        capabilities.accepted(2, source, otherDestination, "1")

        capabilities.accepted(1, source, destination, null)

        assertNull(capabilities.destination(1, source))
        assertEquals(otherDestination, capabilities.destination(2, source))
        assertNull(capabilities.destination(-1, source))
    }

    @Test
    fun corruptCapabilityFailsClosed() {
        val directory = temporaryFolder.newFolder()
        directory.resolve("1").writeText("invalid state")
        assertNull(ReportCapabilities.Store(directory).destination(1, source))
    }
}
