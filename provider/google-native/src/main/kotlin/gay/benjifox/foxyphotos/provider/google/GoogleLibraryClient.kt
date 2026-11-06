package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.core.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Read-only, single-page native protocol adapter. Never logs request/response material. */
class GoogleLibraryClient(
    private val credential: suspend () -> GoogleCredential,
    private val auth: GoogleAuthClient = GoogleAuthClient(),
) : ReadOnlyLibrary, RemoteOriginalReader, GoogleAlbumAccess {
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()

    override suspend fun page(cursor: String?): LibraryPage {
        val request = authenticated("https://photosdata-pa.googleapis.com/6439526531001121323/18047484249733410717")
            .header("X-Goog-Ext-173412678-Bin", "CgcIAhClARgC")
            .header("X-Goog-Ext-174067345-Bin", "CgIIAg==")
            .post(GoogleLibraryProtocol.request(cursor).toRequestBody("application/x-protobuf".toMediaType())).build()
        return GoogleLibraryProtocol.decode(fetch(request, 8 * 1024 * 1024))
    }

    override suspend fun albumPage(cursor: String?): GoogleAlbumPage {
        val request = authenticated("https://photosdata-pa.googleapis.com/6439526531001121323/18047484249733410717")
            .header("X-Goog-Ext-173412678-Bin", "CgcIAhClARgC")
            .header("X-Goog-Ext-174067345-Bin", "CgIIAg==")
            .post(GoogleLibraryProtocol.request(cursor).toRequestBody("application/x-protobuf".toMediaType())).build()
        return GoogleAlbumProtocol.decode(fetch(request, 8 * 1024 * 1024))
    }

    /** No retry: a response loss can follow a committed remote write. */
    override suspend fun createAlbum(title: String): String {
        require(title.isNotBlank() && title.length <= 500)
        val bytes = albumWrite("8386163679468898444", GoogleAlbumProtocol.create(title))
        return GoogleAlbumProtocol.createdId(bytes)
    }

    /** HTTP acknowledgement is deliberately NOT membership confirmation. */
    override suspend fun requestMembership(albumId: String, mediaKey: String) {
        albumWrite("484917746253879292", GoogleAlbumProtocol.add(albumId, mediaKey))
    }

    private suspend fun albumWrite(endpoint: String, bytes: ByteArray): ByteArray = fetch(
        authenticated("https://photosdata-pa.googleapis.com/6439526531001121323/$endpoint")
            .post(bytes.toRequestBody("application/x-protobuf".toMediaType())).build(), 1024 * 1024)

    override suspend fun preview(id: String, large: Boolean): ByteArray {
        require(id.isNotBlank() && id.length <= 4096)
        // Same authenticated media route as bclone DownloadMedia, with a bounded display rendition.
        // These are previews, never evidence of original-byte equality for cleanup.
        val suffix = if (large) "=w1600-h1600" else "=w320-h320"
        val url = "https://lh3.googleusercontent.com/".toHttpUrl().newBuilder()
            .addPathSegment("p").addPathSegment(id + suffix).build()
        return fetch(authenticated(url.toString()).get().build(), if (large) 8 * 1024 * 1024 else 2 * 1024 * 1024)
    }

    /** Actual download stream, never a rendition/metadata hash. Equality is decided by caller. */
    override suspend fun originalHash(id: String, expectedSize: Long): String {
        require(id.isNotBlank() && id.length <= 4096)
        require(expectedSize in 1..(256L * 1024 * 1024))
        val url = "https://lh3.googleusercontent.com/".toHttpUrl().newBuilder()
            .addPathSegment("p").addPathSegment(id + "=d").build()
        return http.newCall(authenticated(url.toString()).get().build()).consumeCancellable { response ->
            hashOriginalResponse(response, expectedSize)
        }
    }

    private fun hashOriginalResponse(response: okhttp3.Response, expectedSize: Long): String {
        require(expectedSize in 1..(256L * 1024 * 1024))
            if (response.code == 401 || response.code == 403) {
                auth.clearCachedSession()
                throw GoogleAuthenticationException("Reconnect Google Photos in Settings.")
            }
            check(response.code == 200) { "Original unavailable" }
            val body = response.body ?: error("Original unavailable")
            check(body.contentLength() == -1L || body.contentLength() == expectedSize) { "Original size differs" }
            return body.byteStream().use { input ->
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(32 * 1024)
                var count = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    count += n
                    check(count <= expectedSize) { "Original size differs" }
                    digest.update(buffer, 0, n)
                }
                check(count == expectedSize) { "Original size differs" }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
    }

    internal suspend fun authenticated(url: String): Request.Builder = Request.Builder().url(url)
        .header("Authorization", auth.exchange(credential()).authorizationHeader())
        .header("User-Agent", "Google Photos/49029607 (Pixel XL; Android 9)")
        .header("Accept-Language", "en-US")

    internal suspend fun fetch(request: Request, limit: Int): ByteArray = http.newCall(request).consumeCancellable { response ->
        if (response.code == 401 || response.code == 403) {
            auth.clearCachedSession()
            throw GoogleAuthenticationException("Google library access denied. Reconnect Google Photos in Settings.")
        }
        if (!response.isSuccessful) throw IOException("Google library request failed (HTTP ${response.code}).")
        val body = response.body ?: throw IOException("Google library response is empty.")
        if (body.contentLength() > limit) throw IOException("Google response exceeds the preview/page safety limit.")
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            if (bytes.size > limit) throw IOException("Google response exceeds the preview/page safety limit.")
            bytes
        }
    }
}
