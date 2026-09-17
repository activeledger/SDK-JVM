package io.activeledger.sdk

import com.google.gson.JsonParser
import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.tx.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** The ledger's reply to a submitted transaction. */
class LedgerResponse internal constructor(val raw: String) {
    private val root by lazy { JsonParser.parseString(raw).asJsonObject }

    /** Stream ids created by this transaction, in the order returned. */
    val newStreams: List<String>
        get() = root.getAsJsonObject("\$streams")
            ?.getAsJsonArray("new")
            ?.map { it.asJsonObject.get("id").asString }
            ?: emptyList()

    /**
     * Errors the network reported.
     *
     * Present and non-empty means the transaction did **not** commit, even
     * though the HTTP status was 200. The ledger answers 200 for a rejected
     * transaction, so treating HTTP success as ledger success is wrong and
     * is a mistake worth failing loudly on rather than discovering later.
     */
    val errors: List<String>
        get() = root.getAsJsonObject("\$summary")
            ?.getAsJsonArray("errors")
            ?.map { it.asString }
            ?: emptyList()

    val committed: Boolean get() = errors.isEmpty()

    /** Values a contract returned via returnToRemote(). */
    fun responses(): List<com.google.gson.JsonObject> =
        root.getAsJsonArray("\$responses")?.mapNotNull { it as? com.google.gson.JsonObject } ?: emptyList()

    override fun toString(): String = raw
}

/**
 * A connection to one Activeledger node.
 *
 * Suspend functions throughout. The blocking facade for Java callers lives in
 * [io.activeledger.sdk.java.ActiveledgerClient].
 */
class Connection @JvmOverloads constructor(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    /** Submits a signed transaction. */
    suspend fun submit(transaction: Transaction): LedgerResponse = post("/", transaction.toJson())

    /**
     * Onboards a new identity and returns its stream id.
     *
     * Throws if the ledger rejected it, rather than returning an object whose
     * streamId is empty - an onboarding that silently produced no identity is
     * the kind of failure that surfaces three calls later.
     */
    suspend fun onboard(keyPair: KeyPair): Identity {
        val response = submit(Transaction.onboard(keyPair))
        val streamId = response.newStreams.firstOrNull()
            ?: throw IllegalStateException("Onboard failed: $response")
        return Identity(streamId, keyPair)
    }

    internal suspend fun post(path: String, body: String): LedgerResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(body.toRequestBody(json))
            .build()
        client.newCall(request).execute().use { response ->
            LedgerResponse(response.body?.string().orEmpty())
        }
    }

    internal suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).get().build()
        client.newCall(request).execute().use { it.body?.string().orEmpty() }
    }

    internal fun url(path: String): String = baseUrl.trimEnd('/') + path
    internal fun httpClient(): OkHttpClient = client

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // No read timeout cap on SSE - event streams are long-lived by
            // design, and a read timeout would close them mid-subscription.
            .build()
    }
}

/** An onboarded identity: its stream id and the key that controls it. */
data class Identity(val streamId: String, val keyPair: KeyPair)
