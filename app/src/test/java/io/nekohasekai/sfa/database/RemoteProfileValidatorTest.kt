package io.nekohasekai.sfa.database

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteProfileValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unchangedPreviouslyValidatedContentSkipsCheckAfterRestart() {
        val directory = temporaryFolder.newFolder()
        var checks = 0
        RemoteProfileValidator(directory) { checks++ }.validate(1, null, "{}", 1)

        RemoteProfileValidator(directory) { checks++ }.validate(1, "{}", "{}", 1)

        assertEquals(1, checks)
    }

    @Test
    fun changedContentIsCheckedEvenWhenItWasPreviouslyValidated() {
        var checks = 0
        val validator = RemoteProfileValidator(temporaryFolder.newFolder()) { checks++ }
        validator.validate(1, null, "{}", 1)
        validator.validate(1, "other", "{}", 1)
        assertEquals(2, checks)
    }

    @Test
    fun invalidContentIsRejectedAndNeverRemembered() {
        var checks = 0
        val validator = RemoteProfileValidator(temporaryFolder.newFolder()) {
            checks++
            require(it != "invalid") { "Invalid configuration" }
        }
        repeat(2) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                validator.validate(1, "invalid", "invalid", 1)
            }
        }
        assertEquals(2, checks)
    }

    @Test
    fun firstRefreshAfterVersionChangeRechecksUnchangedContent() {
        var checks = 0
        val validator = RemoteProfileValidator(temporaryFolder.newFolder()) { checks++ }
        validator.validate(1, "{}", "{}", 1)
        validator.validate(1, "{}", "{}", 2)
        validator.validate(1, "{}", "{}", 2)
        assertEquals(2, checks)
    }
}
