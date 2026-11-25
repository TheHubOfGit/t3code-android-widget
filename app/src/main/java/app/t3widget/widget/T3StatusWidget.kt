package app.t3widget.widget

import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.t3widget.R
import app.t3widget.data.Classifier
import app.t3widget.data.FaviconStore
import app.t3widget.data.NetworkBlock
import app.t3widget.data.ProjectIcon
import app.t3widget.data.SettingsStore
import app.t3widget.data.ShellRepository
import app.t3widget.data.SyncStatus
import app.t3widget.data.ThreadRow
import app.t3widget.data.ThreadState
import app.t3widget.data.WidgetSnapshot
import app.t3widget.data.WidgetStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import app.t3widget.settings.SettingsActivity
import app.t3widget.sync.ShellSyncWorker
import java.time.Duration
import java.time.Instant

/**
 * "State pills" design (M3 Expressive): up to three full-width pills tinted by thread state.
 * Each leads with the project's icon inside a shape whose silhouette encodes the state.
 */
class T3StatusWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = ShellRepository(context)
        val settings = SettingsStore(context)
        val serverUrl = settings.loadUrl()
        val favicons = FaviconStore(context)
        fun withFavicons(s: WidgetSnapshot) = s to s.rows.mapNotNull { row ->
            row.workspaceRoot?.takeIf { row.icon?.kind == "auto" }?.let { root -> favicons.loadBitmap(root)?.let { root to it } }
        }.toMap()
        val initial = withFavicons(repo.current())
        val content = repo.snapshot.map { withFavicons(it) }.flowOn(Dispatchers.IO)
        val styleFlow = settings.widgetStyle(GlanceAppWidgetManager(context).getAppWidgetId(id))
        val initialStyle = styleFlow.first()
        provideContent {
            val state by content.collectAsState(initial = initial)
            val style by styleFlow.collectAsState(initial = initialStyle)
            GlanceTheme {
                WidgetContent(state.first, serverUrl, style, state.second)
            }
        }
    }

    /** Rendered into the widget picker on Android 15+ (see [WidgetPreviews]). */
    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent {
            GlanceTheme {
                WidgetContent(WidgetPreviews.sampleSnapshot(), serverUrl = null, style = WidgetStyle.CARD)
            }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        ShellRepository(context).setRefreshing(true)
        ShellSyncWorker.refreshNow(context)
    }
}

/** Container / on-container / accent colors for one state's pill. */
private data class StateStyle(
    val container: ColorProvider,
    val onContainer: ColorProvider,
    val accent: ColorProvider,
    val shape: Int,
    val label: String,
)

private fun fixed(day: Long, night: Long) = ColorProvider(day = Color(day), night = Color(night))

@Composable
private fun styleOf(row: ThreadRow): StateStyle {
    val c = GlanceTheme.colors
    return when (row.state) {
        // Attention is always amber, independent of the wallpaper palette.
        ThreadState.NEEDS_YOU -> StateStyle(
            fixed(0xFFFFDDB3, 0xFF5C4100), fixed(0xFF2A1800, 0xFFFFDDB3), fixed(0xFF825500, 0xFFFFB95C),
            R.drawable.shape_needs_you, "Needs you",
        )
        ThreadState.RUNNING -> StateStyle(
            c.primaryContainer, c.onPrimaryContainer, c.primary, R.drawable.shape_running, "Running",
        )
        ThreadState.FAILED -> StateStyle(
            c.errorContainer, c.onErrorContainer, c.error, R.drawable.shape_failed, "Failed",
        )
        ThreadState.WAITING -> StateStyle(
            c.tertiaryContainer, c.onTertiaryContainer, c.tertiary, R.drawable.shape_waiting, "Waiting",
        )
        ThreadState.DONE -> StateStyle(
            fixed(0xFFC4EFC0, 0xFF1E4E25), fixed(0xFF002106, 0xFFBAF3B8), fixed(0xFF2E6A30, 0xFF9ED69E),
            R.drawable.shape_done, doneLabel(row.rawStatus),
        )
    }
}

