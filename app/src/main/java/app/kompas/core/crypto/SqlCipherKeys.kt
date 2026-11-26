package app.kompas.core.crypto

/**
 * SQLCipher treats a passphrase shaped as x'<64 hex chars>' as a raw 32-byte key and skips PBKDF.
 * The bytes are already random and wrapped by the Keystore, so a second KDF only adds open latency.
 */
object SqlCipherKeys {
    fun rawKeyPassphrase(key: ByteArray): ByteArray {
        require(key.size == 32) { "DB key must be 32 bytes" }
        val hex = buildString(64) {
            key.forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
        }
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }
}
