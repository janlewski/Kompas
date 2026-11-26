package app.kompas.export

import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretStream
import com.sun.jna.NativeLong
import java.security.SecureRandom

class SodiumExportCipher(
    private val random: SecureRandom = SecureRandom(),
) : ExportCipher {
    private val sodium = SodiumAndroid()

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    override fun deriveKey(
        passphrase: ByteArray,
        salt: ByteArray,
        opslimit: Int,
        memlimitBytes: Long,
    ): ByteArray {
        val output = ByteArray(SecretStream.KEYBYTES)
        val ok = sodium.crypto_pwhash(
            output,
            output.size.toLong(),
            passphrase,
            passphrase.size.toLong(),
            salt,
            opslimit.toLong(),
            NativeLong(memlimitBytes),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13.value,
        )
        if (ok != 0) {
            output.fill(0)
            error("kdf")
        }
        return output
    }

    override fun encrypt(
        key: ByteArray,
        chunks: List<ByteArray>,
        additionalDataFor: (streamHeader: ByteArray) -> ByteArray,
    ): EncryptedPayload {
        val state = SecretStream.State()
        val streamHeader = ByteArray(SecretStream.HEADERBYTES)
        check(sodium.crypto_secretstream_xchacha20poly1305_init_push(state, streamHeader, key) == 0)
        val additional = additionalDataFor(streamHeader)
        val encrypted = chunks.mapIndexed { index, plain ->
            val tag = if (index == chunks.lastIndex) SecretStream.TAG_FINAL else SecretStream.TAG_MESSAGE
            val ad = if (index == 0) additional else ByteArray(0)
            CipherChunk(push(state, plain, ad, tag))
        }
        additional.fill(0)
        return EncryptedPayload(streamHeader, encrypted)
    }

    override fun decrypt(key: ByteArray, payload: EncryptedPayload, additionalData: ByteArray): DecryptOutcome {
        val state = SecretStream.State()
        if (sodium.crypto_secretstream_xchacha20poly1305_init_pull(state, payload.streamHeader, key) != 0) {
            return DecryptOutcome.AuthFailed
        }
        val plain = java.io.ByteArrayOutputStream()
        var sawFinal = false
        payload.chunks.forEachIndexed { index, chunk ->
            if (sawFinal) return DecryptOutcome.Trailing
            val ad = if (index == 0) additionalData else ByteArray(0)
            val pulled = pull(state, chunk.ciphertext, ad) ?: return DecryptOutcome.AuthFailed
            if (pulled.finalChunk) sawFinal = true
            plain.write(pulled.bytes)
        }
        return if (sawFinal) DecryptOutcome.Ok(plain.toByteArray()) else DecryptOutcome.MissingFinal
    }

    private fun push(state: SecretStream.State, message: ByteArray, ad: ByteArray?, tag: Byte): ByteArray {
        val cipher = ByteArray(message.size + SecretStream.ABYTES)
        val written = LongArray(1)
        val ok = sodium.crypto_secretstream_xchacha20poly1305_push(
            state,
            cipher,
            written,
            message,
            message.size.toLong(),
            ad,
            (ad?.size ?: 0).toLong(),
            tag,
        )
        check(ok == 0)
        val length = written[0].toInt().takeIf { it in 1..cipher.size } ?: cipher.size
        return cipher.copyOf(length)
    }

    private fun pull(state: SecretStream.State, cipher: ByteArray, ad: ByteArray?): Pulled? {
        val message = ByteArray((cipher.size - SecretStream.ABYTES).coerceAtLeast(0))
        val written = LongArray(1)
        val tag = ByteArray(1)
        val ok = sodium.crypto_secretstream_xchacha20poly1305_pull(
            state,
            message,
            written,
            tag,
            cipher,
            cipher.size.toLong(),
            ad,
            (ad?.size ?: 0).toLong(),
        )
        if (ok != 0) return null
        val length = written[0].toInt().coerceIn(0, message.size)
        return Pulled(message.copyOf(length), tag[0] == SecretStream.TAG_FINAL)
    }

    private data class Pulled(val bytes: ByteArray, val finalChunk: Boolean)
}
