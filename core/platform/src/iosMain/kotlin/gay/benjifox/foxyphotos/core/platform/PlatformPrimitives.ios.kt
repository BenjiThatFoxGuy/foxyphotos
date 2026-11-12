package gay.benjifox.foxyphotos.core.platform

import kotlinx.cinterop.Arena
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256_CTX
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256_Final
import platform.CoreCrypto.CC_SHA256_Init
import platform.CoreCrypto.CC_SHA256_Update
import platform.Foundation.NSDate

actual fun currentTimeMillis(): Long = NSDate().timeIntervalSince1970.times(1000).toLong()

actual fun formatEpochMillisIso(millis: Long): String = iso8601FromEpochMillis(millis)

actual fun formatCurrentTimeIso(): String = formatEpochMillisIso(currentTimeMillis())

actual class Sha256Digest actual constructor() {
    private val arena = Arena()
    private val context = arena.alloc<CC_SHA256_CTX>()

    init {
        CC_SHA256_Init(context.ptr)
    }

    actual fun update(buffer: ByteArray, offset: Int, count: Int) {
        buffer.usePinned { pinned ->
            CC_SHA256_Update(context.ptr, pinned.addressOf(offset), count.convert())
        }
    }

    actual fun digestHex(): String {
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        digest.usePinned { pinned ->
            CC_SHA256_Final(pinned.addressOf(0), context.ptr)
        }
        return digest.joinToString("") { it.toInt().toString(16).padStart(2, '0') }
    }

    actual fun close() {
        arena.free()
    }
}
