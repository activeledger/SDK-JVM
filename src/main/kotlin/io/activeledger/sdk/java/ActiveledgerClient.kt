@file:Suppress("DEPRECATION")

package io.activeledger.sdk.java

import io.activeledger.sdk.Activeledger
import io.activeledger.sdk.Identity
import io.activeledger.sdk.LedgerResponse
import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.events.LedgerEvent
import io.activeledger.sdk.tx.Transaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Receives events on a subscription. Deprecated - see [subscribe]. */
@Deprecated("Events are served on the node's host only; ActiveCore is deprecated. Run your own server-sent events listener on the node's host. Removed in the next major version.")
fun interface EventListener {
    fun onEvent(event: LedgerEvent)
}

/** Cancels a subscription and closes its underlying connection. */
interface Subscription : AutoCloseable

/**
 * Blocking client for Java callers.
 *
 * The Kotlin API is suspend functions and Flow, which is right for Kotlin and
 * genuinely unpleasant from Java - a suspend function compiles to a method
 * taking a Continuation, which no Java caller wants to see. This is a thin
 * wrapper over the same implementation, not a second one.
 *
 * Tested from real Java sources rather than from Kotlin, because that is the
 * only way the things that break Java interop - nullability, @JvmStatic,
 * default arguments, suspend bridging - actually show up.
 */
class ActiveledgerClient(baseUrl: String) : AutoCloseable {
    private val ledger = Activeledger(baseUrl)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onboard(keyPair: KeyPair): Identity = runBlocking { ledger.connection.onboard(keyPair) }

    fun submit(transaction: Transaction): LedgerResponse =
        runBlocking { ledger.connection.submit(transaction) }

    /**
     * Submits a pre-built envelope as raw JSON.
     *
     * There is no stream-read method here. State is read through a
     * transaction: name the streams in `$r` via Transaction.Builder.readonly
     * and have the contract return values with returnToRemote, which arrive
     * in LedgerResponse.responses.
     */
    fun submitRaw(json: String): LedgerResponse = runBlocking { ledger.connection.submitRaw(json) }

    /**
     * Subscribes to events, delivering them to [listener].
     *
     * Closing the returned [Subscription] cancels collection, which closes
     * the HTTP connection - the same guarantee the Flow gives Kotlin callers,
     * rather than leaving it to the caller to remember.
     *
     * @deprecated ActiveCore is deprecated and no longer serves events; a
     * node's own events feed is reachable only from the node's host. Run your
     * own server-sent events listener there. Removed in the next major
     * version.
     */
    @Deprecated("Events are served on the node's host only; ActiveCore is deprecated. Run your own server-sent events listener on the node's host. Removed in the next major version.")
    @JvmOverloads
    fun subscribe(listener: EventListener, path: String = "/events"): Subscription {
        val job = scope.launch {
            ledger.events.subscribe(path).collect { listener.onEvent(it) }
        }
        return object : Subscription {
            override fun close() {
                job.cancel()
            }
        }
    }

    override fun close() {
        scope.cancel()
    }
}
