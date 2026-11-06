package gay.benjifox.foxyphotos.provider.google

import org.junit.Assert.*
import org.junit.Test

class GoogleLibraryProtocolTest {
    private fun field(number: Int, bytes: ByteArray) = GoogleNativeProtocol.bytesField(number, bytes)
    private fun text(number: Int, value: String) = field(number, value.toByteArray())

    @Test fun `decodes actual wire paths without matching filenames or claiming backup`() {
        val metadata = text(4, "same.jpg") + byteArrayOf(56, 100)
        val first = text(1, "provider-one") + field(2, metadata)
        val second = text(1, "provider-two") + field(2, metadata) + field(5, byteArrayOf(8, 1))
        val page = GoogleLibraryProtocol.decode(field(1, text(1, "next-page") + field(2, first) + field(2, second)))
        assertEquals(listOf("provider-one", "provider-two"), page.items.map { it.id })
        assertEquals(100000L, page.items.first().timestampMillis)
        assertTrue(page.items.last().video)
        assertEquals("next-page", page.nextCursor)
    }
    @Test fun `initial mask and resume request preserve the captured mask`() {
        val initial = GoogleNativeProtocol.ProtoFields(GoogleLibraryProtocol.request(null))
        val resumed = GoogleNativeProtocol.ProtoFields(GoogleLibraryProtocol.request("cursor"))
        assertArrayEquals(initial.bytes(2), resumed.bytes(2))
        assertEquals("cursor", resumed.message(1)!!.string(4))
        assertTrue(resumed.bytes(1)!!.size > initial.bytes(1)!!.size)
    }
    @Test fun `empty container is a real terminal empty page`() {
        val page = GoogleLibraryProtocol.decode(field(1, byteArrayOf()))
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }
    @Test fun `malformed missing and oversized fields fail closed`() {
        listOf(byteArrayOf(), byteArrayOf(10, 127), field(1, field(2, text(2, "not-an-id"))),
            field(1, text(1, "x".repeat(16385)))).forEach { bytes ->
            assertTrue(runCatching { GoogleLibraryProtocol.decode(bytes) }.isFailure)
        }
    }
}
