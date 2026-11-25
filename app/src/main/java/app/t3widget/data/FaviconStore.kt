package app.t3widget.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Project favicons, as T3 Code shows them for projects without a custom icon.
 *
 * The server only signs favicon URLs over its WebSocket RPC (`assets.createUrl` with a
 * `project-favicon` resource, which an orchestration:read token may call). We open one short-lived
 * socket per sync, only for projects whose cached result is stale, then download, rasterize (SVG,
 * PNG, ICO, …) and cache each as a small PNG. "No favicon" is cached too.
 */
class FaviconStore internal constructor(
    private val dir: File,
    private val client: OkHttpClient = T3Api.defaultClient,
) {
    constructor(context: Context) : this(File(context.filesDir, "favicons"))

    init {
        dir.mkdirs()
    }

    /** The cached PNG for [workspaceRoot], or null if it has none (or none fetched yet). */
    fun file(workspaceRoot: String): File? = pngFile(workspaceRoot).takeIf { it.isFile }

    fun loadBitmap(workspaceRoot: String): Bitmap? =
        file(workspaceRoot)?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }

    /** Best effort: failures leave the previous cache in place. Call off the main thread. */
    suspend fun refresh(config: ServerConfig, workspaceRoots: Collection<String>) {
        val now = System.currentTimeMillis()
        val stale = workspaceRoots.distinct().filter { root ->
            val checked = maxOf(pngFile(root).lastModified(), missingFile(root).lastModified())
            now - checked > TTL_MS
        }
        if (stale.isEmpty()) return
        val urls = mintUrls(config, stale) ?: return
        for ((root, relativeUrl) in urls) {
            if (relativeUrl == null) continue // RPC failed for this one; retry next sync
            if (relativeUrl == NO_FAVICON || relativeUrl.endsWith(T3Constants.FAVICON_MISSING_SUFFIX)) {
                markMissing(root)
                continue
            }
            val bitmap = download(config.baseUrl, relativeUrl) ?: continue
            pngFile(root).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            missingFile(root).delete()
        }
    }

    private fun markMissing(root: String) {
        pngFile(root).delete()
        missingFile(root).apply { writeText("") ; setLastModified(System.currentTimeMillis()) }
    }

    /** workspaceRoot → signed relative URL (null if that request failed); null if the socket failed. */
    internal suspend fun mintUrls(config: ServerConfig, roots: List<String>): Map<String, String?>? {
        val ticket = fetchTicket(config) ?: return null
        val wsUrl = T3Api.resolve(config.baseUrl, T3Constants.WS_PATH)?.newBuilder()
            ?.addQueryParameter("wsTicket", ticket)
            ?.addQueryParameter("orchestrationProtocol", T3Constants.ORCHESTRATION_PROTOCOL_VERSION.toString())
            ?.build() ?: return null

        val pending = ConcurrentHashMap<String, CompletableDeferred<String?>>()
        roots.forEachIndexed { i, _ -> pending[(i + 1).toString()] = CompletableDeferred() }
        val opened = CompletableDeferred<Boolean>()

        val socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.complete(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { T3Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (msg["_tag"]?.jsonPrimitive?.content) {
                    "Ping" -> webSocket.send("""{"_tag":"Pong"}""")
                    "Exit" -> {
                        val id = msg["requestId"]?.jsonPrimitive?.content ?: return
                        pending[id]?.complete(relativeUrlOf(msg))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.complete(false)
                pending.values.forEach { it.complete(null) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                pending.values.forEach { it.complete(null) }
            }
        })

        return try {
            if (withTimeoutOrNull(TIMEOUT_MS) { opened.await() } != true) return null
            roots.forEachIndexed { i, root -> socket.send(request(id = (i + 1).toString(), cwd = root)) }
            roots.mapIndexed { i, root ->
                root to withTimeoutOrNull(TIMEOUT_MS) { pending.getValue((i + 1).toString()).await() }
            }.toMap()
        } finally {
            socket.close(1000, null)
        }
    }

    private fun fetchTicket(config: ServerConfig): String? {
        val url = T3Api.resolve(config.baseUrl, T3Constants.WS_TICKET_PATH) ?: return null
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer ${config.token}")
            .header(T3Constants.ORCHESTRATION_PROTOCOL_HEADER, T3Constants.ORCHESTRATION_PROTOCOL_VERSION.toString())
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                T3Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["ticket"]?.jsonPrimitive?.content
            }
        }.getOrNull()
    }

    private fun download(baseUrl: String, relativeUrl: String): Bitmap? {
        val url = T3Api.resolve(baseUrl, relativeUrl) ?: return null
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val type = r.header("Content-Type").orEmpty()
                val bytes = r.body?.bytes() ?: return null
                if (bytes.size > MAX_BYTES) return null
                rasterize(bytes, isSvg = "svg" in type || relativeUrl.endsWith(".svg"))
            }
        }.getOrNull()
    }

    private fun sha(root: String): String =
        MessageDigest.getInstance("SHA-1").digest(root.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun pngFile(root: String) = File(dir, "${sha(root)}-$SIZE_PX.png")
    private fun missingFile(root: String) = File(dir, "${sha(root)}.missing")

    companion object {
        /** Favicons rarely change; re-check a few times a day. */
        private const val TTL_MS = 6L * 60 * 60 * 1000
        private const val TIMEOUT_MS = 10_000L
        private const val MAX_BYTES = 2 * 1024 * 1024
        const val SIZE_PX = 144 // 36dp at ~4x density

        private fun request(id: String, cwd: String): String = buildJsonObject {
            put("_tag", "Request")
            put("id", id)
            put("tag", T3Constants.RPC_ASSETS_CREATE_URL)
            putJsonObject("payload") {
                putJsonObject("resource") {
                    put("_tag", "project-favicon")
                    put("cwd", cwd)
                }
            }
            put("headers", buildJsonArray { })
        }.toString()

        /** The server answered with a typed error (e.g. the folder no longer exists): treat as no favicon. */
        internal const val NO_FAVICON = ""

        /**
         * `{"_tag":"Exit","exit":{"_tag":"Success","value":{"relativeUrl":…}}}` → relativeUrl;
         * a typed `Fail` → [NO_FAVICON]; anything else (defects, odd shapes) → null, retried next sync.
         */
        internal fun relativeUrlOf(msg: JsonObject): String? {
            val exit = msg["exit"] as? JsonObject ?: return null
            if (exit["_tag"]?.jsonPrimitive?.content == "Failure") {
                val causes = exit["cause"] as? kotlinx.serialization.json.JsonArray ?: return null
                val typed = causes.isNotEmpty() && causes.all { (it as? JsonObject)?.get("_tag")?.jsonPrimitive?.content == "Fail" }
                return if (typed) NO_FAVICON else null
            }
            if (exit["_tag"]?.jsonPrimitive?.content != "Success") return null
            val value = exit["value"] as? JsonObject ?: return null
            return value["relativeUrl"]?.jsonPrimitive?.content?.takeIf { it.startsWith("/api/assets/") }
        }

        /** Fits the image into a transparent [SIZE_PX] square. */
        fun rasterize(bytes: ByteArray, isSvg: Boolean): Bitmap? {
            val out = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            if (isSvg) {
                val svg = runCatching { SVG.getFromString(String(bytes)) }.getOrNull() ?: return null
                if (svg.documentViewBox == null && svg.documentWidth > 0 && svg.documentHeight > 0) {
                    svg.setDocumentViewBox(0f, 0f, svg.documentWidth, svg.documentHeight)
                }
                svg.setDocumentWidth("100%")
                svg.setDocumentHeight("100%")
                runCatching { svg.renderToCanvas(canvas, RectF(0f, 0f, SIZE_PX.toFloat(), SIZE_PX.toFloat())) }
                    .onFailure { return null }
                return out
            }
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scale = minOf(SIZE_PX.toFloat() / src.width, SIZE_PX.toFloat() / src.height)
            val w = src.width * scale
            val h = src.height * scale
            val left = (SIZE_PX - w) / 2
            val top = (SIZE_PX - h) / 2
            canvas.drawBitmap(src, null, RectF(left, top, left + w, top + h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }
}
