package io.activeledger.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads activity stream state and metadata.
 *
 * **These reads go to the storage endpoint, not the node.** A node's HTTP
 * port answers "Forbidden" for a stream read - it accepts transactions and
 * serves events, and that is all. Storage is a separate service on its own
 * port, usually not exposed publicly, which is why it is a separate
 * constructor argument rather than derived from the node URL.
 *
 * Found by running against a real network: an earlier version of this class
 * used the node's base URL and a `/api/stream/{id}` path borrowed from a
 * gateway, which a plain node does not serve.
 *
 * `:stream` holds the meta document - authorities, umid, origin - and the
 * bare id holds the contract-controlled state. Anything wanting an identity's
 * keys wants [meta], not [state].
 */
class ActivityStreams internal constructor(
    private val storageUrl: String?,
    private val client: OkHttpClient,
    private val database: String = "activeledger",
) {

    suspend fun state(streamId: String): String = read(streamId)

    suspend fun meta(streamId: String): String = read("$streamId:stream")

    suspend fun volatile(streamId: String): String = read("$streamId:volatile")

    private suspend fun read(id: String): String = withContext(Dispatchers.IO) {
        val base = storageUrl ?: throw IllegalStateException(
            "No storage URL configured. Stream reads go to the storage endpoint, " +
                "not the node - construct Activeledger(baseUrl, storageUrl) to use them."
        )
        val request = Request.Builder()
            .url(base.trimEnd('/') + "/" + database + "/" + java.net.URLEncoder.encode(id, "UTF-8"))
            .get()
            .build()
        client.newCall(request).execute().use { it.body?.string().orEmpty() }
    }
}
