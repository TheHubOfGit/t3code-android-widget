package app.t3widget.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ServerConfig(val baseUrl: String, val token: String)

/** Per-widget look. */
enum class WidgetStyle {
    /** Background, header with refresh button, and the pills. */
    CARD,

    /** No background or header: just the conversation pills over the wallpaper. */
    TRANSPARENT,
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Server URL + bearer token. The token is encrypted with an AES-GCM key that never leaves the
 * Android Keystore; it is never logged (HANDOFF.md §9).
 */
class SettingsStore(private val context: Context) {
    private val store get() = context.settingsDataStore

    suspend fun load(): ServerConfig? {
        val prefs = store.data.first()
        val url = prefs[KEY_URL] ?: return null
        val sealed = prefs[KEY_TOKEN] ?: return null
        val token = runCatching { TokenCipher.decrypt(sealed) }.getOrNull() ?: return null
        return ServerConfig(url, token)
    }

    suspend fun loadUrl(): String? = store.data.first()[KEY_URL]

    suspend fun hasToken(): Boolean = store.data.first()[KEY_TOKEN] != null

    /** Saves the URL, and the token only when [token] is non-null (blank keeps the stored one). */
    suspend fun save(baseUrl: String, token: String?) {
        val sealed = token?.takeIf { it.isNotBlank() }?.let { TokenCipher.encrypt(it.trim()) }
        store.edit {
            it[KEY_URL] = baseUrl
            if (sealed != null) it[KEY_TOKEN] = sealed
        }
    }

    /** Forgets the server and token; widget styles are kept. */
    suspend fun clear() {
        store.edit {
            it.remove(KEY_URL)
            it.remove(KEY_TOKEN)
        }
    }

    fun widgetStyle(appWidgetId: Int): Flow<WidgetStyle> = store.data.map {
        if (appWidgetId.toString() in it[KEY_TRANSPARENT_IDS].orEmpty()) WidgetStyle.TRANSPARENT else WidgetStyle.CARD
    }

    suspend fun setWidgetStyle(appWidgetId: Int, style: WidgetStyle) {
        store.edit {
            val ids = it[KEY_TRANSPARENT_IDS].orEmpty()
            it[KEY_TRANSPARENT_IDS] =
                if (style == WidgetStyle.TRANSPARENT) ids + appWidgetId.toString() else ids - appWidgetId.toString()
        }
    }

    suspend fun forgetWidgets(appWidgetIds: IntArray) {
        store.edit {
            it[KEY_TRANSPARENT_IDS] = it[KEY_TRANSPARENT_IDS].orEmpty() - appWidgetIds.map(Int::toString).toSet()
        }
    }

    /** Version code whose generated picker previews were last published (they are rate-limited). */
    suspend fun previewsPublishedFor(): Int? = store.data.first()[KEY_PREVIEW_VERSION]

    suspend fun setPreviewsPublishedFor(versionCode: Int) {
        store.edit { it[KEY_PREVIEW_VERSION] = versionCode }
    }

    private companion object {
        val KEY_URL = stringPreferencesKey("server_url")
        val KEY_TOKEN = stringPreferencesKey("token_sealed")
        val KEY_TRANSPARENT_IDS = stringSetPreferencesKey("transparent_widget_ids")
        val KEY_PREVIEW_VERSION = intPreferencesKey("previews_published_for")
    }
}

private object TokenCipher {
    private const val ALIAS = "t3widget_token_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(ct)
    }

    fun decrypt(sealed: String): String {
        val (iv, ct) = sealed.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
