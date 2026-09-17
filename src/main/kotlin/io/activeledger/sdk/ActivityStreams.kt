package io.activeledger.sdk

/**
 * Reads activity stream state and its metadata.
 *
 * `:stream` holds the meta document - authorities, umid, origin - and the
 * bare id holds the contract-controlled state. They are separate documents
 * and separate reads; a caller wanting the authorities on an identity wants
 * [meta], not [state].
 */
class ActivityStreams internal constructor(private val connection: Connection) {

    suspend fun state(streamId: String): String = connection.get("/api/stream/$streamId")

    suspend fun meta(streamId: String): String = connection.get("/api/stream/$streamId:stream")
}
