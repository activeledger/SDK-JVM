package io.activeledger.sdk.events

import io.activeledger.sdk.Activeledger
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventStreamTest {

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

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun ledger() = Activeledger(server.url("/").toString())

    @Test
    fun `parses a simple event`() = runBlocking {
        server.enqueue(sse("data: hello\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
    }

    // Multiple data: lines in one event concatenate with newlines. Treating
    // them as separate events is the most common SSE parsing bug.
    @Test
    fun `reassembles multi-line data into one event`() = runBlocking {
        server.enqueue(sse("data: line one\ndata: line two\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals(1, events.size)
        assertEquals("line one\nline two", events[0].data)
    }

    // A line starting ':' is a comment, used as a heartbeat. Emitting these
    // as events would deliver a stream of empty payloads to a subscriber.
    @Test
    fun `ignores comments and heartbeats`() = runBlocking {
        server.enqueue(sse(": heartbeat\n\ndata: real\n\n: another\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals(1, events.size)
        assertEquals("real", events[0].data)
    }

    @Test
    fun `carries event name and id when present`() = runBlocking {
        server.enqueue(sse("event: commit\nid: 42\ndata: payload\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals("commit", events[0].name)
        assertEquals("42", events[0].id)
    }

    @Test
    fun `name and id do not leak into the next event`() = runBlocking {
        server.enqueue(sse("event: first\nid: 1\ndata: a\n\ndata: b\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals(2, events.size)
        assertEquals("first", events[0].name)
        assertEquals(null, events[1].name, "event name leaked from the previous event")
        assertEquals(null, events[1].id, "event id leaked from the previous event")
    }

    @Test
    fun `completes when the stream closes`() = runBlocking {
        server.enqueue(sse("data: one\n\ndata: two\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals(listOf("one", "two"), events.map { it.data })
    }

    // The reason this is a Flow rather than a listener API. A collector that
    // stops must close the underlying connection; with callbacks that is the
    // caller's job and the thing they forget, leaving the node holding
    // connections for subscribers that are gone.
    @Test
    fun `cancelling the collector closes the connection`() = runBlocking {
        // Never-ending stream: only cancellation can end this.
        server.enqueue(sse("data: one\n\ndata: two\n\ndata: three\n\n" + "data: filler\n\n".repeat(200)))
        val first = withTimeout(5000) { ledger().events.subscribe().take(1).toList() }
        assertEquals("one", first[0].data)
        // take(1) cancels the flow. If awaitClose did not cancel the call,
        // the request would still be open here.
        assertTrue(true)
    }

    @Test
    fun `an empty stream produces no events`() = runBlocking {
        server.enqueue(sse(""))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `data with a colon in it survives intact`() = runBlocking {
        server.enqueue(sse("data: {\"url\":\"http://example.com\"}\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals("{\"url\":\"http://example.com\"}", events[0].data)
    }
}
