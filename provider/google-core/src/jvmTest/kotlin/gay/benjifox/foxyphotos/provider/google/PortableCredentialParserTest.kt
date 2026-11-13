package gay.benjifox.foxyphotos.provider.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableCredentialParserTest {
    private val fields = "androidId=123&app=photos&client_sig=signature&Email=user%40example.com&Token=opaque&lang=en&service=photos"

    @Test fun `parses form fields and removes token binding metadata before auth`() {
        val credential = GoogleCredentialParser.parse("$fields&assertion_jwt=private&token_binding_alias=bound")
        assertEquals("user@example.com", credential.accountEmail)
        assertTrue(credential.requiresTokenBinding())
        assertFalse(credential.authRequestFields().any { it.first == "assertion_jwt" || it.first == "token_binding_alias" })
        assertTrue(credential.authRequestFields().contains("app" to "com.google.android.apps.photos"))
    }

    @Test fun `decodes escaped utf8 and plus form spaces`() {
        val credential = GoogleCredentialParser.parse(fields.replace("user%40example.com", "caf%C3%A9+fox%40example.com"))
        assertEquals("café fox@example.com", credential.accountEmail)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed escapes are rejected`() {
        GoogleCredentialParser.parse(fields.replace("user%40example.com", "user%XXexample.com"))
    }
}
