package app.feelings.tracker.export

import app.feelings.tracker.core.result.BackupProblem
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class BackupHeader(
    val formatVersion: Int,
    val kdfId: Int,
    val aeadId: Int,
    val opslimit: Int,
    val memlimitKiB: Int,
    val salt: ByteArray,
    val streamHeader: ByteArray,
)

sealed class HeaderParse {
    data class Ok(val header: BackupHeader) : HeaderParse()

    data class Rejected(val problem: BackupProblem) : HeaderParse()
}

object BackupFormat {
    const val MAGIC_0 = 0x46.toByte()
    const val MAGIC_1 = 0x54.toByte()
    const val MAGIC_2 = 0x42.toByte()
    const val MAGIC_3 = 0x4B.toByte()
    const val HEADER_BYTES = 56
    const val SALT_BYTES = 16
    const val STREAM_HEADER_BYTES = 24
    const val FORMAT_VERSION = 1
    const val KDF_ARGON2ID13 = 1
    const val AEAD_SECRETSTREAM = 1
    const val DEFAULT_OPSLIMIT = 3
    const val DEFAULT_MEMLIMIT_KIB = 64 * 1024
    const val CHUNK_PLAINTEXT = 64 * 1024
    const val CHUNK_OVERHEAD = 17
    const val MAX_CIPHERTEXT = CHUNK_PLAINTEXT + CHUNK_OVERHEAD
    const val MAX_PLAINTEXT = 200L * 1024L * 1024L
    const val MIN_OPSLIMIT = 1
    const val MAX_OPSLIMIT = 10
    const val MIN_MEMLIMIT_KIB = 8 * 1024
    const val MAX_MEMLIMIT_KIB = 512 * 1024
    const val MIN_PASSPHRASE = 10

    fun encodeHeader(header: BackupHeader): ByteArray {
        require(header.salt.size == SALT_BYTES)
        require(header.streamHeader.size == STREAM_HEADER_BYTES)
        val buffer = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_0).put(MAGIC_1).put(MAGIC_2).put(MAGIC_3)
        buffer.putShort(header.formatVersion.toShort())
        buffer.put(header.kdfId.toByte())
        buffer.put(header.aeadId.toByte())
        buffer.putInt(header.opslimit)
        buffer.putInt(header.memlimitKiB)
        buffer.put(header.salt)
        buffer.put(header.streamHeader)
        return buffer.array()
    }

    fun parseHeader(bytes: ByteArray): HeaderParse {
        if (bytes.size < HEADER_BYTES) return HeaderParse.Rejected(BackupProblem.TRUNCATED)
        if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1 || bytes[2] != MAGIC_2 || bytes[3] != MAGIC_3) {
            return HeaderParse.Rejected(BackupProblem.NOT_A_BACKUP)
        }
        val buffer = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val version = buffer.short.toInt() and 0xFFFF
        val kdf = buffer.get().toInt() and 0xFF
        val aead = buffer.get().toInt() and 0xFF
        val ops = buffer.int
        val mem = buffer.int
        if (version > FORMAT_VERSION) return HeaderParse.Rejected(BackupProblem.NEWER_VERSION)
        if (version < 1 || kdf != KDF_ARGON2ID13 || aead != AEAD_SECRETSTREAM) {
            return HeaderParse.Rejected(BackupProblem.NOT_A_BACKUP)
        }
        if (ops !in MIN_OPSLIMIT..MAX_OPSLIMIT || mem !in MIN_MEMLIMIT_KIB..MAX_MEMLIMIT_KIB) {
            return HeaderParse.Rejected(BackupProblem.OVER_LIMIT)
        }
        val salt = ByteArray(SALT_BYTES)
        val stream = ByteArray(STREAM_HEADER_BYTES)
        buffer.get(salt)
        buffer.get(stream)
        return HeaderParse.Ok(BackupHeader(version, kdf, aead, ops, mem, salt, stream))
    }

    fun splitPlaintext(plaintext: ByteArray): List<ByteArray> {
        if (plaintext.isEmpty()) return listOf(ByteArray(0))
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < plaintext.size) {
            val end = minOf(offset + CHUNK_PLAINTEXT, plaintext.size)
            chunks += plaintext.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }
}

data class CipherChunk(val ciphertext: ByteArray)

data class EncryptedPayload(
    val streamHeader: ByteArray,
    val chunks: List<CipherChunk>,
)

interface ExportCipher {
    fun randomBytes(size: Int): ByteArray

    fun deriveKey(passphrase: ByteArray, salt: ByteArray, opslimit: Int, memlimitBytes: Long): ByteArray

    fun encrypt(
        key: ByteArray,
        chunks: List<ByteArray>,
        additionalDataFor: (streamHeader: ByteArray) -> ByteArray,
    ): EncryptedPayload

