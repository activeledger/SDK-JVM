package io.activeledger.sdk

import io.activeledger.sdk.events.EventStream

/**
 * Entry point for Kotlin callers.
 *
 *     val ledger = Activeledger("http://localhost:5260")
 *     val identity = ledger.connection.onboard(KeyPair.generate(KeyType.ML_DSA_65))
 */
class Activeledger @JvmOverloads constructor(
    baseUrl: String,
    val connection: Connection = Connection(baseUrl),
) {
    val events: EventStream = EventStream(connection)
    val streams: ActivityStreams = ActivityStreams(connection)
}
