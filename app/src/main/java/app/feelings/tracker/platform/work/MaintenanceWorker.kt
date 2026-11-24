package app.feelings.tracker.platform.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.feelings.tracker.App
import app.feelings.tracker.core.result.AppError

class MaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as App
        val opened = app.container.ensureOpen()
        if (opened is app.feelings.tracker.core.result.AppResult.Err && opened.error is AppError.KeyLost) {
            app.container.notifications.postStatus(app.feelings.tracker.R.string.key_loss_title)
        }
        return Result.success()
    }
}
