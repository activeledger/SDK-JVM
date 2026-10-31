package io.activeledger.sdk

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.tx.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The ledger's reply to a submitted transaction.
 *
 * A reply is committed only if the HTTP status is 2xx, the body is a JSON
 * object, it has a `$summary` object, and `$summary.errors` is absent or empty.
 * Anything else (an empty body, `{}`, an HTML error page from a proxy, `null`,
 * an array, a non-2xx status) is reported as not committed with an entry in
 * [errors]. The getters never throw.
 */
class LedgerResponse internal constructor(val raw: String, private val status: Int = 200) {
    private val root: JsonObject? by lazy {
        try {
            JsonParser.parseString(raw) as? JsonObject
        } catch (e: RuntimeException) {
            null
        }
    }

    private val httpOk get() = status in 200..299

    /** Stream ids created by this transaction, in the order returned. */
    val newStreams: List<String>
        get() = try {
            root?.getAsJsonObject("\$streams")
                ?.getAsJsonArray("new")
                ?.map { it.asJsonObject.get("id").asString }
                ?: emptyList()
        } catch (e: RuntimeException) {
            emptyList()
        }

    /**
     * Errors the network reported.
     *
     * Present and non-empty means the transaction did **not** commit. The
     * ledger answers HTTP 200 for a rejected transaction, so those arrive in
     * the body's `$summary`; a non-2xx status or a body that is not a JSON
     * object, or one without a `$summary` object, adds an entry here as well,
     * because none of those is a commit.
     */
    val errors: List<String>
        get() {
            val body = root
            val summary = try {
                body?.get("\$summary") as? JsonObject
            } catch (e: RuntimeException) {
                null
            }
            val reported = try {
                summary?.getAsJsonArray("errors")
                    ?.map { it.asString }
                    ?: emptyList()
            } catch (e: RuntimeException) {
                listOf("unreadable \$summary.errors in response")
            }
            val extra = ArrayList<String>()
            if (!httpOk) extra.add("HTTP $status")
            if (body == null) extra.add("response is not a JSON object")
            else if (summary == null) extra.add("response has no \$summary")
            return reported + extra
        }

    val committed: Boolean get() = errors.isEmpty()

    /** Values a contract returned via returnToRemote(). */
    fun responses(): List<JsonObject> =
        try {
            root?.getAsJsonArray("\$responses")?.mapNotNull { it as? JsonObject } ?: emptyList()
        } catch (e: RuntimeException) {
            emptyList()
        }

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
     * Submits a pre-built envelope as raw JSON.
     *
     * For an envelope built elsewhere, and for testing rejection paths - a
     * tampered body cannot be expressed through [submit], because the
     * builder would re-sign it and produce a valid transaction instead of
     * the invalid one under test.
     */
    suspend fun submitRaw(json: String): LedgerResponse = post("/", json)

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
            LedgerResponse(response.body?.string().orEmpty(), response.code)
        }
    }

    internal suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from GET $path: ${text.take(200)}")
            text
        }
    }

    internal fun url(path: String): String = baseUrl.trimEnd('/') + path
    fun httpClient(): OkHttpClient = client

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Ordinary calls time out after 30s. EventStream does not use
            // this: it derives a client with no read timeout, because a quiet
            // event stream is long-lived by design.
            .build()
    }
}

/** An onboarded identity: its stream id and the key that controls it. */
data class Identity(val streamId: String, val keyPair: KeyPair)
