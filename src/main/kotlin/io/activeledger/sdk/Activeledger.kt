package io.activeledger.sdk

import io.activeledger.sdk.events.EventStream

/**
 * Entry point for Kotlin callers.
 *
 *     val ledger = Activeledger("http://localhost:5260")
 *     val identity = ledger.connection.onboard(KeyPair.generate(KeyType.ML_DSA_65))
 *
 * [storageUrl] is optional and only needed for reading streams. It is a
 * separate service on its own port - a node will not serve stream reads - and
 * is often not publicly exposed, so most callers never set it.
 */
class Activeledger @JvmOverloads constructor(
    baseUrl: String,
    val storageUrl: String? = null,
) {
    val connection: Connection = Connection(baseUrl)
    val events: EventStream = EventStream(connection)
    val streams: ActivityStreams = ActivityStreams(storageUrl, connection.httpClient())
}
