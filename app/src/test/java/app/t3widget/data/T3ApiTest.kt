package app.t3widget.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class T3ApiTest {
    private lateinit var server: MockWebServer
    private val api = T3Api()
    private val base get() = server.url("/").toString()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `shell request sends token, protocol header and omit flags but never resumeFrom`() {
        server.enqueue(MockResponse().setBody("""{"schemaVersion":2,"projects":[],"threads":[]}"""))
        val result = api.fetchShell(base, "tok123")
        assertTrue(result is ApiResult.Ok)
        val req = server.takeRequest()
        assertEquals("/api/orchestration/shell", req.requestUrl!!.encodedPath)
        assertEquals("Bearer tok123", req.getHeader("Authorization"))
        assertEquals("2", req.getHeader("x-t3-orchestration-protocol"))
        assertEquals("true", req.requestUrl!!.queryParameter("omitIdleSubagents"))
        assertEquals("true", req.requestUrl!!.queryParameter("omitOlderSettled"))
        assertNull(req.requestUrl!!.queryParameter("resumeFrom"))
    }

    @Test fun `401 maps to Unauthorized`() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiResult.Unauthorized, api.fetchShell(base, "x"))
    }

    @Test fun `426 maps to ProtocolMismatch`() {
        server.enqueue(MockResponse().setResponseCode(426))
        assertTrue(api.fetchShell(base, "x") is ApiResult.ProtocolMismatch)
    }

    @Test fun `empty 400 plus bumped protocol on probe maps to ProtocolMismatch`() {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setBody("""{"serverVersion":"0.2.0","orchestrationProtocolVersion":3}"""))
        assertEquals(ApiResult.ProtocolMismatch(3), api.fetchShell(base, "x"))
    }

    @Test fun `400 with matching protocol stays a plain failure`() {
        server.enqueue(MockResponse().setResponseCode(400))
        server.enqueue(MockResponse().setBody("""{"orchestrationProtocolVersion":2}"""))
        assertEquals(ApiResult.Failed("HTTP 400"), api.fetchShell(base, "x"))
    }

    @Test fun `garbage body is a failure, not a crash`() {
        server.enqueue(MockResponse().setBody("<html>proxy login</html>"))
        assertTrue(api.fetchShell(base, "x") is ApiResult.Failed)
    }

    @Test fun `unreachable server is Offline`() {
        val url = base
        server.shutdown()
        assertTrue(api.fetchShell(url, "x") is ApiResult.Offline)
    }

    @Test fun `url normalization`() {
        assertEquals("http://192.168.1.5:3773", T3Api.normalizeBaseUrl("192.168.1.5"))
        assertEquals("http://192.168.1.5:8080", T3Api.normalizeBaseUrl(" 192.168.1.5:8080/ "))
        assertEquals("https://mac.tail1.ts.net", T3Api.normalizeBaseUrl("https://mac.tail1.ts.net/"))
        assertEquals("https://mac.tail1.ts.net", T3Api.normalizeBaseUrl("https://mac.tail1.ts.net/some/path"))
        assertNull(T3Api.normalizeBaseUrl(""))
        assertNull(T3Api.normalizeBaseUrl("ftp://x"))
    }

    @Test fun `cleartext only for private hosts`() {
        assertNull(T3Api.validateBaseUrl("192.168.1.5"))
        assertNull(T3Api.validateBaseUrl("http://100.101.102.103:3773"))
        assertNull(T3Api.validateBaseUrl("http://10.0.2.2:3773"))
        assertNull(T3Api.validateBaseUrl("http://my-mac.local:3773"))
        assertNull(T3Api.validateBaseUrl("https://example.com"))
        assertNotNull(T3Api.validateBaseUrl("http://example.com"))
        assertNotNull(T3Api.validateBaseUrl("http://8.8.8.8"))
        assertFalse(T3Api.isCleartextAllowed("172.32.0.1"))
    }
}
