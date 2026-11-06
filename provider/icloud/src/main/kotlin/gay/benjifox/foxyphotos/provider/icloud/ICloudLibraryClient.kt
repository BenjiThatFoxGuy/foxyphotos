package gay.benjifox.foxyphotos.provider.icloud

import gay.benjifox.foxyphotos.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ICloudReauthenticationRequired : IOException("iCloud session rejected or expired. Import a renewed session in Settings.")
class ICloudProtocolException : IOException("iCloud Photos response unsupported or library not ready.")

/** Injectable wire boundary for offline protocol fixtures. Cookies are never sent to media URLs. */
internal fun interface ICloudWire {
    suspend fun send(url: String, json: String?, cookies: String?, limit: Int): ICloudReply
}
internal class ICloudReply(val body: ByteArray, val cookies: List<String> = emptyList())

internal class AppleWire : ICloudWire {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS).build()
    override suspend fun send(url: String, json: String?, cookies: String?, limit: Int): ICloudReply {
        val builder = Request.Builder().url(url)
        if (json != null) builder.post(json.toRequestBody("text/plain".toMediaType()))
        if (cookies != null) builder.header("Cookie", cookies).header("Origin", "https://www.icloud.com")
            .header("Referer", "https://www.icloud.com/")
        val call = http.newCall(builder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(IOException("iCloud network request failed.")) }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (it.code in listOf(401, 403, 421)) throw ICloudReauthenticationRequired()
                            if (!it.isSuccessful) throw IOException("iCloud request failed (HTTP "+it.code+").")
                            val body = it.body ?: throw ICloudProtocolException()
                            if (body.contentLength() > limit) throw IOException("iCloud response exceeds read limit.")
                            val bytes = body.byteStream().use { input ->
                                val out = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                while (out.size() <= limit) {
                                    val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                                    if (n < 0) break
                                    out.write(buffer, 0, n)
                                }
                                if (out.size() > limit) throw IOException("iCloud response exceeds read limit.")
                                out.toByteArray()
                            }
                            ICloudReply(bytes, it.headers.values("Set-Cookie"))
                        }
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            })
        }
    }
}