private fun doneLabel(status: String) = when (status) {
    "completed" -> "Done"
    "idle" -> "Idle"
    "interrupted", "cancelled" -> "Stopped"
    "rolled_back" -> "Rolled back"
    else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/** T3 Code project colors as (container, glyph) pairs for light and dark. */
private object ProjectColors {
    private val table: Map<String, LongArray> = mapOf(
        // name to [dayContainer, dayGlyph, nightContainer, nightGlyph]
        "gray" to longArrayOf(0xFFE5E7EB, 0xFF1F2937, 0xFF374151, 0xFFE5E7EB),
        "red" to longArrayOf(0xFFFECACA, 0xFF7F1D1D, 0xFF991B1B, 0xFFFECACA),
        "orange" to longArrayOf(0xFFFED7AA, 0xFF7C2D12, 0xFF9A3412, 0xFFFED7AA),
        "amber" to longArrayOf(0xFFFDE68A, 0xFF78350F, 0xFF92400E, 0xFFFDE68A),
        "yellow" to longArrayOf(0xFFFEF08A, 0xFF713F12, 0xFF854D0E, 0xFFFEF08A),
        "lime" to longArrayOf(0xFFD9F99D, 0xFF365314, 0xFF3F6212, 0xFFD9F99D),
        "green" to longArrayOf(0xFFBBF7D0, 0xFF14532D, 0xFF166534, 0xFFBBF7D0),
        "emerald" to longArrayOf(0xFFA7F3D0, 0xFF064E3B, 0xFF065F46, 0xFFA7F3D0),
        "teal" to longArrayOf(0xFF99F6E4, 0xFF134E4A, 0xFF115E59, 0xFF99F6E4),
        "cyan" to longArrayOf(0xFFA5F3FC, 0xFF164E63, 0xFF155E75, 0xFFA5F3FC),
        "sky" to longArrayOf(0xFFBAE6FD, 0xFF0C4A6E, 0xFF075985, 0xFFBAE6FD),
        "blue" to longArrayOf(0xFFBFDBFE, 0xFF1E3A8A, 0xFF1E40AF, 0xFFBFDBFE),
        "indigo" to longArrayOf(0xFFC7D2FE, 0xFF312E81, 0xFF3730A3, 0xFFC7D2FE),
        "violet" to longArrayOf(0xFFDDD6FE, 0xFF4C1D95, 0xFF5B21B6, 0xFFDDD6FE),
        "purple" to longArrayOf(0xFFE9D5FF, 0xFF581C87, 0xFF6B21A8, 0xFFE9D5FF),
        "fuchsia" to longArrayOf(0xFFF5D0FE, 0xFF701A75, 0xFF86198F, 0xFFF5D0FE),
        "pink" to longArrayOf(0xFFFBCFE8, 0xFF831843, 0xFF9D174D, 0xFFFBCFE8),
        "rose" to longArrayOf(0xFFFECDD3, 0xFF881337, 0xFF9F1239, 0xFFFECDD3),
    )

    private fun of(name: String) = table[name] ?: table.getValue("gray")
    fun container(name: String) = of(name).let { fixed(it[0], it[2]) }
    fun glyph(name: String) = of(name).let { fixed(it[1], it[3]) }
}

private const val STALE_AFTER_MS = 30 * 60 * 1000L
private val HEADER_HEIGHT = 40.dp
private val PILL_HEIGHT = 46.dp
private val PILL_GAP = 6.dp

@Composable
internal fun WidgetContent(
    snapshot: WidgetSnapshot,
    serverUrl: String?,
    style: WidgetStyle,
    favicons: Map<String, Bitmap> = emptyMap(),
) {
    val transparent = style == WidgetStyle.TRANSPARENT
    val size = LocalSize.current
    val chrome = if (transparent) 0.dp else HEADER_HEIGHT + 24.dp
    val fit = (((size.height - chrome).value + PILL_GAP.value) / (PILL_HEIGHT.value + PILL_GAP.value)).toInt()
        .coerceIn(1, Classifier.MAX_ROWS)

    val container = if (transparent) {
        GlanceModifier.fillMaxSize().appWidgetBackground()
    } else {
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(28.dp)
            .padding(12.dp)
    }

    Column(modifier = container, verticalAlignment = if (transparent) Alignment.CenterVertically else Alignment.Top) {
        if (!transparent) Header(snapshot, serverUrl)
        val banner = bannerText(snapshot)
        if (banner != null) {
            StatusBanner(banner, snapshot.status, asPill = transparent)
            if (transparent) Spacer(GlanceModifier.height(PILL_GAP))
        }
        val rows = snapshot.rows.take(if (banner != null) (fit - 1).coerceAtLeast(0) else fit)
        if (rows.isEmpty() && snapshot.status == SyncStatus.OK) {
            if (transparent) {
                StatusBanner("All quiet — no threads yet", SyncStatus.OK, asPill = true)
            } else {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "All quiet — no threads yet",
                        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                    )
                }
            }
        }
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(GlanceModifier.height(PILL_GAP))
            Pill(row, snapshot.environmentId, serverUrl, row.workspaceRoot?.let { favicons[it] })
        }
    }
}

@Composable
private fun Header(snapshot: WidgetSnapshot, serverUrl: String?) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT).padding(start = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "T3 Code",
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity(ThreadLinks.appIntent(context, serverUrl))),
        )
        Text(
            text = freshnessLabel(context, snapshot),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(8.dp))
        Box(
            modifier = GlanceModifier
                .size(32.dp)
                .cornerRadius(12.dp)
                .background(GlanceTheme.colors.primaryContainer)
                .clickable(actionRunCallback<RefreshAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onPrimaryContainer),
                modifier = GlanceModifier.size(18.dp),
            )
        }
    }
}

private fun freshnessLabel(context: Context, snapshot: WidgetSnapshot): String {
    if (snapshot.refreshing) return "Refreshing…"
    val last = snapshot.lastSuccessAt ?: return ""
    val time = DateFormat.getTimeFormat(context).format(java.util.Date(last))
    val stale = System.currentTimeMillis() - last > STALE_AFTER_MS
    return if (stale || snapshot.status != SyncStatus.OK) "as of $time" else time
}

