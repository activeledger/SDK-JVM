package io.activeledger.sdk

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class ConnectionGetTest {

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

    private fun base() = server.url("/").toString()

    @Test
    fun `get issues a GET with no body and returns the response text`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello node"))
        val text = Connection(base()).get("/status")
        assertEquals("hello node", text)
        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/status", request.path)
        assertEquals(0L, request.bodySize)
    }

    @Test
    fun `get does not double the slash when the base url ends with one`() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        Connection(base() + "//").get("/a/b")
        assertEquals("/a/b", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `get keeps the query string`() = runBlocking {
        server.enqueue(MockResponse().setBody("x"))
        Connection(base()).get("/q?a=1&b=2")
        assertEquals("/q?a=1&b=2", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `get of an empty body is the empty string`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals("", Connection(base()).get("/nothing"))
    }

    @Test
    fun `get of an unreachable node is an IOException`() {
        val connection = Connection(base())
        server.shutdown()
        assertFailsWith<IOException> { runBlocking { connection.get("/") } }
    }

    @Test
    fun `httpClient returns the client the connection was built with`() {
        val client = OkHttpClient.Builder().callTimeout(7, TimeUnit.SECONDS).build()
        val connection = Connection(base(), client)
        assertSame(client, connection.httpClient())
        assertEquals(7_000, connection.httpClient().callTimeoutMillis)
    }

    @Test
    fun `a supplied client is the one that carries the requests`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Probe", "from-supplied-client").build())
            }
            .build()
        Connection(base(), client).submitRaw("{}")
        assertEquals("from-supplied-client", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-Probe"))
    }

    @Test
    fun `each default connection gets its own client`() {
        assertNotSame(Connection(base()).httpClient(), Connection(base()).httpClient())
    }
}
