package app.feelings.tracker.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class WrappedSecret(
    val alias: String,
    val iv: ByteArray,
    val ciphertext: ByteArray,
)

class KeystoreKeyWrapper(
    private val keyStoreType: String = ANDROID_KEYSTORE,
) {
    fun wrap(plaintext: ByteArray, alias: String): WrappedSecret {
        val key = getOrCreate(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(plaintext)
        return WrappedSecret(alias, cipher.iv, ciphertext)
    }

    fun unwrap(wrapped: WrappedSecret): ByteArray {
        val key = existing(wrapped.alias) ?: throw KeyUnwrapException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
        return cipher.doFinal(wrapped.ciphertext)
    }

    fun delete(alias: String) {
        val store = load()
        if (store.containsAlias(alias)) {
            store.deleteEntry(alias)
        }
    }

    fun exists(alias: String): Boolean = load().containsAlias(alias)

    private fun existing(alias: String): SecretKey? {
        val store = load()
        if (!store.containsAlias(alias)) return null
        return store.getKey(alias, null) as? SecretKey
    }

    private fun getOrCreate(alias: String): SecretKey {
        existing(alias)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun load(): KeyStore = KeyStore.getInstance(keyStoreType).apply { load(null) }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}

class KeyUnwrapException : Exception()
