package app.feelings.tracker.data.db

import app.feelings.tracker.core.result.AppError
import app.feelings.tracker.core.result.AppResult
import app.feelings.tracker.core.result.DbUnavailableException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DatabaseHolder(
    private val opener: () -> AppResult<AppDatabase>,
) {
    private val mutex = Mutex()
    private var database: AppDatabase? = null
    var failure: AppError? = null
        private set

    suspend fun open(): AppResult<AppDatabase> = mutex.withLock {
        database?.let { return AppResult.Ok(it) }
        return when (val opened = opener()) {
            is AppResult.Ok -> {
                database = opened.value
                failure = null
                opened
            }
            is AppResult.Err -> {
                failure = opened.error
                opened
            }
        }
    }

    suspend fun require(): AppDatabase = mutex.withLock {
        database ?: throw DbUnavailableException(failure ?: AppError.DbOpenFailed)
    }

    suspend fun closeAndSwap(next: AppDatabase) = mutex.withLock {
        database?.close()
        database = next
        failure = null
    }

    suspend fun closeAndDrop() = mutex.withLock {
        database?.close()
        database = null
        failure = null
    }
}
