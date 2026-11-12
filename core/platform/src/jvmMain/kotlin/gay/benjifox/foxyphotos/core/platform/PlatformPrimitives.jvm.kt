package gay.benjifox.foxyphotos.core.platform

import java.security.MessageDigest

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatEpochMillisIso(millis: Long): String = java.time.Instant.ofEpochMilli(millis).toString()

actual fun formatCurrentTimeIso(): String = formatEpochMillisIso(currentTimeMillis())

actual class Sha256Digest actual constructor() {
    private val delegate = MessageDigest.getInstance("SHA-256")

    actual fun update(buffer: ByteArray, offset: Int, count: Int) {
        delegate.update(buffer, offset, count)
    }

    actual fun digestHex(): String =
        delegate.digest().joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

    actual fun close() {
        // MessageDigest holds no native resources.
    }
}