private fun bannerText(snapshot: WidgetSnapshot): String? = when (snapshot.status) {
    SyncStatus.OK -> null
    SyncStatus.NOT_CONFIGURED -> "Not set up — tap to add your server"
    SyncStatus.LOADING -> "Loading…"
    SyncStatus.OFFLINE -> if (snapshot.message == NetworkBlock.MESSAGE) snapshot.message else "Offline — showing last known state"
    SyncStatus.AUTH_EXPIRED -> "Token expired — tap to paste a new one"
    SyncStatus.UPDATE_NEEDED -> snapshot.message ?: "T3 Code updated its API — widget update needed"
    SyncStatus.ERROR -> "Error: ${snapshot.message ?: "unknown"}"
}

@Composable
private fun StatusBanner(text: String, status: SyncStatus, asPill: Boolean) {
    val context = LocalContext.current
    val quiet = status == SyncStatus.OFFLINE || status == SyncStatus.OK
    val color = if (quiet) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.error
    val open = actionStartActivity(Intent(context, SettingsActivity::class.java))
    if (asPill) {
        // Over the wallpaper, give the message its own surface so it stays readable.
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(18.dp)
                .background(GlanceTheme.colors.widgetBackground)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clickable(open),
        ) {
            Text(text = text, style = TextStyle(color = color, fontSize = 12.sp), maxLines = 2)
        }
    } else {
        Text(
            text = text,
            style = TextStyle(color = color, fontSize = 12.sp),
            maxLines = 2,
            modifier = GlanceModifier.padding(start = 4.dp, bottom = 6.dp).clickable(open),
        )
    }
}

@Composable
private fun Pill(row: ThreadRow, environmentId: String?, serverUrl: String?, favicon: Bitmap?) {
    val context = LocalContext.current
    val style = styleOf(row)
    val sub = listOfNotNull(
        row.projectTitle.takeIf { it.isNotBlank() },
        relativeAge(row.updatedAt),
    ).joinToString(" · ")

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(PILL_HEIGHT)
            .cornerRadius(PILL_HEIGHT / 2)
            .background(style.container)
            .padding(start = 5.dp, end = 10.dp)
            .clickable(actionStartActivity(ThreadLinks.threadIntent(context, environmentId, row.threadId, serverUrl))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectBadge(row, style, favicon)
        Spacer(GlanceModifier.width(10.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.title,
                style = TextStyle(color = style.onContainer, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    style = TextStyle(color = style.onContainer, fontSize = 11.sp),
                    maxLines = 1,
                )
            }
        }
        Spacer(GlanceModifier.width(6.dp))
        Box(
            modifier = GlanceModifier
                .cornerRadius(12.dp)
                .background(style.accent)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        ) {
            Text(
                text = style.label,
                style = TextStyle(color = style.container, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

/** Project icon inside the state's expressive shape, filled with the project's color. */
@Composable
private fun ProjectBadge(row: ThreadRow, style: StateStyle, favicon: Bitmap?) {
    val icon = row.icon ?: ProjectIcon("auto", Classifier.monogram(row.projectTitle), Classifier.monogramColor(row.projectTitle), "PR")
    val glyphColor = ProjectColors.glyph(icon.color)
    val showFavicon = favicon != null && icon.kind == "auto"
    if (showFavicon) {
        // Repo favicons are complete logos: show them edge to edge, like T3 Code does.
        Image(
            provider = ImageProvider(favicon!!),
            contentDescription = style.label,
            modifier = GlanceModifier.size(36.dp),
        )
        return
    }
    Box(modifier = GlanceModifier.size(36.dp), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(style.shape),
            contentDescription = style.label,
            colorFilter = ColorFilter.tint(ProjectColors.container(icon.color)),
            modifier = GlanceModifier.size(36.dp),
        )
        val lucide = if (icon.kind == "lucide") LucideIcons.byName[icon.value] else null
        when {
            lucide != null -> Image(
                provider = ImageProvider(lucide),
                contentDescription = null,
                colorFilter = ColorFilter.tint(glyphColor),
                modifier = GlanceModifier.size(18.dp),
            )
            icon.kind == "emoji" -> Text(
                text = icon.value,
                style = TextStyle(fontSize = 17.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
            else -> {
                val text = if (icon.kind == "lucide") icon.fallbackMonogram else icon.value
                Text(
                    text = text,
                    style = TextStyle(
                        color = glyphColor,
                        fontSize = if (text.length > 2) 10.sp else 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun relativeAge(iso: String?): String? {
    val then = Classifier.parseInstant(iso) ?: return null
    val d = Duration.between(then, Instant.now())
    return when {
        d.isNegative || d.toMinutes() < 1 -> "just now"
        d.toMinutes() < 60 -> "${d.toMinutes()}m ago"
        d.toHours() < 24 -> "${d.toHours()}h ago"
        else -> "${d.toDays()}d ago"
    }
}
