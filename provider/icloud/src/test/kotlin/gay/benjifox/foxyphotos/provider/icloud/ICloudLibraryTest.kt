package gay.benjifox.foxyphotos.provider.icloud

import gay.benjifox.foxyphotos.provider.contract.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class ICloudLibraryTest {
    private val config = """
        [unrelated]
        type = other
        password = never-ingest-this
        [photos]
        type = iclouddrive
        service = photos
        trust_token = fixture-trust
        cookies = first=one;second=two==;
        client_id = fixture-client
        encoding = Slash
    """.trimIndent()
    private fun session() = ICloudSessionImport.parse(config, "photos")
    private fun reply(json: String, cookies: List<String> = emptyList()) = ICloudReply(json.encodeToByteArray(), cookies)
    private val validation = """{"dsInfo":{"hsaVersion":2},"hsaChallengeRequired":false,"webservices":{"ckdatabasews":{"status":"active","url":"https://p01-ckdatabasews.icloud.com"}}}"""
    private val zones = """{"zones":[{"zoneID":{"zoneName":"PrimarySync"}},{"zoneID":{"zoneName":"SharedSync-fixture"}}]}"""

    @Test fun `explicit section keeps compatible fields and never exposes secrets`() {
        val s = session()
        assertEquals(setOf("trust_token", "cookies", "client_id"), s.fields.keys)
        assertEquals("first=one;second=two==;", s.cookies())
        assertEquals("ICloudSession(redacted)", s.toString())
        listOf(config.replace("service = photos", "service = drive"), config.replace("first=one;second=two==;", "[{\"name\":\"first\"}]"),
            "RCLONE_ENCRYPT_V0:\nfixture", config + "\nunknown = unsafe").forEach {
            try { ICloudSessionImport.parse(it, "photos"); fail("Invalid config accepted") } catch (_: IllegalArgumentException) { }
        }
        try { ICloudSessionImport.parse(config, "missing"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `rclone obscure uses URL base64 IV and AES CTR without plaintext guessing`() {
        // Fixed interoperability vectors from pinned rclone obscure_test.go.
        listOf("YWFhYWFhYWFhYWFhYWFhYXMaGgIlEQ", "YmJiYmJiYmJiYmJiYmJiYp3gcEWbAw").forEach { obscured ->
            assertEquals("potato", ICloudSessionImport.parse(config + "\npassword = " + obscured, "photos").fields["password"])
        }
        try { ICloudSessionImport.parse(config + "\npassword = plaintext", "photos"); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `rejected replacement keeps prior encrypted-store payload`() = runBlocking {
        var stored = "previous-session".encodeToByteArray()
        val store = object : CredentialStore {
            override suspend fun hasCredential(reference: CredentialReference) = true
            override suspend fun loadCredential(reference: CredentialReference) = stored.copyOf()
            override suspend fun saveCredential(reference: CredentialReference, secret: ByteArray) { stored = secret.copyOf() }
            override suspend fun deleteCredential(reference: CredentialReference) { fail("Must not delete") }
        }
        val rejected = ICloudSessionRepository(store) { throw ICloudReauthenticationRequired() }
        try { rejected.importSession(config,"photos"); fail() } catch (_: ICloudReauthenticationRequired) { }
        assertEquals("previous-session", stored.decodeToString())
        val accepted = ICloudSessionRepository(store) { it.replacingCookies("updated=fixture;") }
        accepted.importSession(config,"photos")
        assertEquals("updated=fixture;", accepted.load()!!.cookies())
        assertFalse(stored.decodeToString().contains("unrelated"))
    }
    @Test fun `validation first cookie rotation and asset-count pagination preserve shared masters`() = runBlocking {
        val calls = mutableListOf<String>()
        var persisted: ICloudSession? = null
        var page = 0
        val wire = ICloudWire { url, body, cookie, _ ->
            calls += url
            when {
                url.endsWith("/validate") -> { assertEquals("", body); assertNotNull(cookie); reply(validation, listOf("first=rotated; Path=/; Secure")) }
                url.contains("changes/database") -> reply(zones)
                url.contains("records/query") -> {
                    val query = JSONObject(body!!).getJSONObject("query")
                    val offset = query.getJSONArray("filterBy").getJSONObject(0).getJSONObject("fieldValue").getInt("value")
                    if (page++ == 0) {
                        assertEquals(0,offset)
                        reply("""{"records":[
                          {"recordType":"CPLMaster","recordName":"master","fields":{"resJPEGThumbRes":{"value":{"downloadURL":"https://cvws.icloud-content.com/preview"}},"resOriginalRes":{"value":{"downloadURL":"https://cvws.icloud-content.com/original"}}}},
                          {"recordType":"CPLAsset","recordName":"asset1","fields":{"assetDate":{"value":123},"masterRef":{"value":{"recordName":"master"}}}},
                          {"recordType":"CPLAsset","recordName":"asset2","fields":{"assetDate":{"value":456},"masterRef":{"value":{"recordName":"master"}}}}
                        ]}""")
                    } else { assertEquals(2,offset); reply("""{"records":[]}""") }
                }
                else -> { assertNull(cookie); assertNull(body); reply("fixture-image") }
            }
        }
        val client = ICloudLibraryClient(session(),wire) { persisted = it }
        val first = client.page()
        assertEquals(2,first.items.size)
        assertNotEquals(first.items[0].id,first.items[1].id)
        assertEquals("2",first.nextCursor)
        assertEquals(456L,first.items[1].timestampMillis)
        assertTrue(calls.first().endsWith("/validate"))
        assertEquals("first=rotated;second=two==;",persisted!!.cookies())
        assertEquals("fixture-image",client.preview(first.items.first().id).decodeToString())
        assertEquals("fixture-image",client.read(first.items.first().id,1024).decodeToString())
        assertNull(client.page(first.nextCursor).nextCursor)
        assertEquals(1,calls.count { it.endsWith("/validate") })
    }
    @Test fun `expired sessions latch while network failures remain retryable`() = runBlocking {
        var calls = 0
        val expired = ICloudLibraryClient(session(),ICloudWire { _,_,_,_ -> calls++; throw ICloudReauthenticationRequired() }) {}
        repeat(2) { try { expired.page(); fail() } catch (_: ICloudReauthenticationRequired) {} }
        assertEquals(1,calls)
        calls = 0
        val network = ICloudLibraryClient(session(),ICloudWire { _,_,_,_ -> calls++; throw IOException("fixture") }) {}
        repeat(2) { try { network.page(); fail() } catch (_: IOException) {} }
        assertEquals(2,calls)
    }
    @Test fun `album discovery pages zones and uses confirmed record identity`() = runBlocking {
        var selected = false
        val client = ICloudLibraryClient(session(), ICloudWire { url,body,_,_ ->
            when {
                url.endsWith("/validate") -> reply(validation)
                url.contains("changes/database") -> reply(zones)
                else -> {
                    val query = JSONObject(body!!).getJSONObject("query")
                    if (query.getString("recordType") == "CPLAlbumByPositionLive") reply("""{"records":[{"recordName":"album-fixture","fields":{"albumNameEnc":{"value":"VHJpcA=="}}}]}""")
                    else {
                        selected = true
                        assertEquals("CPLContainerRelationLiveByAssetDate",query.getString("recordType"))
                        assertEquals("album-fixture",query.getJSONArray("filterBy").getJSONObject(2).getJSONObject("fieldValue").getString("value"))
                        reply("""{"records":[]}""")
                    }
                }
            }
        }) {}
        val first = client.albums()
        assertEquals("Trip",first.albums[1].title)
        assertNotNull(first.nextCursor)
        val second = client.albums(first.nextCursor)
        assertNotEquals(first.albums[1].id,second.albums[1].id)
        assertNull(second.nextCursor)
        client.albumPage(first.albums[1].id)
        assertTrue(selected)
    }
    @Test fun `untrusted discovered service never receives cookies`() = runBlocking {
        var calls = 0
        val client = ICloudLibraryClient(session(), ICloudWire { _,_,_,_ -> calls++; reply(validation.replace("p01-ckdatabasews.icloud.com","icloud.com.attacker.test")) }) {}
        try { client.page(); fail() } catch (_: IllegalArgumentException) {}
        assertEquals(1,calls)
    }
}