/** Manual-session-only Photos client. No password sign-in, challenge submission or cloud writes. */
class ICloudLibraryClient internal constructor(
    private var session: ICloudSession,
    private val wire: ICloudWire,
    private val persist: suspend (ICloudSession) -> Unit,
) : AlbumReadOnlyLibrary {
    constructor(session: ICloudSession, persist: suspend (ICloudSession) -> Unit = {}) : this(session, AppleWire(), persist)
    private val lock = Mutex()
    private var endpoint: String? = null
    private var expired = false
    private val albumsById = linkedMapOf<String, Pair<String, String?>>()
    private val resources = linkedMapOf<String, Resources>()
    private val masters = linkedMapOf<String, JSONObject>()
    private var zones: List<String>? = null
    private var pageCalls = 0
    private class Resources(val thumb: String?, val large: String?, val original: String?)
    @JvmSynthetic fun currentSession(): ICloudSession = session

    suspend fun validate() = lock.withLock { validateLocked() }
    private suspend fun validateLocked() {
        if (expired) throw ICloudReauthenticationRequired()
        if (endpoint != null) return
        val info = request("https://setup.icloud.com/setup/ws/1/validate", "")
        if (info.optJSONObject("dsInfo")?.optInt("hsaVersion") == 2 && info.optBoolean("hsaChallengeRequired")) {
            expired = true; throw ICloudReauthenticationRequired()
        }
        val service = info.optJSONObject("webservices")?.optJSONObject("ckdatabasews") ?: throw ICloudProtocolException()
        if (service.optString("status") != "active") throw ICloudProtocolException()
        val url = service.getString("url").toHttpUrl()
        requireApple(url, media = false)
        endpoint = url.toString().trimEnd('/') + "/database/1/com.apple.photos.cloud/production/private"
    }

    private suspend fun request(url: String, json: String): JSONObject {
        if (expired) throw ICloudReauthenticationRequired()
        try {
            val reply = wire.send(url, json, session.cookies(), 8 * 1024 * 1024)
            if (reply.cookies.isNotEmpty()) {
                val pairs = session.cookies().split(';').filter { it.contains('=') }.associate {
                    val at = it.indexOf('='); it.substring(0, at).trim() to it.substring(at + 1).trim()
                }.toMutableMap()
                reply.cookies.forEach { header ->
                    val pair = header.substringBefore(';'); val at = pair.indexOf('=')
                    if (at > 0) {
                        val name = pair.substring(0,at).trim(); val value = pair.substring(at+1)
                        if (value.isEmpty() || header.contains("max-age=0", true)) pairs.remove(name) else pairs[name] = value
                    }
                }
                session = session.replacingCookies(pairs.entries.joinToString(";") { it.key + "=" + it.value } + ";")
                persist(session)
            }
            val obj = JSONObject(reply.body.decodeToString())
            when (obj.optString("serverErrorCode")) {
                "AUTHENTICATION_REQUIRED", "AUTHENTICATION_FAILED", "ACCESS_DENIED" -> throw ICloudReauthenticationRequired()
                "", "OK" -> Unit
                else -> throw ICloudProtocolException()
            }
            return obj
        } catch (e: ICloudReauthenticationRequired) { expired = true; throw e }
    }
    private suspend fun query(body: JSONObject, route: String = "records/query"): JSONObject {
        validateLocked()
        return request(endpoint + "/" + route + "?remapEnums=true&getCurrentSyncToken=true", body.toString())
    }
    private suspend fun discover(): List<String> {
        zones?.let { return it }
        val result = query(JSONObject(), "changes/database")
        if (result.optBoolean("moreComing")) throw IOException("iCloud zone discovery is partial; this prototype cannot continue it.")
        val array = result.getJSONArray("zones")
        if (array.length() > 50) throw IOException("iCloud library limit reached.")
        val found = (0 until array.length()).map { array.getJSONObject(it) }.filter { !it.optBoolean("deleted") }
            .map { it.getJSONObject("zoneID").getString("zoneName") }
        if (found.isEmpty()) throw ICloudProtocolException()
        zones = found
        return found
    }
    private fun zoneBody(zone: String, type: String) = JSONObject().put("zoneID", JSONObject().put("zoneName", zone))
        .put("query", JSONObject().put("recordType", type))
    private fun value(record: JSONObject, name: String): Any? = record.optJSONObject("fields")?.optJSONObject(name)?.opt("value")
    private fun token(zone: String, id: String) = Base64.getUrlEncoder().withoutPadding().encodeToString((zone + "\n" + id).encodeToByteArray())

    override suspend fun albums(cursor: String?): LibraryAlbumPage = lock.withLock {
        val parts = cursor?.split(':')
        require(parts == null || parts.size == 2)
        val zoneIndex = parts?.get(0)?.toInt() ?: 0
        val continuation = parts?.get(1)?.takeIf { it.isNotEmpty() }?.let { String(Base64.getUrlDecoder().decode(it)) }
        val allZones = discover()
        require(zoneIndex in allZones.indices)
        val zone = allZones[zoneIndex]
        val body = zoneBody(zone, "CPLAlbumByPositionLive").put("resultsLimit", 100)
        if (continuation != null) body.put("continuationMarker", continuation)
        val response = query(body)
        val rows = response.getJSONArray("records")
        if (albumsById.size + rows.length() > 1000) throw IOException("Album session limit reached.")
        val list = mutableListOf<LibraryAlbum>()
        if (continuation == null) {
            val id = token(zone, "all")
            albumsById[id] = zone to null
            list += LibraryAlbum(id, "$zone · All Photos")
        }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val id = row.getString("recordName")
            if (id == "----Root-Folder----" || value(row,"isDeleted") == true) continue
            val title = value(row,"albumNameEnc") as? String ?: continue
            val key = token(zone, id)
            albumsById[key] = zone to id
            list += LibraryAlbum(key, String(Base64.getDecoder().decode(title)))
        }
        val next = response.optString("continuationMarker").takeIf { it.isNotEmpty() }?.let {
            "$zoneIndex:" + Base64.getUrlEncoder().withoutPadding().encodeToString(it.encodeToByteArray())
        } ?: if (zoneIndex + 1 < allZones.size) "${zoneIndex+1}:" else null
        LibraryAlbumPage(list, next)
    }
    override suspend fun page(cursor: String?): LibraryPage = lock.withLock {
        val zone = discover().firstOrNull { it == "PrimarySync" } ?: zones!!.first()
        pageLocked(zone, null, cursor)
    }
    override suspend fun albumPage(albumId: String, cursor: String?): LibraryPage = lock.withLock {
        val album = albumsById[albumId] ?: throw IllegalArgumentException("Choose a loaded album first.")
        pageLocked(album.first, album.second, cursor)
    }
    private suspend fun pageLocked(zone: String, album: String?, cursor: String?): LibraryPage {
        if (pageCalls >= 50) throw IOException("iCloud session request limit reached. Reopen the library.")
        val offset = cursor?.toInt() ?: 0
        require(offset in 0..5000)
        val filters = JSONArray().put(filter("startRank", "INT64", offset)).put(filter("direction", "STRING", "ASCENDING"))
        if (album != null) filters.put(filter("parentId", "STRING", album))
        val type = if (album == null) "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted" else "CPLContainerRelationLiveByAssetDate"
        val body = zoneBody(zone, type).put("resultsLimit", 200)
        body.getJSONObject("query").put("filterBy", filters)
        body.put("desiredKeys", JSONArray(listOf("masterRef", "assetDate", "resJPEGThumbRes", "resJPEGMedRes", "resJPEGLargeRes", "resJPEGFullRes", "resOriginalRes", "resVidFullRes")))
        val response = query(body)
        pageCalls++
        val rows = response.getJSONArray("records")
        val assets = mutableListOf<JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            when (row.optString("recordType")) {
                "CPLMaster" -> masters[token(zone,row.getString("recordName"))] = row
                "CPLAsset" -> assets += row
            }
        }
        val items = assets.map { asset ->
            val masterId = (value(asset,"masterRef") as? JSONObject)?.getString("recordName") ?: throw ICloudProtocolException()
            val master = masters[token(zone,masterId)] ?: throw ICloudProtocolException()
            fun resource(key: String): String? = ((value(asset,key) ?: value(master,key)) as? JSONObject)?.optString("downloadURL")?.takeIf { it.isNotEmpty() }
            val key = token(zone,asset.getString("recordName"))
            resources[key] = Resources(resource("resJPEGThumbRes") ?: resource("resJPEGMedRes"),
                resource("resJPEGLargeRes") ?: resource("resJPEGFullRes") ?: resource("resJPEGMedRes"), resource("resOriginalRes") ?: resource("resVidFullRes"))
            LibraryItem(key, (value(asset,"assetDate") as? Number)?.toLong() ?: 0L, value(master,"resVidFullRes") != null)
        }
        if (resources.size > 5000 || masters.size > 5000) throw IOException("iCloud session item limit reached.")
        return LibraryPage(items, if (assets.isEmpty()) null else (offset + assets.size).toString())
    }
    private fun filter(name: String, type: String, value: Any) = JSONObject().put("fieldName", name).put("comparator", "EQUALS")
        .put("fieldValue", JSONObject().put("type", type).put("value", value))
    override suspend fun preview(id: String, large: Boolean): ByteArray = media(id, if (large) 8*1024*1024 else 2*1024*1024) {
        if (large) it.large ?: it.thumb else it.thumb ?: it.large
    }
    override suspend fun read(id: String, maxBytes: Int): ByteArray {
        require(maxBytes in 1..32*1024*1024)
        return media(id, maxBytes) { it.original }
    }
    private suspend fun media(id: String, limit: Int, choose: (Resources) -> String?): ByteArray {
        val url = lock.withLock {
            if (expired) throw ICloudReauthenticationRequired()
            choose(resources[id] ?: throw ICloudProtocolException()) ?: throw IOException("Requested iCloud rendition is unavailable.")
        }.toHttpUrl()
        requireApple(url, media = true)
        // Signed resource URL is itself authority; never leak imported cookies to a CDN.
        return try { wire.send(url.toString(), null, null, limit).body }
        catch (_: ICloudReauthenticationRequired) { throw IOException("iCloud preview URL expired. Refresh the library; replace session if requested.") }
    }
    private fun requireApple(url: HttpUrl, media: Boolean) {
        val domains = if (media) listOf("icloud.com", "icloud-content.com", "apple.com") else listOf("icloud.com")
        require(url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            domains.any { url.host == it || url.host.endsWith("."+it) }) { "Unsupported iCloud endpoint." }
    }
}
