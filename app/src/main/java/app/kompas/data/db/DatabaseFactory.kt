package app.kompas.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import app.kompas.core.crypto.DbKeyManager
import app.kompas.core.crypto.SqlCipherKeys
import app.kompas.core.result.AppError
import app.kompas.core.result.AppResult
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DatabaseFactory {
    fun open(context: Context, keyManager: DbKeyManager): AppResult<AppDatabase> {
        val loaded = keyManager.loadOrCreate()
        if (loaded is AppResult.Err) return loaded
        val key = (loaded as AppResult.Ok).value
        val passphrase = SqlCipherKeys.rawKeyPassphrase(key)
        key.fill(0)
        val factory = RetainedPassphraseFactory(passphrase)
        passphrase.fill(0)
        System.loadLibrary("sqlcipher")
        return try {
            val database = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DbKeyManager.DATABASE_NAME,
            )
                .openHelperFactory(factory)
                .addCallback(SeedCallback())
                .build()
            try {
                database.openHelper.writableDatabase.query("SELECT count(*) FROM app_settings").close()
            } catch (thrown: Exception) {
                runCatching { database.close() }
                throw thrown
            }
            AppResult.Ok(database)
        } catch (_: Exception) {
            factory.wipe()
            if (keyManager.hasDatabase()) AppResult.Err(AppError.KeyLost) else AppResult.Err(AppError.DbOpenFailed)
        }
    }

    fun openPassphrase(context: Context, name: String, passphrase: ByteArray, seed: Boolean): AppDatabase {
        System.loadLibrary("sqlcipher")
        val builder = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
            .openHelperFactory(RetainedPassphraseFactory(passphrase))
        if (seed) builder.addCallback(SeedCallback())
        return builder.build()
    }
}

/**
 * SQLCipher 4.20 keeps the passphrase array and passes it to sqlite3_key for every pooled
 * connection. The caller's array can be wiped immediately. The retained copy is wiped on close.
 */
private class RetainedPassphraseFactory(
    passphrase: ByteArray,
) : SupportSQLiteOpenHelper.Factory {
    private val retained = passphrase.copyOf()
    private val delegate = SupportOpenHelperFactory(retained)

    fun wipe() {
        retained.fill(0)
    }

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        return object : SupportSQLiteOpenHelper by helper {
            override fun close() {
                try {
                    helper.close()
                } finally {
                    wipe()
                }
            }
        }
    }
}
