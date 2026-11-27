package com.utility.dlmsreader.dlms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** All 21 unsupported `.dat` files in the SANDS field corpus were 0 bytes. */
class EmptyImportFileTest {

    private fun messageFor(fileName: String, bytes: ByteArray): String? = try {
        MeterFileImportRouter.route(bytes, fileName, null)
        fail("import must fail")
        null
    } catch (error: IllegalArgumentException) {
        error.message
    }

    @Test
    fun `zero-byte file says it is empty instead of unsupported`() {
        listOf("A5023689.dat", "reading.OUT", "report.json", "").forEach { name ->
            assertEquals(name, MeterFileImportRouter.EMPTY_FILE_MESSAGE, messageFor(name, ByteArray(0)))
        }
    }

    @Test
    fun `non-empty unknown file still reports unsupported`() {
        val message = messageFor("notes.dat", "hello".toByteArray())
        assertTrue(message.orEmpty(), message.orEmpty().contains("not a supported meter file"))
    }
}
