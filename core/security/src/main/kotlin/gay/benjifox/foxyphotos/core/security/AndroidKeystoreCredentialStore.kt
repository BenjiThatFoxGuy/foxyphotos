package gay.benjifox.foxyphotos.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import gay.benjifox.foxyphotos.provider.contract.CredentialReference
import gay.benjifox.foxyphotos.provider.contract.CredentialStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeystoreCredentialStore(
    context: Context,
) : CredentialStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun hasCredential(reference: CredentialReference): Boolean =
        preferences.contains(ciphertextKey(reference)) && preferences.contains(ivKey(reference))

    override suspend fun saveCredential(reference: CredentialReference, secret: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, encryptionKey())
        }
        val ciphertext = cipher.doFinal(secret)
        preferences.edit()
            .putString(ciphertextKey(reference), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(ivKey(reference), Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
    }

    override suspend fun loadCredential(reference: CredentialReference): ByteArray? {
        val encodedCiphertext = preferences.getString(ciphertextKey(reference), null) ?: return null
        val encodedIv = preferences.getString(ivKey(reference), null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                javax.crypto.spec.GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)),
            )
        }
        return cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP))
    }

    override suspend fun deleteCredential(reference: CredentialReference) {
        preferences.edit()
            .remove(ciphertextKey(reference))
            .remove(ivKey(reference))
            .commit()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun ciphertextKey(reference: CredentialReference): String = "credential.${reference.value}.ciphertext"

    private fun ivKey(reference: CredentialReference): String = "credential.${reference.value}.iv"

    private companion object {
        const val PREFERENCES_NAME = "foxyphotos.credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "foxyphotos.credentials.aes-gcm.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
