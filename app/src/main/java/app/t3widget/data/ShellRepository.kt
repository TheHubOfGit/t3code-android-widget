package app.t3widget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** What the widget shows about connectivity, on top of the (possibly stale) rows. */
@Serializable
enum class SyncStatus { NOT_CONFIGURED, LOADING, OK, OFFLINE, AUTH_EXPIRED, UPDATE_NEEDED, ERROR }

@Serializable
data class WidgetSnapshot(
    val status: SyncStatus = SyncStatus.NOT_CONFIGURED,
    val message: String? = null,
    val rows: List<ThreadRow> = emptyList(),
    val counts: Counts = Counts(),
    /** Epoch millis of the last successful fetch; rows are as old as this. */
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val refreshing: Boolean = false,
    /** Server's environment id; the official app's thread links are /threads/<environmentId>/<threadId>. */
    val environmentId: String? = null,
)

private val Context.widgetDataStore: DataStore<Preferences> by preferencesDataStore(name = "widget_state")

/** fetch → classify → cache. The widget only ever renders the cache (HANDOFF.md §8, rule 5). */
class ShellRepository(
    private val context: Context,
    private val settings: SettingsStore = SettingsStore(context),
    private val api: T3Api = T3Api(),
) {
    private val store get() = context.widgetDataStore

    val snapshot: Flow<WidgetSnapshot> = store.data.map { decode(it[KEY_SNAPSHOT]) }

    suspend fun current(): WidgetSnapshot = snapshot.first()

    suspend fun setRefreshing(refreshing: Boolean) = update { it.copy(refreshing = refreshing) }

    suspend fun refresh(): WidgetSnapshot = withContext(Dispatchers.IO) {
        val config = settings.load()
        val now = System.currentTimeMillis()
        if (config == null) {
            return@withContext update { WidgetSnapshot(status = SyncStatus.NOT_CONFIGURED) }
        }
        when (val result = api.fetchShell(config.baseUrl, config.token)) {
            is ApiResult.Ok -> {
                val built = Classifier.build(result.value)
                // Unauthenticated and tiny; fall back to the cached id if the probe fails.
                val envId = (api.fetchEnvironment(config.baseUrl) as? ApiResult.Ok)?.value?.environmentId
                // Projects without a custom icon show their repo favicon, as in T3 Code.
                val faviconRoots = built.rows.filter { it.icon?.kind == "auto" }.mapNotNull { it.workspaceRoot }
                runCatching { FaviconStore(context).refresh(config, faviconRoots) }
                update { prev ->
                    WidgetSnapshot(
                        status = SyncStatus.OK,
                        rows = built.rows,
                        counts = built.counts,
                        lastSuccessAt = now,
                        lastAttemptAt = now,
                        environmentId = envId ?: prev.environmentId,
                    )
                }
            }
            ApiResult.Unauthorized -> fail(SyncStatus.AUTH_EXPIRED, "Token expired or revoked — issue a new one", now)
            is ApiResult.ProtocolMismatch -> fail(
                SyncStatus.UPDATE_NEEDED,
                "T3 Code updated its API (protocol ${result.serverProtocol ?: "?"}) — widget update needed",
                now,
            )
            is ApiResult.Offline -> fail(
                SyncStatus.OFFLINE,
                if (NetworkBlock.isBlocked(context)) NetworkBlock.MESSAGE else "Can't reach server",
                now,
            )
            is ApiResult.Failed -> fail(SyncStatus.ERROR, result.message, now)
        }
    }

    suspend fun clear() {
        store.edit { it.remove(KEY_SNAPSHOT) }
    }

    /** Keeps the last good rows and marks them stale. */
    private suspend fun fail(status: SyncStatus, message: String, now: Long) = update {
        it.copy(status = status, message = message, lastAttemptAt = now, refreshing = false)
    }

    private suspend fun update(transform: (WidgetSnapshot) -> WidgetSnapshot): WidgetSnapshot {
        var out = WidgetSnapshot()
        store.edit { prefs ->
            out = transform(decode(prefs[KEY_SNAPSHOT]))
            prefs[KEY_SNAPSHOT] = T3Json.encodeToString(WidgetSnapshot.serializer(), out)
        }
        return out
    }

    private fun decode(raw: String?): WidgetSnapshot =
        raw?.let { runCatching { T3Json.decodeFromString(WidgetSnapshot.serializer(), it) }.getOrNull() }
            ?: WidgetSnapshot()

    private companion object {
        val KEY_SNAPSHOT = stringPreferencesKey("snapshot_v1")
    }
}
