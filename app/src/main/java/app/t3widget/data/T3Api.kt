package app.t3widget.data

import kotlinx.serialization.SerializationException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** 401/403: token missing, expired or revoked. */
    data object Unauthorized : ApiResult<Nothing>
    /** 426, or a 400 that the environment probe attributes to a protocol bump. */
    data class ProtocolMismatch(val serverProtocol: Int?) : ApiResult<Nothing>
    data class Offline(val message: String) : ApiResult<Nothing>
    data class Failed(val message: String) : ApiResult<Nothing>
}

/** Thin, blocking HTTP client for the two read-only endpoints. Call from a background thread. */
class T3Api(
    private val client: OkHttpClient = defaultClient,
) {
    fun fetchEnvironment(baseUrl: String): ApiResult<EnvironmentDto> {
        val url = resolve(baseUrl, T3Constants.ENVIRONMENT_PATH) ?: return ApiResult.Failed("Invalid server URL")
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        return execute(request) { body -> T3Json.decodeFromString(EnvironmentDto.serializer(), body) }
    }

    fun fetchShell(baseUrl: String, token: String): ApiResult<ShellSnapshotDto> {
        val base = resolve(baseUrl, T3Constants.SHELL_PATH) ?: return ApiResult.Failed("Invalid server URL")
        val url = base.newBuilder().apply {
            T3Constants.SHELL_QUERY.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header(T3Constants.ORCHESTRATION_PROTOCOL_HEADER, T3Constants.ORCHESTRATION_PROTOCOL_VERSION.toString())
            .build()
        val result = execute(request) { body -> T3Json.decodeFromString(ShellSnapshotDto.serializer(), body) }
        // Observed on server 0.1.11: a wrong/missing protocol header yields an empty 400, not 426.
        // Disambiguate with the unauthenticated environment probe.
        if (result is ApiResult.Failed && result.message.startsWith("HTTP 400")) {
            val env = fetchEnvironment(baseUrl)
            if (env is ApiResult.Ok && env.value.orchestrationProtocolVersion != T3Constants.ORCHESTRATION_PROTOCOL_VERSION) {
                return ApiResult.ProtocolMismatch(env.value.orchestrationProtocolVersion)
            }
        }
        return result
    }

    private fun <T> execute(request: Request, parse: (String) -> T): ApiResult<T> = try {
        client.newCall(request).execute().use { response ->
            when (response.code) {
                in 200..299 -> {
                    val body = response.body?.string().orEmpty()
                    try {
                        ApiResult.Ok(parse(body))
                    } catch (e: SerializationException) {
                        ApiResult.Failed("Unexpected response from server")
                    } catch (e: IllegalArgumentException) {
                        ApiResult.Failed("Unexpected response from server")
                    }
                }
                401, 403 -> ApiResult.Unauthorized
                426 -> ApiResult.ProtocolMismatch(null)
                else -> ApiResult.Failed("HTTP ${response.code}")
            }
        }
    } catch (e: IOException) {
        ApiResult.Offline(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        fun resolve(baseUrl: String, path: String): HttpUrl? {
            val base = normalizeBaseUrl(baseUrl)?.toHttpUrlOrNull() ?: return null
            return base.newBuilder().encodedPath(path).build()
        }

        /** Accepts "host", "host:3773", "http://host", "https://host/" etc. */
        fun normalizeBaseUrl(input: String): String? {
            val trimmed = input.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = if ("://" in trimmed) {
                trimmed
            } else {
                // Bare host: assume the default T3 port over plain HTTP (LAN / tailnet IP).
                val hasPort = Regex("""^[^/]*:\d+""").containsMatchIn(trimmed)
                if (hasPort) "http://$trimmed" else "http://$trimmed:${T3Constants.DEFAULT_PORT}"
            }
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (url.scheme != "http" && url.scheme != "https") return null
            return url.newBuilder().encodedPath("/").build().toString().trimEnd('/')
        }

        /**
         * Cleartext HTTP is only allowed to hosts that are on the local network or the tailnet
         * (whose traffic WireGuard already encrypts). Everything else must use HTTPS.
         */
        fun isCleartextAllowed(host: String): Boolean {
            val h = host.lowercase()
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".ts.net") || h.endsWith(".lan")) return true
            if (!h.contains(':') && h.none { it.isLetter() }) {
                val o = h.split('.').mapNotNull { it.toIntOrNull() }
                if (o.size != 4) return false
                return o[0] == 127 || o[0] == 10 ||
                    (o[0] == 192 && o[1] == 168) ||
                    (o[0] == 172 && o[1] in 16..31) ||
                    (o[0] == 169 && o[1] == 254) ||
                    (o[0] == 100 && o[1] in 64..127) // Tailscale CGNAT range
            }
            return h.startsWith("fd") || h == "::1" || h == "[::1]" // ULA / loopback IPv6
        }

        /** Returns an error message, or null if the URL is acceptable. */
        fun validateBaseUrl(input: String): String? {
            val normalized = normalizeBaseUrl(input) ?: return "Enter a URL like https://my-mac.tailnet.ts.net"
            val url = normalized.toHttpUrlOrNull() ?: return "Invalid URL"
            if (url.scheme == "http" && !isCleartextAllowed(url.host)) {
                return "Plain HTTP is only allowed for LAN / Tailscale addresses. Use HTTPS."
            }
            return null
        }
    }
}
