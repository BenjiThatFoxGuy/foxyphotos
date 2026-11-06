package gay.benjifox.foxyphotos.provider.google

import gay.benjifox.foxyphotos.core.model.DiagnosticEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** correlation is random and unrelated to any media or account identifier. */
class UploadCorrelationId private constructor(private val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun create() = UploadCorrelationId(UUID.randomUUID())
        internal fun restore(text: String): UploadCorrelationId {
            require(text.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
            return UploadCorrelationId(UUID.fromString(text))
        }
    }
}

enum class UploadStage { UPLOAD, AUTH, HASH_CHECK, UPLOAD_TOKEN, MEDIA_BYTES, COMMIT }
enum class UploadAction { START, HTTP_RESPONSE, RETRY, SUCCESS, PRESENT, FAILURE }
enum class UploadFileType {
    JPEG, PNG, GIF, WEBP, HEIC, AVIF, DNG, MP4, MOV, OTHER_IMAGE, OTHER_VIDEO, OTHER;

    companion object {
        fun fromMimeType(mime: String?): UploadFileType = when (mime?.lowercase(java.util.Locale.ROOT)) {
            "image/jpeg" -> JPEG
            "image/png" -> PNG
            "image/gif" -> GIF
            "image/webp" -> WEBP
            "image/heic", "image/heif" -> HEIC
            "image/avif" -> AVIF
            "image/x-adobe-dng" -> DNG
            "video/mp4" -> MP4
            "video/quicktime" -> MOV
            else -> when {
                mime?.startsWith("image/", ignoreCase = true) == true -> OTHER_IMAGE
                mime?.startsWith("video/", ignoreCase = true) == true -> OTHER_VIDEO
                else -> OTHER
            }
        }
    }
}
enum class UploadFailure { NONE, NETWORK, HTTP, PROTOCOL, INVALID_INPUT, UNCERTAIN, CANCELLED, OTHER }

data class UploadDiagnosticEvent(
    val correlationId: UploadCorrelationId,
    val stage: UploadStage,
    val action: UploadAction,
    val attempt: Int = 1,
    val httpStatus: Int? = null,
    val fileType: UploadFileType = UploadFileType.OTHER,
    val sizeBytes: Long = 0,
    val failure: UploadFailure = UploadFailure.NONE,
    val timestampMillis: Long = System.currentTimeMillis(),
    val responseShape: CommitResponseShape? = null,
) {
    init {
        require(attempt in 1..100 && (httpStatus == null || httpStatus in 100..599))
        require(sizeBytes >= 0 && timestampMillis >= 0)
    }

    fun render(): String = "${java.time.Instant.ofEpochMilli(timestampMillis)} upload=$correlationId " +
        "stage=${stage.name.lowercase()} action=${action.name.lowercase()} attempt=$attempt " +
        "http=${httpStatus ?: "none"} type=${fileType.name.lowercase()} bytes=$sizeBytes failure=${failure.name.lowercase()}" +
        (responseShape?.let { " commit_wire=${it.encoded}" } ?: "")

    internal fun encode(): String = listOf(timestampMillis, correlationId, stage, action, attempt,
        httpStatus ?: 0, fileType, sizeBytes, failure, responseShape?.encoded ?: "-").joinToString("|")

    companion object {
        internal fun decode(line: String): UploadDiagnosticEvent {
            val parts = line.split('|')
            require(parts.size == 10)
            return UploadDiagnosticEvent(UploadCorrelationId.restore(parts[1]), UploadStage.valueOf(parts[2]),
                UploadAction.valueOf(parts[3]), parts[4].toInt(), parts[5].toInt().takeUnless { it == 0 },
                UploadFileType.valueOf(parts[6]), parts[7].toLong(), UploadFailure.valueOf(parts[8]), parts[0].toLong(),
                parts[9].takeUnless { it == "-" }?.let(CommitResponseShape::restore))
        }
    }
}

fun interface UploadDiagnosticSink {
    fun record(event: UploadDiagnosticEvent)
}

/** call on an io dispatcher. only typed, validated records are ever read back or exported. */
class UploadDiagnosticLog(
    private val file: File,
    private val maxEvents: Int = 400,
    private val maxBytes: Int = 256 * 1024,
) : UploadDiagnosticSink {
    private val events = ArrayDeque<Any>()
    @Volatile var storageHealthy: Boolean = true
        private set

    init {
        require(maxEvents in 1..1000 && maxBytes in 1024..1024 * 1024)
        try {
            if (file.exists()) {
                require(file.length() <= maxBytes)
                val lines = file.readText(Charsets.UTF_8).lines()
                require(lines.firstOrNull() == HEADER)
                lines.drop(1).filter(String::isNotEmpty).forEach { events.addLast(if (it.startsWith("op|")) DiagnosticEvent.decode(it) else UploadDiagnosticEvent.decode(it)) }
                trim()
            }
        } catch (_: Exception) {
            events.clear()
            storageHealthy = false
            // discard corrupt storage rather than displaying untrusted strings.
            persist()
        }
    }

    @Synchronized override fun record(event: UploadDiagnosticEvent) {
        events.addLast(event)
        trim()
        persist()
    }

    @Synchronized fun snapshot(): List<UploadDiagnosticEvent> = events.filterIsInstance<UploadDiagnosticEvent>()

    @Synchronized fun record(event: DiagnosticEvent) { events.addLast(event); trim(); persist() }
    @Synchronized fun renderedSnapshot(): List<String> = events.map { when (it) {
        is DiagnosticEvent -> it.render()
        is UploadDiagnosticEvent -> it.render()
        else -> error("Unsupported diagnostic")
    } }

    @Synchronized fun clear() {
        events.clear()
        persist()
    }

    private fun encoded(): String = HEADER + "\n" + events.joinToString("\n", postfix = if (events.isEmpty()) "" else "\n") { when (it) { is DiagnosticEvent -> it.encode(); is UploadDiagnosticEvent -> it.encode(); else -> error("Unsupported diagnostic") } }

    private fun trim() {
        while (events.size > maxEvents || encoded().toByteArray(Charsets.UTF_8).size > maxBytes) events.removeFirst()
    }

    private fun persist() {
        val temporary = File(file.parentFile, "${file.name}.pending")
        try {
            file.parentFile?.mkdirs()
            temporary.outputStream().use { stream ->
                stream.write(encoded().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            storageHealthy = true
        } catch (_: Exception) {
            storageHealthy = false
        } finally {
            try { temporary.delete() } catch (_: Exception) { /* diagnostic storage is best effort */ }
        }
    }

    private companion object { const val HEADER = "foxyphotos-diagnostics-v1" }
}
