package app.t3widget.settings

import android.os.Bundle
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.RoundedCornerShape
import android.provider.Settings
import android.os.PowerManager
import android.net.Uri
import android.content.Intent
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.os.Build
import app.t3widget.data.ApiResult
import app.t3widget.data.SettingsStore
import app.t3widget.data.ShellRepository
import app.t3widget.data.T3Api
import app.t3widget.data.T3Constants
import app.t3widget.sync.ShellSyncWorker
import app.t3widget.widget.WidgetPreviews
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { SettingsScreen(onDone = { finish() }) } }
        lifecycleScope.launch {
            // Opening the app doubles as "refresh now" (transparent widgets have no refresh button).
            if (SettingsStore(applicationContext).load() != null) {
                ShellRepository(applicationContext).setRefreshing(true)
                ShellSyncWorker.refreshNow(applicationContext)
            }
            runCatching { WidgetPreviews.publishIfNeeded(applicationContext) }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), content = content)
    }
}

private const val READ_TOKEN_CMD =
    "t3 auth session issue --scope orchestration:read --ttl 30d --label \"android-widget\" --token-only"

@Composable
private fun SettingsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context.applicationContext) }
    val api = remember { T3Api() }

    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var hasSavedToken by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        url = settings.loadUrl().orEmpty()
        hasSavedToken = settings.hasToken()
    }

    suspend fun effectiveToken(): String? =
        token.trim().ifEmpty { null } ?: settings.load()?.token

    fun test(thenSave: Boolean) {
        val urlError = T3Api.validateBaseUrl(url)
        if (urlError != null) {
            status = urlError
            return
        }
        val base = T3Api.normalizeBaseUrl(url)!!
        busy = true
        status = "Connecting…"
        scope.launch {
            val tok = effectiveToken()
            val message = withContext(Dispatchers.IO) { probe(api, base, tok) }
            status = message.text
            if (thenSave && message.ok) {
                settings.save(base, token.ifBlank { null })
                url = base
                token = ""
                hasSavedToken = true
                ShellRepository(context.applicationContext).setRefreshing(true)
                ShellSyncWorker.schedulePeriodic(context)
                ShellSyncWorker.refreshNow(context)
                status = message.text + "\nSaved. The widget will refresh now."
            }
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("T3 Code widget", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Shows running threads and requests waiting on you from your T3 Code server. " +
                "Long-press the widget and tap its settings to switch between card and transparent styles. " +
                "Expose the server with `t3 serve --tailscale-serve` (or `--lan` on home Wi-Fi), " +
                "then issue a read-only token on that computer:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            READ_TOKEN_CMD,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server URL") },
            placeholder = { Text("https://my-mac.tailnet.ts.net  or  192.168.1.20:${T3Constants.DEFAULT_PORT}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Access token") },
            placeholder = { Text(if (hasSavedToken) "Saved — leave blank to keep" else "Paste token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { test(thenSave = false) }, enabled = !busy) { Text("Test connection") }
            Button(onClick = { test(thenSave = true) }, enabled = !busy) { Text("Save") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

        BackgroundUpdatesCard()

        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                scope.launch {
                    settings.clear()
                    ShellRepository(context.applicationContext).clear()
                    ShellSyncWorker.refreshNow(context)
                    url = ""; token = ""; hasSavedToken = false
                    status = "Forgot server and token. Revoke the token on the host with `t3 auth session revoke`."
                }
            }) { Text("Forget server") }
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

private data class ProbeResult(val ok: Boolean, val text: String)

private fun probe(api: T3Api, base: String, token: String?): ProbeResult {
    val env = when (val r = api.fetchEnvironment(base)) {
        is ApiResult.Ok -> r.value
        is ApiResult.Offline -> return ProbeResult(false, "Can't reach $base (${r.message})")
        else -> return ProbeResult(false, "That doesn't look like a T3 Code server")
    }
    val name = env.label ?: "server"
    val version = env.serverVersion ?: "?"
    if (env.orchestrationProtocolVersion != T3Constants.ORCHESTRATION_PROTOCOL_VERSION) {
        return ProbeResult(
            false,
            "$name runs T3 Code $version with API protocol ${env.orchestrationProtocolVersion}; " +
                "this widget speaks ${T3Constants.ORCHESTRATION_PROTOCOL_VERSION}. A widget update is needed.",
        )
    }
    if (token == null) return ProbeResult(false, "Found $name (T3 Code $version). Now paste a token.")
    return when (val shell = api.fetchShell(base, token)) {
        is ApiResult.Ok -> ProbeResult(
            true,
            "Connected to $name · T3 Code $version · ${shell.value.threads.size} threads, ${shell.value.projects.size} projects",
        )
        ApiResult.Unauthorized -> ProbeResult(false, "Found $name, but the token was rejected (expired, revoked, or missing orchestration:read).")
        is ApiResult.ProtocolMismatch -> ProbeResult(false, "API protocol mismatch — widget update needed.")
        is ApiResult.Offline -> ProbeResult(false, "Connection dropped: ${shell.message}")
        is ApiResult.Failed -> ProbeResult(false, "Server error: ${shell.message}")
    }
}

/**
 * Battery Saver (and Doze) block background network for every app not on the battery allowlist,
 * so the widget can't refresh and reads as "offline". "Unrestricted" battery use lifts that.
 */
@Composable
private fun BackgroundUpdatesCard() {
    val context = LocalContext.current
    val power = remember { context.getSystemService(PowerManager::class.java) }
    var allowed by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) { // re-check after the user returns from the system dialog
            allowed = power.isIgnoringBatteryOptimizations(context.packageName)
            delay(1_000)
        }
    }
    if (allowed) {
        Text(
            "Background updates: allowed, including during Battery Saver.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep the widget updating", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android cuts background apps off the network during Battery Saver, so the widget " +
                    "stops updating and shows \"paused\". Allowing unrestricted battery use fixes that; " +
                    "the widget still only checks every 15 minutes.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                @SuppressLint("BatteryLife")
                val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(request) }
                    .onFailure { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }) { Text("Allow background updates") }
        }
    }
}
