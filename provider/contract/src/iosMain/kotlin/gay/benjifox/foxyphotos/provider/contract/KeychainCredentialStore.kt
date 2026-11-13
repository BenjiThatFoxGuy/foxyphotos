package gay.benjifox.foxyphotos.provider.contract

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.create
import platform.Foundation.setValue
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

/**
 * iOS Keychain-backed [CredentialStore].
 *
 * Each credential is stored as a kSecClassGenericPassword item keyed by
 * [CredentialReference.value] as the account. The service is fixed so all
 * FoxyPhotos credentials share one namespace. The Keychain itself provides
 * hardware-backed encryption (Secure Enclave on supported devices).
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainCredentialStore : CredentialStore {

    override suspend fun hasCredential(reference: CredentialReference): Boolean {
        val query = baseQuery(reference).apply {
            setValue(kSecMatchLimitOne, forKey = kSecMatchLimit)
        }
        val status = SecItemCopyMatching(query, null)
        return status == errSecSuccess
    }

    override suspend fun saveCredential(reference: CredentialReference, secret: ByteArray) {
        val data = secret.toNSData()
        // Try update; if missing, add.
        val query = baseQuery(reference)
        val attrs = NSMutableDictionary().apply {
            setValue(data, forKey = kSecValueData)
        }
        val updateStatus = SecItemUpdate(query, attrs)
        if (updateStatus == errSecItemNotFound) {
            query.setValue(data, forKey = kSecValueData)
            val addStatus = SecItemAdd(query, null)
            check(addStatus == errSecSuccess) {
                "Keychain add failed: $addStatus"
            }
        } else {
            check(updateStatus == errSecSuccess) {
                "Keychain update failed: $updateStatus"
            }
        }
    }

    override suspend fun loadCredential(reference: CredentialReference): ByteArray? {
        val query = baseQuery(reference).apply {
            setValue(true, forKey = kSecReturnData)
            setValue(kSecMatchLimitOne, forKey = kSecMatchLimit)
        }
        memScoped {
            val result = alloc<platform.darwin.CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return null
            check(status == errSecSuccess) {
                "Keychain load failed: $status"
            }
            val data = result.value as? NSData ?: return null
            return data.toByteArray()
        }
    }

    override suspend fun deleteCredential(reference: CredentialReference) {
        val query = baseQuery(reference)
        val status = SecItemDelete(query)
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "Keychain delete failed: $status"
        }
    }

    private fun baseQuery(reference: CredentialReference): NSMutableDictionary {
        return NSMutableDictionary().apply {
            setValue(kSecClassGenericPassword, forKey = kSecClass)
            setValue(SERVICE_NAME, forKey = kSecAttrService)
            setValue(reference.value, forKey = kSecAttrAccount)
        }
    }

    private companion object {
        const val SERVICE_NAME = "gay.benjifox.foxyphotos.credentials"
    }
}

/** Convert a Kotlin ByteArray to NSData. */
@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

/** Convert NSData back to a Kotlin ByteArray. */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val len = length.toInt()
    if (len == 0) return ByteArray(0)
    return ByteArray(len).also { bytes ->
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), this.bytes, this.length)
        }
    }
}
