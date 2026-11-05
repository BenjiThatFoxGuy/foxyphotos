package gay.benjifox.foxyphotos.provider.contract

import org.junit.Assert.fail
import org.junit.Test

class CredentialReferenceTest {
    @Test
    fun `rejects blank credential references`() {
        try {
            CredentialReference(" ")
            fail("Expected blank credential reference to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
