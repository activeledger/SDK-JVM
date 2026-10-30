@file:Suppress("DEPRECATION")

package io.activeledger.sdk.events

import io.activeledger.sdk.Activeledger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What a subscriber sees when the connection or the stream fails, and what is sent. */
class EventStreamFailureTest {

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

    private fun ledger() = Activeledger(server.url("/").toString())

    @Test
    fun `an unreachable node fails the flow with an IOException`() {
        val dead = Activeledger(server.url("/").toString())
        server.shutdown()
        assertFailsWith<IOException> {
            runBlocking { withTimeout(10_000) { dead.events.subscribe().toList() } }
        }
    }

    @Test
    fun `a connection dropped before any reply fails the flow`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertFailsWith<IOException> {
            runBlocking { withTimeout(10_000) { ledger().events.subscribe().toList() } }
        }
    }

    // Events already delivered are not lost when the stream then breaks; the
    // failure arrives after them rather than instead of them.
    @Test
    fun `a stream cut mid-event delivers the complete events then fails`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: whole\n\ndata: " + "x".repeat(200))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val seen = mutableListOf<String>()
        assertFailsWith<IOException> {
            runBlocking {
                withTimeout(10_000) {
                    ledger().events.subscribe().collect { seen.add(it.data) }
                }
            }
        }
        assertEquals(listOf("whole"), seen)
    }

    @Test
    fun `the request is a GET for the default events path asking for an event stream`() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        withTimeout(5000) { ledger().events.subscribe().toList() }
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/events", request.path)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("no-cache", request.getHeader("Cache-Control"))
    }

    @Test
    fun `a custom path is requested instead of the default`() = runBlocking {
        server.enqueue(MockResponse().setBody("data: x\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe("/feed/contracts").toList() }
        assertEquals("/feed/contracts", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
        assertEquals(listOf("x"), events.map { it.data })
    }

    @Test
    fun `a base url with a trailing slash does not double the slash`() = runBlocking {
        server.enqueue(MockResponse().setBody(""))
        withTimeout(5000) { Activeledger(server.url("/").toString() + "/").events.subscribe().toList() }
        assertEquals("/events", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    // A field line with no colon, an unknown field and a retry hint carry no
    // data, so they must not dispatch an event of their own.
    @Test
    fun `unknown fields and a bare blank line produce no events`() = runBlocking {
        server.enqueue(MockResponse().setBody("retry: 3000\nfoo: bar\n\n\n\ndata: kept\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals(listOf("kept"), events.map { it.data })
    }

    @Test
    fun `an event with a name but no data is not dispatched`() = runBlocking {
        server.enqueue(MockResponse().setBody("event: ping\nid: 7\n\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `CRLF framed events are read`() = runBlocking {
        server.enqueue(MockResponse().setBody("event: commit\r\ndata: crlf\r\n\r\n"))
        val events = withTimeout(5000) { ledger().events.subscribe().toList() }
        assertEquals(1, events.size)
        assertEquals("crlf", events[0].data)
        assertEquals("commit", events[0].name)
    }
}
