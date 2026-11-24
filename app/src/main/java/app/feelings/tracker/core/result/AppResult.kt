package app.feelings.tracker.core.result

sealed class AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>()

    data class Err(val error: AppError) : AppResult<Nothing>()
}

sealed class AppError {
    data object KeyLost : AppError()

    data object DbOpenFailed : AppError()

    data class Backup(val problem: BackupProblem) : AppError()
}

enum class BackupProblem {
    NOT_A_BACKUP,
    TRUNCATED,
    NEWER_VERSION,
    WRONG_PASSPHRASE_OR_DAMAGED,
    NOT_ENOUGH_STORAGE,
    OVER_LIMIT,
}

class DbUnavailableException(val error: AppError) : RuntimeException()
