package io.activeledger.sdk

import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.crypto.KeyType
import io.activeledger.sdk.tx.Transaction
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectionTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun reply(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun connection() = Connection(server.url("/").toString())

    @Test
    fun `parses new streams, in the order returned`() {
        val r = LedgerResponse("""{"${'$'}streams":{"new":[{"id":"aaa"},{"id":"bbb"}]}}""")
        assertEquals(listOf("aaa", "bbb"), r.newStreams)
    }

    @Test
    fun `a response without streams or summary has none and is committed`() {
        val r = LedgerResponse("{}")
        assertEquals(emptyList(), r.newStreams)
        assertEquals(emptyList(), r.errors)
        assertEquals(emptyList(), r.responses())
        assertTrue(r.committed)
    }

    // The ledger answers HTTP 200 for a rejected transaction, so success is
    // decided by the body.
    @Test
    fun `errors in the summary mean not committed`() {
        val r = LedgerResponse("""{"${'$'}summary":{"errors":["1220: Signature Incorrect","other"]}}""")
        assertEquals(listOf("1220: Signature Incorrect", "other"), r.errors)
        assertTrue(!r.committed)
    }

    @Test
    fun `an empty errors array is still committed`() {
        assertTrue(LedgerResponse("""{"${'$'}summary":{"errors":[]}}""").committed)
    }

    @Test
    fun `contract responses are returned as objects`() {
        val r = LedgerResponse("""{"${'$'}responses":[{"balance":5},{"name":"x"}]}""")
        val responses = r.responses()
        assertEquals(2, responses.size)
        assertEquals(5, responses[0].get("balance").asInt)
        assertEquals("x", responses[1].get("name").asString)
    }

    @Test
    fun `non-object entries in responses are skipped`() {
        val r = LedgerResponse("""{"${'$'}responses":["text",7,{"ok":true}]}""")
        assertEquals(1, r.responses().size)
    }

    @Test
    fun `toString is the raw body`() {
        assertEquals("{\"a\":1}", LedgerResponse("{\"a\":1}").toString())
    }

    @Test
    fun `submit posts the transaction json to the root path`() = runBlocking {
        server.enqueue(reply("{}"))
        val tx = Transaction.onboard(KeyPair.generate(KeyType.SECP256K1))
        connection().submit(tx)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/", request.path)
        assertEquals(tx.toJson(), request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
    }

    @Test
    fun `submitRaw sends the string untouched`() = runBlocking {
        server.enqueue(reply("{}"))
        val raw = """{ "spaced" :  "as given", "z":1,"a":2 }"""
        connection().submitRaw(raw)
        assertEquals(raw, server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a trailing slash on the base url does not double up`() = runBlocking {
        server.enqueue(reply("{}"))
        Connection(server.url("/").toString().trimEnd('/') + "//").submitRaw("{}")
        assertEquals("/", server.takeRequest().path)
    }

    @Test
    fun `an http 200 carrying errors is returned, not thrown`() = runBlocking {
        server.enqueue(reply("""{"${'$'}summary":{"errors":["rejected"]}}"""))
        val r = connection().submitRaw("{}")
        assertTrue(!r.committed)
        assertEquals(listOf("rejected"), r.errors)
    }

    @Test
    fun `onboard returns the identity with the new stream id and the same key`() = runBlocking {
        server.enqueue(reply("""{"${'$'}streams":{"new":[{"id":"stream-1"}]}}"""))
        val key = KeyPair.generate(KeyType.FALCON_512)
        val identity = connection().onboard(key)
        assertEquals("stream-1", identity.streamId)
        assertTrue(identity.keyPair === key)
        // What went over the wire is the onboarding transaction for that key.
        assertTrue(server.takeRequest().body.readUtf8().contains(key.exportPublic()))
    }

    @Test
    fun `onboard throws, naming the response, when no stream was created`() {
        server.enqueue(reply("""{"${'$'}summary":{"errors":["1220: Signature Incorrect"]}}"""))
        val e = assertFailsWith<IllegalStateException> {
            runBlocking { connection().onboard(KeyPair.generate(KeyType.SECP256K1)) }
        }
        assertTrue(e.message!!.contains("Onboard failed"))
        assertTrue(e.message!!.contains("1220"))
    }

    @Test
    fun `an unreachable node surfaces as an exception`() {
        val url = server.url("/").toString()
        server.shutdown()
        assertFailsWith<java.io.IOException> { runBlocking { Connection(url).submitRaw("{}") } }
    }

    @Test
    fun `a body that is not a JSON object is reported as not committed without throwing`() {
        for (body in listOf("", "<html>502 Bad Gateway</html>", "null", "[]", "42", "\"text\"")) {
            val r = LedgerResponse(body)
            assertEquals(false, r.committed, body)
            assertTrue(r.errors.isNotEmpty(), body)
            assertEquals(emptyList(), r.newStreams, body)
            assertEquals(emptyList(), r.responses(), body)
        }
    }

    @Test
    fun `a malformed summary does not make the getters throw`() {
        val r = LedgerResponse("""{"${'$'}summary":{"errors":"oops"},"${'$'}streams":{"new":[5]}}""")
        assertEquals(false, r.committed)
        assertEquals(emptyList(), r.newStreams)
    }

    @Test
    fun `a non-2xx status is not committed even with an empty object body`() {
        server.enqueue(reply("{}").setResponseCode(502))
        val r = runBlocking { connection().submitRaw("{}") }
        assertEquals(false, r.committed)
        assertTrue(r.errors.any { it.contains("502") }, r.errors.toString())
    }

    @Test
    fun `an HTML 502 from submitRaw is reported rather than thrown`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        val r = runBlocking { connection().submitRaw("{}") }
        assertEquals(false, r.committed)
    }

    @Test
    fun `a ledger rejection delivered as HTTP 200 still lists its errors`() {
        server.enqueue(reply("""{"${'$'}summary":{"errors":["1220: Signature Incorrect"]}}"""))
        val r = runBlocking { connection().submitRaw("{}") }
        assertEquals(listOf("1220: Signature Incorrect"), r.errors)
    }

    @Test
    fun `onboard throws a clear error on an HTML 502`() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))
        assertFailsWith<IllegalStateException> {
            runBlocking { connection().onboard(KeyPair.generate(KeyType.SECP256K1)) }
        }
    }
}
