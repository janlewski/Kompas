package app.kompas.platform.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kompas.App
import app.kompas.R
import app.kompas.core.result.AppError
import app.kompas.core.result.AppResult

class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val opened = app.container.ensureOpen()
        if (opened is AppResult.Err && opened.error is AppError.KeyLost) {
            app.container.notifications.postStatus(R.string.key_loss_title)
        }
        return Result.success()
    }
}