    /** Null means authentication failed. MissingFinal means no TAG_FINAL chunk was seen. */
    fun decrypt(key: ByteArray, payload: EncryptedPayload, additionalData: ByteArray): DecryptOutcome
}

sealed class DecryptOutcome {
    data class Ok(val plaintext: ByteArray) : DecryptOutcome()

    data object AuthFailed : DecryptOutcome()

    data object MissingFinal : DecryptOutcome()

    data object Trailing : DecryptOutcome()
}

object BackupCodec {
    fun write(cipher: ExportCipher, passphrase: CharArray, plaintext: ByteArray): ByteArray {
        val passphraseBytes = charArrayToUtf8(passphrase)
        val salt = cipher.randomBytes(BackupFormat.SALT_BYTES)
        val key = cipher.deriveKey(
            passphraseBytes,
            salt,
            BackupFormat.DEFAULT_OPSLIMIT,
            BackupFormat.DEFAULT_MEMLIMIT_KIB.toLong() * 1024L,
        )
        passphraseBytes.fill(0)
        val chunks = BackupFormat.splitPlaintext(plaintext)
        val placeholder = BackupHeader(
            formatVersion = BackupFormat.FORMAT_VERSION,
            kdfId = BackupFormat.KDF_ARGON2ID13,
            aeadId = BackupFormat.AEAD_SECRETSTREAM,
            opslimit = BackupFormat.DEFAULT_OPSLIMIT,
            memlimitKiB = BackupFormat.DEFAULT_MEMLIMIT_KIB,
            salt = salt,
            streamHeader = ByteArray(BackupFormat.STREAM_HEADER_BYTES),
        )
        val encrypted = try {
            cipher.encrypt(key, chunks) { streamHeader ->
                BackupFormat.encodeHeader(placeholder.copy(streamHeader = streamHeader))
            }
        } finally {
            key.fill(0)
        }
        val headerBytes = BackupFormat.encodeHeader(placeholder.copy(streamHeader = encrypted.streamHeader))
        val body = java.io.ByteArrayOutputStream()
        encrypted.chunks.forEach { chunk ->
            body.write(encodeLength(chunk.ciphertext.size))
            body.write(chunk.ciphertext)
        }
        return headerBytes + body.toByteArray()
    }

    fun read(cipher: ExportCipher, passphrase: CharArray, file: ByteArray): ReadBackup {
        val parsed = BackupFormat.parseHeader(file)
        if (parsed is HeaderParse.Rejected) return ReadBackup.Failed(parsed.problem)
        val header = (parsed as HeaderParse.Ok).header
        val chunks = mutableListOf<CipherChunk>()
        var offset = BackupFormat.HEADER_BYTES
        while (offset < file.size) {
            if (file.size - offset < 4) return ReadBackup.Failed(BackupProblem.TRUNCATED)
            val length = ByteBuffer.wrap(file, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (length <= 0 || length > BackupFormat.MAX_CIPHERTEXT) {
                return ReadBackup.Failed(BackupProblem.OVER_LIMIT)
            }
            offset += 4
            if (file.size - offset < length) return ReadBackup.Failed(BackupProblem.TRUNCATED)
            chunks += CipherChunk(file.copyOfRange(offset, offset + length))
            offset += length
        }
        if (chunks.isEmpty()) return ReadBackup.Failed(BackupProblem.TRUNCATED)
        val passphraseBytes = charArrayToUtf8(passphrase)
        val key = cipher.deriveKey(
            passphraseBytes,
            header.salt,
            header.opslimit,
            header.memlimitKiB.toLong() * 1024L,
        )
        passphraseBytes.fill(0)
        val additional = file.copyOfRange(0, BackupFormat.HEADER_BYTES)
        val outcome = try {
            cipher.decrypt(key, EncryptedPayload(header.streamHeader, chunks), additional)
        } finally {
            key.fill(0)
        }
        return when (outcome) {
            is DecryptOutcome.Ok -> if (outcome.plaintext.size.toLong() > BackupFormat.MAX_PLAINTEXT) {
                ReadBackup.Failed(BackupProblem.OVER_LIMIT)
            } else {
                ReadBackup.Plaintext(outcome.plaintext)
            }
            DecryptOutcome.AuthFailed, DecryptOutcome.Trailing ->
                ReadBackup.Failed(BackupProblem.WRONG_PASSPHRASE_OR_DAMAGED)
            DecryptOutcome.MissingFinal -> ReadBackup.Failed(BackupProblem.TRUNCATED)
        }
    }

    private fun encodeLength(length: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length).array()

    fun charArrayToUtf8(chars: CharArray): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        return bytes
    }
}

sealed class ReadBackup {
    data class Plaintext(val bytes: ByteArray) : ReadBackup()

    data class Failed(val problem: BackupProblem) : ReadBackup()
}
