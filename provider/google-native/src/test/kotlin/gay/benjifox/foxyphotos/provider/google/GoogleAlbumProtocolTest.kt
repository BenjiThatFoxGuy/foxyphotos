package gay.benjifox.foxyphotos.provider.google

import org.junit.Assert.*
import org.junit.Test

class GoogleAlbumProtocolTest {
    private fun b(n: Int, value: ByteArray) = GoogleNativeProtocol.bytesField(n, value)
    private fun s(n: Int, value: String) = b(n, value.toByteArray())
    @Test fun nativeAlbumsPreserveOpaqueIdsCountAndDistinctWebId() {
        val entry = s(1, "native") + b(2, s(5, "Camera") + GoogleNativeProtocol.varintField(7, 20000)) + b(4, b(2, s(3, "web")))
        val page = GoogleAlbumProtocol.decode(b(1, s(1, "next") + b(3, entry)))
        assertEquals(GoogleAlbum("native", "Camera", 20000, "web"), page.albums.single())
        assertEquals("next", page.nextCursor)
    }
    @Test fun absentCountIsUnknownAndEmptyContainerIsValid() {
        val page = GoogleAlbumProtocol.decode(b(1, b(3, s(1, "native") + b(2, s(5, "Camera")))))
        assertNull(page.albums.single().count)
        assertTrue(GoogleAlbumProtocol.decode(b(1, byteArrayOf())).albums.isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun missingContainerFailsClosed() { GoogleAlbumProtocol.decode(byteArrayOf()) }
    @Test fun writesMatchPinnedProtocolAndCreationIsNotMembershipProof() {
        val create = GoogleNativeProtocol.ProtoFields(GoogleAlbumProtocol.create("Camera", 123))
        assertEquals("Camera", create.string(1)); assertEquals(123L, create.varint(2))
        assertTrue(create.messages(4).isEmpty())
        val add = GoogleNativeProtocol.ProtoFields(GoogleAlbumProtocol.add("album", "media", 124))
        assertEquals("media", add.string(1)); assertEquals("album", add.string(2)); assertEquals(124L, add.varint(7))
        assertEquals("new", GoogleAlbumProtocol.createdId(b(1, s(1, "new"))))
    }
}
