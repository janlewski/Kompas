package app.feelings.tracker.core.crypto

import android.content.Context
import app.feelings.tracker.core.result.AppError
import app.feelings.tracker.core.result.AppResult
import java.io.File
import java.security.SecureRandom

/**
 * Key blob layout (no health data): magic "DBK1", alias length, alias, 12-byte IV, ciphertext.
 * The alias is recorded so a restore can swap blobs without deleting the previous Keystore key first.
 */
class DbKeyManager(
    context: Context,
    private val wrapper: KeystoreKeyWrapper,
    private val random: SecureRandom = SecureRandom(),
) {
    private val directory = context.noBackupFilesDir
    private val keyFile = File(directory, FILE_NAME)
    private val databaseFile = context.getDatabasePath(DATABASE_NAME)

    fun databaseFile(): File = databaseFile

    fun hasDatabase(): Boolean = databaseFile.exists()

    fun hasKeyFile(): Boolean = keyFile.exists()

    fun loadOrCreate(): AppResult<ByteArray> {
        if (!keyFile.exists()) {
            if (databaseFile.exists()) return AppResult.Err(AppError.KeyLost)
            return createNew(DEFAULT_ALIAS)
        }
        return try {
            val wrapped = read(keyFile) ?: return AppResult.Err(AppError.KeyLost)
            AppResult.Ok(wrapper.unwrap(wrapped))
        } catch (_: Exception) {
            AppResult.Err(AppError.KeyLost)
        }
    }

    fun createWithAlias(alias: String): ByteArray {
        val created = createNew(alias)
        check(created is AppResult.Ok)
        return created.value
    }

    fun writeExisting(alias: String, key: ByteArray) {
        val wrapped = wrapper.wrap(key, alias)
        writeAtomically(keyFile, encode(wrapped))
    }

    fun deleteKeyMaterial() {
        if (keyFile.exists()) keyFile.delete()
        wrapper.delete(DEFAULT_ALIAS)
        wrapper.delete(IMPORT_ALIAS)
    }

    private fun createNew(alias: String): AppResult<ByteArray> {
        val key = ByteArray(KEY_BYTES)
        random.nextBytes(key)
        return try {
            writeExisting(alias, key)
            AppResult.Ok(key)
        } catch (_: Exception) {
            key.fill(0)
            AppResult.Err(AppError.KeyLost)
        }
    }

    companion object {
        const val DATABASE_NAME = "feelings.db"
        const val DEFAULT_ALIAS = "db_key_wrap"
        const val IMPORT_ALIAS = "db_key_wrap_import"
        const val FILE_NAME = "dbkey.bin"
        private const val KEY_BYTES = 32
        private val MAGIC = byteArrayOf(0x44, 0x42, 0x4B, 0x31)

        fun encode(wrapped: WrappedSecret): ByteArray {
            val aliasBytes = wrapped.alias.toByteArray(Charsets.UTF_8)
            require(aliasBytes.size <= 0xFFFF)
            require(wrapped.iv.size == 12)
            val out = ByteArray(4 + 2 + aliasBytes.size + wrapped.iv.size + wrapped.ciphertext.size)
            MAGIC.copyInto(out)
            out[4] = (aliasBytes.size shr 8).toByte()
            out[5] = aliasBytes.size.toByte()
            aliasBytes.copyInto(out, 6)
            val ivAt = 6 + aliasBytes.size
            wrapped.iv.copyInto(out, ivAt)
            wrapped.ciphertext.copyInto(out, ivAt + wrapped.iv.size)
            return out
        }

        fun decode(bytes: ByteArray): WrappedSecret? {
            if (bytes.size < 4 + 2 + 12 + 16) return null
            if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
            val aliasLen = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
            val aliasEnd = 6 + aliasLen
            if (aliasLen <= 0 || bytes.size < aliasEnd + 12 + 16) return null
            val alias = bytes.copyOfRange(6, aliasEnd).toString(Charsets.UTF_8)
            val iv = bytes.copyOfRange(aliasEnd, aliasEnd + 12)
            val ciphertext = bytes.copyOfRange(aliasEnd + 12, bytes.size)
            return WrappedSecret(alias, iv, ciphertext)
        }

        private fun read(file: File): WrappedSecret? = decode(file.readBytes())

        private fun writeAtomically(target: File, bytes: ByteArray) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (target.exists() && !target.delete()) error("key file")
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }
    }
}
