package gay.benjifox.foxyphotos.provider.icloud

import gay.benjifox.foxyphotos.provider.contract.*
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Secret material has no data-class, debug, or UI representation. */
class ICloudSession internal constructor(@get:JvmSynthetic internal val fields: Map<String, String>) {
    override fun toString() = "ICloudSession(redacted)"
    @JvmSynthetic internal fun encode(): ByteArray = JSONObject(fields).toString().encodeToByteArray()
    @JvmSynthetic internal fun cookies() = fields.getValue("cookies")
    @JvmSynthetic internal fun replacingCookies(value: String) = ICloudSession(fields + ("cookies" to value))
}

object ICloudSessionImport {
    private val supported = setOf("type", "service", "apple_id", "password", "trust_token", "cookies", "client_id", "encoding")

    /** Explicit section and format: password in this format is ALWAYS rclone obscure. */
    @JvmSynthetic
    fun parse(config: String, section: String): ICloudSession {
        require(config.length <= 256 * 1024) { "Config exceeds import limit." }
        require(!config.trimStart().startsWith("RCLONE_ENCRYPT")) { "Encrypted config files are unsupported. Export only the intended remote as plaintext locally." }
        require(section.isNotBlank() && section.none { it == '[' || it == ']' || it == '\n' || it == '\r' }) { "Enter the exact remote section name." }
        var active = false
        var found = false
        val fields = linkedMapOf<String, String>()
        config.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                active = line.substring(1, line.length - 1) == section
                if (active) { require(!found) { "Duplicate selected section." }; found = true }
            } else if (active && line.isNotEmpty() && !line.startsWith("#") && !line.startsWith(";")) {
                val at = line.indexOf('=')
                require(at > 0) { "Invalid selected remote field." }
                val key = line.substring(0, at).trim()
                require(key in supported && !fields.containsKey(key)) { "Unsupported or duplicate selected remote field." }
                fields[key] = line.substring(at + 1).trim()
            }
        }
        require(found) { "Selected remote section was not found." }
        require(fields["type"] == "iclouddrive" && fields["service"] == "photos") { "Select an iclouddrive remote with service = photos." }
        require(!fields["trust_token"].isNullOrBlank()) { "Selected remote needs trust_token." }
        val cookies = fields["cookies"].orEmpty()
        require(cookies.isNotBlank() && cookies.none { it == '\r' || it == '\n' }) { "Expected Cookie-header-style cookies." }
        require(cookies.split(';').filter { it.isNotBlank() }.all {
            val at = it.indexOf('=')
            at > 0 && it.substring(0, at).trim().matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
        }) { "Expected name=value;name=value; cookies, not browser JSON." }
        fields["password"]?.takeIf { it.isNotEmpty() }?.let { fields["password"] = reveal(it) }
        return ICloudSession(fields.filterKeys { it != "type" && it != "service" && it != "encoding" })
    }

    internal fun reveal(value: String): String {
        try {
            require(value.matches(Regex("[A-Za-z0-9_-]+")))
            val bytes = Base64.getUrlDecoder().decode(value)
            require(bytes.size >= 16)
            // rclone's public obscure key. This is compatibility encoding, not secure storage.
            val key = "9c935b48730a554d6bfd7c63c886a92bd390198eb8128afbf4de162b8b95f638"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(bytes.copyOfRange(0,16)))
            val plain = cipher.doFinal(bytes.copyOfRange(16, bytes.size))
            return try { plain.decodeToString(throwOnInvalidSequence = true) } finally { plain.fill(0); bytes.fill(0); key.fill(0) }
        } catch (_: Exception) { throw IllegalArgumentException("Invalid rclone-obscured password. Plaintext passwords are not accepted in config imports.") }
    }
}

/** Replacement commits only after validation; failed import leaves the previous encrypted session intact. */
class ICloudSessionRepository internal constructor(
    private val store: CredentialStore,
    private val validateCandidate: suspend (ICloudSession) -> ICloudSession,
) {
    constructor(store: CredentialStore) : this(store, { candidate ->
        val client = ICloudLibraryClient(candidate)
        client.validate()
        client.currentSession()
    })
    @JvmSynthetic suspend fun load(): ICloudSession? {
        val bytes = store.loadCredential(reference) ?: return null
        return try {
            val json = JSONObject(bytes.decodeToString())
            ICloudSession(json.keys().asSequence().associateWith { json.getString(it) })
        } finally { bytes.fill(0) }
    }
    @JvmSynthetic suspend fun save(session: ICloudSession) {
        val bytes = session.encode()
        try { store.saveCredential(reference, bytes) } finally { bytes.fill(0) }
    }
    @JvmSynthetic suspend fun import(config: String, section: String) {
        val candidate = ICloudSessionImport.parse(config, section)
        save(validateCandidate(candidate))
    }
    companion object { val reference = CredentialReference("icloud-photos-session") }
}
