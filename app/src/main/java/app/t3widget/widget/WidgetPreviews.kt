package app.t3widget.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import androidx.collection.intSetOf
import androidx.core.content.pm.PackageInfoCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import app.t3widget.data.Counts
import app.t3widget.data.ProjectIcon
import app.t3widget.data.SettingsStore
import app.t3widget.data.SyncStatus
import app.t3widget.data.ThreadRow
import app.t3widget.data.ThreadState
import app.t3widget.data.WidgetSnapshot
import java.time.Instant

/**
 * Widget-picker previews.
 * - Android 15+: generated from [T3StatusWidget.providePreview] using [sampleSnapshot].
 * - Android 12-14: the static `@layout/widget_preview` referenced from `t3_widget_info.xml`.
 */
object WidgetPreviews {
    fun sampleSnapshot(now: Instant = Instant.now()): WidgetSnapshot {
        fun ago(minutes: Long) = now.minusSeconds(minutes * 60).toString()
        return WidgetSnapshot(
            status = SyncStatus.OK,
            lastSuccessAt = now.toEpochMilli(),
            counts = Counts(needsYou = 1, running = 1),
            rows = listOf(
                ThreadRow(
                    threadId = "preview-1", projectTitle = "my-app", title = "Fix login bug",
                    state = ThreadState.NEEDS_YOU, rawStatus = "running", updatedAt = ago(1),
                    icon = ProjectIcon("monogram", "MA", "violet", "MA"),
                ),
                ThreadRow(
                    threadId = "preview-2", projectTitle = "api", title = "Add rate limiting",
                    state = ThreadState.RUNNING, rawStatus = "running", updatedAt = ago(3),
                    icon = ProjectIcon("emoji", "⚡", "sky", "AP"),
                ),
                ThreadRow(
                    threadId = "preview-3", projectTitle = "docs", title = "Rewrite README",
                    state = ThreadState.DONE, rawStatus = "completed", updatedAt = ago(12),
                    icon = ProjectIcon("lucide", "book-open", "emerald", "DO"),
                ),
            ),
        )
    }

    /**
     * Publishes generated previews once per app version. The platform rate-limits this call, so a
     * failure is simply retried on the next app open / sync.
     */
    suspend fun publishIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val settings = SettingsStore(context)
        val version = runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0)).toInt()
        }.getOrDefault(0)
        if (settings.previewsPublishedFor() == version) return
        val result = runCatching {
            GlanceAppWidgetManager(context).setWidgetPreviews(
                T3WidgetReceiver::class,
                intSetOf(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD),
            )
        }.getOrNull()
        if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
            settings.setPreviewsPublishedFor(version)
        }
    }
}
