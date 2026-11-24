package app.feelings.tracker.data.db

import android.content.Context
import androidx.room.Room
import app.feelings.tracker.core.crypto.DbKeyManager
import app.feelings.tracker.core.crypto.SqlCipherKeys
import app.feelings.tracker.core.result.AppError
import app.feelings.tracker.core.result.AppResult
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

object DatabaseFactory {
    fun open(context: Context, keyManager: DbKeyManager): AppResult<AppDatabase> {
        val loaded = keyManager.loadOrCreate()
        if (loaded is AppResult.Err) return loaded
        val key = (loaded as AppResult.Ok).value
        val passphrase = SqlCipherKeys.rawKeyPassphrase(key)
        key.fill(0)
        System.loadLibrary("sqlcipher")
        return try {
            val database = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DbKeyManager.DATABASE_NAME,
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addCallback(SeedCallback())
                .build()
            database.openHelper.writableDatabase.query("SELECT count(*) FROM app_settings").close()
            passphrase.fill(0)
            AppResult.Ok(database)
        } catch (_: Exception) {
            passphrase.fill(0)
            if (keyManager.hasDatabase()) AppResult.Err(AppError.KeyLost) else AppResult.Err(AppError.DbOpenFailed)
        }
    }

    fun openPassphrase(context: Context, name: String, passphrase: ByteArray, seed: Boolean): AppDatabase {
        System.loadLibrary("sqlcipher")
        val builder = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
        if (seed) builder.addCallback(SeedCallback())
        return builder.build()
    }
}
