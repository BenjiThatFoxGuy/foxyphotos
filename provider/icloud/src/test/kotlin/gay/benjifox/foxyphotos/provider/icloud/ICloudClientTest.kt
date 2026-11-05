package gay.benjifox.foxyphotos.provider.icloud

import org.junit.Assert.*
import org.junit.Test

class ICloudClientTest {
    @Test fun `authentication is explicitly unavailable and cannot create a session`() {
        val result = ICloudAuth().signIn()
        assertEquals(ICloudAuthResult.Unavailable, result)
        assertEquals("iCloud sign-in is not implemented.", result.message)
    }
}
