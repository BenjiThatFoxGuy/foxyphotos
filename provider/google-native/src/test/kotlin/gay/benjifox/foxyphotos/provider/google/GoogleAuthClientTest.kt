package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.provider.foundation.HttpClient
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthClientTest {
    @Test
    fun `does not expose an endpoint override in production bytecode`() {
        val exposedMethods = GoogleAuthClient::class.java.declaredMethods.asList() +
            GoogleAuthClient::class.java.declaredClasses.flatMap { it.declaredMethods.asList() }
        assertTrue(
            GoogleAuthClient::class.java.constructors.none { constructor ->
                constructor.parameterTypes.any { parameter -> parameter == HttpClient::class.java }
            },
        )
        val credentialSecretAccessors = GoogleCredential::class.java.declaredMethods.filter { method ->
            method.name.contains("authRequestFields", ignoreCase = true)
        }
        val bearerSecretAccessors = GoogleBearerToken::class.java.declaredMethods.filter { method ->
            method.name.contains("authorizationHeader", ignoreCase = true)
        }
        assertTrue(credentialSecretAccessors.isNotEmpty() && credentialSecretAccessors.all { it.isSynthetic })
        assertTrue(bearerSecretAccessors.isNotEmpty() && bearerSecretAccessors.all { it.isSynthetic })
        assertTrue(
            exposedMethods.none { method ->
                method.name.contains("Endpoint", ignoreCase = true) ||
                    method.name.contains("ForTest", ignoreCase = true)
            },
        )
    }

    @Test
    fun `exchanges a credential through the transformed Android auth form`() {
        val credential = GoogleCredentialParser.parse(
            "androidId=device-123&app=unexpected.app&client_sig=sig&Email=person%40example.com&" +
                "Token=secret-token&lang=en_US&service=oauth2%3Aphotos.native&" +
                "extra=kept&it_caveat_types=remove&assertion_jwt=remove&token_binding_alias=remove",
        )

        val request = createAuthRequest(credential)
        val result = parseAuthResponse(authResponse("Auth=fresh-bearer-token\nExpiry=1700000000\n"), credential)

        assertEquals("Bearer fresh-bearer-token", result.authorizationHeader())
        assertEquals(1_700_000_000L, result.expiresAtEpochSeconds)
        assertEquals("POST", request.method)
        assertEquals("https://android.googleapis.com/auth", request.url.toString())
        assertEquals("application/x-www-form-urlencoded", request.body!!.contentType().toString())
        assertEquals("device-123", request.header("device"))
        assertEquals("com.google.android.apps.photos", request.header("app"))

        val form = decodeForm(request.body!!.writeToBuffer())
        assertEquals("com.google.android.apps.photos", form["app"])
        assertEquals("com.google.android.apps.photos", form["callerPkg"])
        assertEquals("kept", form["extra"])
        assertNull(form["it_caveat_types"])
        assertNull(form["assertion_jwt"])
        assertNull(form["token_binding_alias"])
        assertFalse(result.toString().contains("fresh-bearer-token"))
        assertFalse(credential.toString().contains("secret-token"))
    }

    @Test
    fun `decodes a gzip encoded auth response`() {
        val credential = GoogleCredentialParser.parse(
            "androidId=device-123&app=app&client_sig=sig&Email=person%40example.com&" +
                "Token=secret-token&lang=en_US&service=oauth2%3Aphotos.native",
        )

        val result = parseAuthResponse(
            response = authResponse(
                body = gzip("Auth=gzip-bearer-token\nExpiry=1700000001\n"),
                contentEncoding = "gzip",
            ),
            credential = credential,
        )

        assertEquals("Bearer gzip-bearer-token", result.authorizationHeader())
        assertEquals(1_700_000_001L, result.expiresAtEpochSeconds)
    }

    private fun createAuthRequest(credential: GoogleCredential): Request {
        val method = GoogleAuthClient::class.java.getDeclaredMethod("createAuthRequest", GoogleCredential::class.java)
        method.isAccessible = true
        return method.invoke(GoogleAuthClient(), credential) as Request
    }

    private fun parseAuthResponse(response: Response, credential: GoogleCredential): GoogleBearerToken {
        val method = GoogleAuthClient::class.java.getDeclaredMethod(
            "parseAuthResponse",
            Response::class.java,
            GoogleCredential::class.java,
        )
        method.isAccessible = true
        return method.invoke(GoogleAuthClient(), response, credential) as GoogleBearerToken
    }

    private fun authResponse(body: String): Response = authResponse(body.toByteArray(StandardCharsets.UTF_8))

    private fun authResponse(body: ByteArray, contentEncoding: String? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("https://android.googleapis.com/auth").build())
            .protocol(Protocol.HTTP_2)
            .code(200)
            .message("OK")
            .apply { contentEncoding?.let { header("Content-Encoding", it) } }
            .body(body.toResponseBody())
            .build()

    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(value.toByteArray(StandardCharsets.UTF_8)) }
        output.toByteArray()
    }

    private fun decodeForm(encoded: String): Map<String, String> = encoded
        .split('&')
        .associate { field ->
            val separator = field.indexOf('=')
            assertTrue(separator > 0)
            URLDecoder.decode(field.substring(0, separator), StandardCharsets.UTF_8) to
                URLDecoder.decode(field.substring(separator + 1), StandardCharsets.UTF_8)
        }

    private fun okhttp3.RequestBody.writeToBuffer(): String = Buffer().use { buffer ->
        writeTo(buffer)
        buffer.readUtf8()
    }
}
