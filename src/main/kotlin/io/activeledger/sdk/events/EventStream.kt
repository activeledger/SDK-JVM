package io.activeledger.sdk.events

import io.activeledger.sdk.Connection
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import okhttp3.Request
import okhttp3.Response

/** One server-sent event. */
data class LedgerEvent(val name: String?, val data: String, val id: String?)

/**
 * Server-sent event subscription, as a cold [Flow].
 *
 * A Flow rather than a listener because cancellation is the hard part: when a
 * collector stops, the underlying HTTP connection must close. With a callback
 * API that is the caller's job, and it is the thing they forget - the symptom
 * being a node holding open connections for subscribers that went away.
 * `awaitClose` makes it structural instead.
 *
 * The parser is hand-written rather than pulled from a library because the
 * SSE framing rules that matter here are few and specific: multiple `data:`
 * lines in one event concatenate with newlines, a line starting `:` is a
 * comment (used as a heartbeat and easy to mistake for an event), and a blank
 * line dispatches.
 */
class EventStream internal constructor(private val connection: Connection) {

    fun subscribe(path: String = "/events"): Flow<LedgerEvent> = callbackFlow {
        val request = Request.Builder()
            .url(connection.url(path))
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        val call = connection.httpClient().newCall(request)
        var response: Response? = null

        try {
            response = call.execute()
            val source = response.body?.source()
                ?: throw IllegalStateException("Event stream returned no body")

            var eventName: String? = null
            var eventId: String? = null
            val data = StringBuilder()

            while (!source.exhausted()) {
                val line = source.readUtf8LineStrict()

                when {
                    // Blank line dispatches whatever has accumulated.
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            trySend(LedgerEvent(eventName, data.toString().removeSuffix("\n"), eventId))
                            data.setLength(0)
                            eventName = null
                            eventId = null
                        }
                    }
                    // Comment or heartbeat. Ignored deliberately: emitting
                    // these as events is a classic SSE bug, and heartbeats
                    // are frequent.
                    line.startsWith(":") -> Unit
                    line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
                    line.startsWith("id:") -> eventId = line.removePrefix("id:").trim()
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
                }
            }
            close()
        } catch (e: Exception) {
            close(e)
        }

        awaitClose {
            // The whole reason this is a Flow. Cancelling the collector
            // closes the connection rather than leaving the node holding it.
            call.cancel()
            response?.close()
        }
    }.flowOn(Dispatchers.IO)
}
