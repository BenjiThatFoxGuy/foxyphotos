package gay.benjifox.foxyphotos.provider.google

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class GoogleAlbumListing(val albums: List<GoogleAlbum>?, val issue: String?, val syncToken: String? = null)
data class GoogleAlbumListingResult(val albums: List<GoogleAlbum>, val syncToken: String?)

/** Shared Google album protocol. HTTP acknowledgement is not membership confirmation. */
class GoogleAlbumBridge(private val transport: GoogleHttpTransport) {
    private val provider = GoogleUploadSequence(transport)
    private val libraryEndpoint = "https://photosdata-pa.googleapis.com/6439526531001121323/18047484249733410717"
    private val createEndpoint = "https://photosdata-pa.googleapis.com/6439526531001121323/8386163679468898444"
    private val addEndpoint = "https://photosdata-pa.googleapis.com/6439526531001121323/484917746253879292"

    @Throws(Exception::class)
    suspend fun list(credentialForm: String): List<GoogleAlbum> = listDetailed(credentialForm).albums

    /**
     * Full album library. Pass a previously captured sync token to refresh only
     * what changed; a null token pulls every page.
     */
    @Throws(Exception::class)
    suspend fun listDetailed(credentialForm: String, syncToken: String? = null,
                             onPage: ((Int, Int) -> Unit)? = null): GoogleAlbumListingResult {
        val auth = provider.authenticate(GoogleCredentialParser.parse(credentialForm))
        val albums = mutableListOf<GoogleAlbum>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var latestSyncToken = syncToken
        var pageIndex = 0
        repeat(10_000) {
            val body = if (cursor == null && syncToken != null) {
                GoogleAlbumListRequest.syncRequest(syncToken)
            } else {
                GoogleAlbumListRequest.request(cursor)
            }
            val page = GoogleAlbumProtocol.decode(postAuthorized(auth, libraryEndpoint, body))
            albums += page.albums
            if (!page.syncToken.isNullOrEmpty()) latestSyncToken = page.syncToken
            require(albums.size <= 5000) { "Album count exceeds safety limit." }
            pageIndex += 1
            // Page progress is the only honest signal: a full library is many
            // pages and the caller needs to show that it is moving.
            onPage?.invoke(pageIndex, albums.size)
            cursor = page.nextCursor
            if (cursor == null) {
                require(albums.groupBy { it.id }.values.all { entries -> entries.distinct().size == 1 })
                return GoogleAlbumListingResult(albums.distinctBy { it.id }, latestSyncToken)
            }
            require(cursors.add(cursor!!)) { "Repeated album page." }
        }
        throw GoogleUploadSequenceException("Album page limit reached.")
    }

    /** A bounded, credential-free failure reason for the device status panel. */
    suspend fun listDiagnosed(credentialForm: String): GoogleAlbumListing =
        listDiagnosed(credentialForm, null)

    @Throws(Exception::class)
    suspend fun listDiagnosed(credentialForm: String, syncToken: String?): GoogleAlbumListing =
        listDiagnosedWithin(credentialForm, 45_000, syncToken)

    internal suspend fun listDiagnosedWithin(credentialForm: String, timeoutMillis: Long, syncToken: String? = null): GoogleAlbumListing = try {
        val result = withTimeout(timeoutMillis) { listDetailed(credentialForm, syncToken) }
        GoogleAlbumListing(result.albums, null, result.syncToken)
    } catch (_: TimeoutCancellationException) {
        GoogleAlbumListing(null, "Google album listing timed out")
    } catch (e: CancellationException) {
        throw e
    } catch (e: GoogleUploadSequenceException) {
        val message = e.message.orEmpty().take(200)
        GoogleAlbumListing(null, when {
            message.startsWith("Provider returned HTTP ") -> "Google listing ${message.removeSuffix(".")}"
            message.isNotBlank() -> "Google listing $message"
            else -> "Google listing request failed"
        })
    } catch (_: Exception) {
        GoogleAlbumListing(null, "Google listing response could not be decoded")
    }

    @Throws(Exception::class)
    suspend fun create(credentialForm: String, title: String, initialMediaKey: String, nowSeconds: Long): String {
        require(title.isNotBlank() && title.length <= 500 && initialMediaKey.isNotBlank())
        // Use the same create-with-first-member protocol as Android.
        val response = post(credentialForm, createEndpoint,
                            GoogleAlbumProtocol.create(title, nowSeconds, initialMediaKey))
        return GoogleAlbumProtocol.createdId(response)
    }

    @Throws(Exception::class)
    suspend fun add(credentialForm: String, albumId: String, mediaKey: String, nowSeconds: Long) {
        post(credentialForm, addEndpoint, GoogleAlbumProtocol.add(albumId, mediaKey, nowSeconds))
    }

    fun hasCapacity(album: GoogleAlbum?, reserved: Int): Boolean =
        album?.count?.let { it >= 0 && reserved >= 0 && it < 20_000L - reserved } ?: false

    private suspend fun post(credentialForm: String, endpoint: String, body: ByteArray): ByteArray =
        postAuthorized(provider.authenticate(GoogleCredentialParser.parse(credentialForm)), endpoint, body)

    private suspend fun postAuthorized(auth: String, endpoint: String, body: ByteArray): ByteArray {
        return provider.send(GoogleHttpRequest(endpoint, "POST", mapOf(
            "Authorization" to auth,
            "User-Agent" to "Google Photos/49029607 (Pixel XL; Android 9)",
            "Accept-Language" to "en-US",
            "Content-Type" to "application/x-protobuf",
            "X-Goog-Ext-173412678-Bin" to "CgcIAhClARgC",
            "X-Goog-Ext-174067345-Bin" to "CgIIAg==",
        ), body)).body
    }
}
