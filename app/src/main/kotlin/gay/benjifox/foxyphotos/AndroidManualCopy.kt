package gay.benjifox.foxyphotos

import android.content.Context
import gay.benjifox.foxyphotos.core.backup.*
import gay.benjifox.foxyphotos.core.security.AndroidKeystoreCredentialStore
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.google.*
import gay.benjifox.foxyphotos.provider.icloud.*
import kotlinx.coroutines.*
import java.io.File

/** Foreground-screen prototype: one private staging file, <=32 MiB; no iCloud mutation adapter. */
internal class AndroidManualCopy(
    private val context: Context, private val source: ICloudLibraryClient,
    private val credential: GoogleCredential, private val batch: ManualCopyBatch,
    private val allowed: () -> Boolean, private val progress: (String) -> Unit,
) : ManualCopyPort {
    private val uploader = GoogleNativeUploadClient(AppDiagnostics.get(context), singleAttempt = true)
    private val albums = FolderAlbums(context, credential.accountEmail, GoogleLibraryClient({ credential }))
    private val folder = "icloud:${batch.sourceAccount}:${batch.albumId}"
    private var staged: File? = null
    private var info: ICloudOriginalInfo? = null
    override suspend fun stage(sourceId: String): String {
        check(allowed())
        progress("Reading one iCloud original (up to 32 MiB)")
        val meta = source.originalInfo(sourceId)
        require(meta.sizeBytes == null || meta.sizeBytes in 1..32L*1024*1024)
        val bytes = source.read(sourceId, 32*1024*1024)
        try {
            currentCoroutineContext().ensureActive()
            require(bytes.isNotEmpty() && (meta.sizeBytes == null || bytes.size.toLong() == meta.sizeBytes))
            // Never use a provider-supplied name as a local path.
            val dir = File(context.noBackupFilesDir, "copy-staging").apply { mkdirs() }
            val file = File.createTempFile("original-", ".stage", dir)
            staged = file
            file.outputStream().use { it.write(bytes) }
            info = meta
            return java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        } finally { bytes.fill(0) }
    }
    override suspend fun findExisting(sha1: String): String? {
        check(allowed())
        progress("Checking destination presence")
        return uploader.findExisting(credential, sha1.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
    }
    override suspend fun upload(sourceId: String): String {
        check(allowed())
        val file = requireNotNull(staged)
        val metadata = requireNotNull(info)
        progress("Uploading one original to Google")
        val sha1 = file.inputStream().use { input ->
            val digest = java.security.MessageDigest.getInstance("SHA-1")
            val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            digest.digest()
        }
        val result = uploader.upload(GoogleUploadRequest(credential, metadata.name, file.length(),
            (metadata.timestampMillis / 1000).coerceAtLeast(0), sha1,
            fileType = if (metadata.video) UploadFileType.OTHER_VIDEO else UploadFileType.OTHER_IMAGE,
            beforeBytes = { allowed() }, openSource = { file.inputStream() }))
        return when (result) {
            is GoogleUploadOutcome.Committed -> result.mediaKey
            is GoogleUploadOutcome.Present -> result.mediaKey
        }
    }
    override suspend fun organize(sourceId: String, mediaKey: String): CopyOrganization {
        check(allowed())
        progress("Resolving album organization separately from upload")
        val note = albums.associate(folder, setOf(folder), mediaKey, contentIdentity = sourceId,
            requestedTitle = batch.albumTitle, admitted = allowed)
        return CopyOrganization(albums.attempted(folder, sourceId), note)
    }
    override fun releaseStage() { staged?.delete(); staged = null; info = null }
    companion object {
        @JvmSynthetic
        suspend fun credential(context: Context): GoogleCredential = withContext(Dispatchers.IO) {
            val bytes = AndroidKeystoreCredentialStore(context).loadCredential(CredentialReference("google-native"))
                ?: error("Missing Google connection")
            try { GoogleCredentialParser.parse(bytes.decodeToString()) } finally { bytes.fill(0) }
        }
        /** Called under the screen's single-copy owner, before starting any work. */
        fun clearAbandonedStage(context: Context) {
            File(context.noBackupFilesDir, "copy-staging").listFiles()?.forEach { if (it.isFile) it.delete() }
        }
    }
}
