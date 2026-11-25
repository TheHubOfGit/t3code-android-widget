package app.t3widget.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import app.t3widget.data.SettingsStore
import app.t3widget.data.WidgetStyle
import app.t3widget.settings.SettingsActivity
import app.t3widget.sync.ShellSyncWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Per-widget options. Launched when a widget is placed (optional on Android 12+) and from the
 * widget's long-press "settings" action (reconfigurable).
 */
class WidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // If the user backs out while placing the widget, the launcher must not add it.
        setResult(Activity.RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            ConfigTheme {
                ConfigScreen(
                    appWidgetId = appWidgetId,
                    onDone = { style -> save(style) },
                )
            }
        }
    }

    private fun save(style: WidgetStyle) {
        lifecycleScope.launch {
            SettingsStore(applicationContext).setWidgetStyle(appWidgetId, style)
            val glanceId = runCatching { GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId) }.getOrNull()
            if (glanceId != null) T3StatusWidget().update(applicationContext, glanceId)
            ShellSyncWorker.schedulePeriodic(applicationContext)
            setResult(Activity.RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@Composable
private fun ConfigTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) { Surface(modifier = Modifier.fillMaxSize(), content = content) }
}

@Composable
private fun ConfigScreen(appWidgetId: Int, onDone: (WidgetStyle) -> Unit) {
    val context = LocalContext.current
    val settings = remember { SettingsStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(WidgetStyle.CARD) }
    var configured by remember { mutableStateOf(true) }

    LaunchedEffect(appWidgetId) {
        style = settings.widgetStyle(appWidgetId).first()
        configured = settings.load() != null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Widget style", style = MaterialTheme.typography.headlineSmall)
        StyleOption(
            selected = style == WidgetStyle.CARD,
            title = "Card",
            body = "Background, title, last-update time and a refresh button.",
            onClick = { style = WidgetStyle.CARD },
        )
        StyleOption(
            selected = style == WidgetStyle.TRANSPARENT,
            title = "Transparent",
            body = "Just the conversation pills over your wallpaper. It still refreshes every 15 minutes; " +
                "open the T3 Widget app to refresh sooner.",
            onClick = { style = WidgetStyle.TRANSPARENT },
        )
        if (!configured) {
            Text(
                "No server set up yet — the widget will ask you to add one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onDone(style) }) { Text("Done") }
            OutlinedButton(onClick = {
                scope.launch {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                }
            }) { Text("Server settings") }
        }
    }
}

@Composable
private fun StyleOption(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
