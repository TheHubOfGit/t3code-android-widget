package app.t3widget.data

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Manual check against a real server: T3_LIVE_URL, T3_LIVE_TOKEN_FILE, T3_LIVE_ROOTS (|-separated). Skipped otherwise. */
class FaviconLiveCheck {
    @Test fun `mints favicon URLs over the WebSocket RPC`() {
        val url = System.getenv("T3_LIVE_URL")
        val tokenFile = System.getenv("T3_LIVE_TOKEN_FILE")
        assumeTrue(url != null && tokenFile != null)
        val roots = System.getenv("T3_LIVE_ROOTS").split("|")
        val store = FaviconStore(kotlin.io.path.createTempDirectory().toFile())
        val result = runBlocking { store.mintUrls(ServerConfig(url!!, File(tokenFile!!).readText().trim()), roots) }
        result!!.forEach { (root, rel) -> println("LIVE ${root.substringAfterLast('/')} -> ${rel?.substringAfterLast('/')}") }
    }
}
