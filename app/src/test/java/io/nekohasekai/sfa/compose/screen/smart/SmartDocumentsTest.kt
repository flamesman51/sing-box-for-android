package io.nekohasekai.sfa.compose.screen.smart

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class SmartDocumentsTest {
    @Test
    fun acceptsUtf8AndExactLimit() {
        assertEquals("Привет", readSmartImport(ByteArrayInputStream("Привет".toByteArray())))
        assertEquals(SMART_IMPORT_LIMIT, readSmartImport(ByteArrayInputStream(ByteArray(SMART_IMPORT_LIMIT) { 32 })).length)
    }

    @Test(expected = IOException::class)
    fun rejectsOversizedDocument() {
        readSmartImport(ByteArrayInputStream(ByteArray(SMART_IMPORT_LIMIT + 1)))
    }
}
