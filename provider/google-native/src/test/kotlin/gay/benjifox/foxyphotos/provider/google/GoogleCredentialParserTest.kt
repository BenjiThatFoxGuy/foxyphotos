package gay.benjifox.foxyphotos.provider.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCredentialParserTest {
    @Test
    fun `parses a complete imported Google Photos credential without retaining its raw form`() {
        val credential = GoogleCredentialParser.parse(
            "androidId=device-123&app=com.google.android.apps.photos&client_sig=sig&Email=person%40example.com&Token=secret-token&lang=en_US&service=oauth2%3Aphotos.native",
        )

        assertEquals("person@example.com", credential.accountEmail)
        assertEquals("device-123", credential.androidId)
        assertTrue(credential.hasRequiredFields)
    }
}
