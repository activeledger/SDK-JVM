package io.activeledger.sdk

import io.activeledger.sdk.events.EventStream

/**
 * Entry point for Kotlin callers.
 *
 *     val ledger = Activeledger("http://localhost:5260")
 *     val identity = ledger.connection.onboard(KeyPair.generate(KeyType.ML_DSA_65))
 *
 * There is deliberately no storage endpoint here. An earlier version exposed
 * one, which was wrong: a node's storage service is reachable only from the
 * node's own host, so it is not something a client can use, and the
 * JavaScript SDK does not touch it at all.
 *
 * State is read the same way it is written - through a transaction. Name the
 * streams to read in `$r` (see `Transaction.Builder.readonly`) and have the
 * contract hand values back with `returnToRemote`, which arrive in
 * [LedgerResponse.responses].
 */
class Activeledger(baseUrl: String) {
    val connection: Connection = Connection(baseUrl)
    val events: EventStream = EventStream(connection)
}
