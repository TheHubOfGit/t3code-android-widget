package app.t3widget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import app.t3widget.data.SettingsStore
import app.t3widget.sync.ShellSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class T3WidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = T3StatusWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        ShellSyncWorker.schedulePeriodic(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Fires when a widget is added (updatePeriodMillis is 0, so not periodically).
        ShellSyncWorker.schedulePeriodic(context)
        ShellSyncWorker.refreshNow(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val app = context.applicationContext
        // Glance's receiver already holds goAsync(); this is a tiny best-effort DataStore write.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { SettingsStore(app).forgetWidgets(appWidgetIds) }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        ShellSyncWorker.cancelAll(context)
    }
}
