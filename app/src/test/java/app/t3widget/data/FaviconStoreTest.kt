package app.t3widget.data

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaviconStoreTest {
    private fun parse(s: String) = T3Json.parseToJsonElement(s).jsonObject

    @Test fun `reads the signed URL from a successful RPC exit`() {
        val msg = parse("""{"_tag":"Exit","requestId":"1","exit":{"_tag":"Success","value":{"relativeUrl":"/api/assets/tok.sig/v1-favicon.svg","expiresAt":1,"sourcePath":"public/favicon.svg"}}}""")
        assertEquals("/api/assets/tok.sig/v1-favicon.svg", FaviconStore.relativeUrlOf(msg))
    }

    @Test fun `typed failures mean no favicon`() {
        val msg = parse("""{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":[{"_tag":"Fail","error":{"_tag":"AssetWorkspaceRootNormalizationError"}}]}}""")
        assertEquals(FaviconStore.NO_FAVICON, FaviconStore.relativeUrlOf(msg))
    }

    @Test fun `defects and odd URLs yield null so they are retried`() {
        assertNull(FaviconStore.relativeUrlOf(parse("""{"_tag":"Exit","requestId":"1","exit":{"_tag":"Failure","cause":[{"_tag":"Die","defect":"boom"}]}}""")))
        assertNull(FaviconStore.relativeUrlOf(parse("""{"_tag":"Exit","requestId":"1","exit":{"_tag":"Success","value":{"relativeUrl":"https://evil/x"}}}""")))
    }
}
