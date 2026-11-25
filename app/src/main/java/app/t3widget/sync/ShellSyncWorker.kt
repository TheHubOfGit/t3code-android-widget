package app.t3widget.sync

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.t3widget.data.ShellRepository
import app.t3widget.widget.T3StatusWidget
import app.t3widget.widget.WidgetPreviews
import java.util.concurrent.TimeUnit

/** Fetches the shell snapshot into the cache, then re-renders every widget instance. */
class ShellSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = ShellRepository(applicationContext)
        try {
            repo.refresh()
        } finally {
            repo.setRefreshing(false)
            T3StatusWidget().updateAll(applicationContext)
        }
        runCatching { WidgetPreviews.publishIfNeeded(applicationContext) }
        // Offline/auth errors are surfaced in the widget; retrying would only drain battery.
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "t3-shell-periodic"
        private const val ONE_SHOT = "t3-shell-refresh"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** 15 minutes is WorkManager's floor. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShellSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun refreshNow(context: Context) {
            // No network constraint: if offline we want the widget to say so right away.
            val request = OneTimeWorkRequestBuilder<ShellSyncWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(ONE_SHOT)
            }
        }

        suspend fun hasWidgets(context: Context): Boolean =
            GlanceAppWidgetManager(context).getGlanceIds(T3StatusWidget::class.java).isNotEmpty()
    }
}
