package io.activeledger.sdk.tx

import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.json.CanonicalJson
import io.activeledger.sdk.json.JsonObject
import java.util.Base64

/**
 * A signed Activeledger transaction, ready to submit.
 *
 * [body] is the `$tx` object. [sigs] maps a signer label to a base64
 * signature over **the canonical bytes of `body` and nothing else** - not the
 * envelope, not a hash of it, not a length-prefixed form.
 */
class Transaction internal constructor(
    val body: JsonObject,
    val sigs: Map<String, String>,
    val selfSign: Boolean,
) {
    /** The full envelope, as submitted. */
    fun envelope(): JsonObject {
        val out = JsonObject().put("\$tx", body)
        if (selfSign) out.put("\$selfsign", true)
        val sigObject = JsonObject()
        sigs.forEach { (label, signature) -> sigObject.put(label, signature) }
        return out.put("\$sigs", sigObject)
    }

    fun toJson(): String = CanonicalJson.stringify(envelope())

    /** The exact bytes that were signed. Useful when debugging a 1220. */
    fun signedBytes(): ByteArray = CanonicalJson.bytes(body)

    companion object {

        /**
         * Builds the onboarding transaction for a new identity.
         *
         * Two things here are the most common first failure in a port, so
         * they are done in one place rather than left to a caller:
         *
         * - `$selfsign` is true, and `$sigs` is keyed by the `$i` **label**
         *   ("identity"), not by a stream id. There is no stream yet.
         * - `type` is always present. The ledger defaults a missing type to
         *   `"rsa"` and then attempts RSA verification against a base64
         *   post-quantum blob, which returns 1220 "Signature Incorrect" and
         *   says nothing about the key type.
         */
        @JvmStatic
        @JvmOverloads
        fun onboard(keyPair: KeyPair, label: String = "identity"): Transaction {
            val body = JsonObject()
                .put("\$namespace", "default")
                .put("\$contract", "onboard")
                .put(
                    "\$i",
                    JsonObject().put(
                        label,
                        JsonObject()
                            .put("type", keyPair.type.wire)
                            .put("publicKey", keyPair.exportPublic()),
                    ),
                )
                .put("\$o", JsonObject())

            return Transaction(body, mapOf(label to sign(keyPair, body)), selfSign = true)
        }

        @JvmStatic
        fun builder(): Builder = Builder()

        internal fun sign(keyPair: KeyPair, body: JsonObject): String =
            Base64.getEncoder().encodeToString(keyPair.sign(CanonicalJson.bytes(body)))
    }

    /**
     * Builds an ordinary transaction.
     *
     * Insertion order is preserved throughout, because the ledger does not
     * canonicalise key order and the signature covers the order actually
     * written.
     */
    class Builder internal constructor() {
        private var namespace: String? = null
        private var contract: String? = null
        private var entry: String? = null
        private val inputs = JsonObject()
        private val outputs = JsonObject()
        private val signers = LinkedHashMap<String, KeyPair>()

        fun namespace(value: String): Builder = apply { namespace = value }
        fun contract(value: String): Builder = apply { contract = value }
        fun entry(value: String): Builder = apply { entry = value }

        /** Adds an input stream, its signing key, and any payload fields. */
        @JvmOverloads
        fun input(streamId: String, keyPair: KeyPair, payload: Map<String, Any?> = emptyMap()): Builder = apply {
            inputs.put(streamId, toJsonObject(payload))
            signers[streamId] = keyPair
        }

        @JvmOverloads
        fun output(streamId: String, payload: Map<String, Any?> = emptyMap()): Builder = apply {
            outputs.put(streamId, toJsonObject(payload))
        }

        fun build(): Transaction {
            val ns = namespace ?: throw IllegalStateException("namespace is required")
            val con = contract ?: throw IllegalStateException("contract is required")
            require(signers.isNotEmpty()) { "at least one input with a signing key is required" }

            val body = JsonObject()
            entry?.let { body.put("\$entry", it) }
            body.put("\$namespace", ns).put("\$contract", con).put("\$i", inputs)
            if (outputs.keys.isNotEmpty()) body.put("\$o", outputs)

            val sigs = signers.mapValues { (_, key) -> sign(key, body) }
            return Transaction(body, sigs, selfSign = false)
        }

        private fun toJsonObject(payload: Map<String, Any?>): JsonObject {
            val out = JsonObject()
            payload.forEach { (key, value) ->
                when (value) {
                    null -> out.putNull(key)
                    is String -> out.put(key, value)
                    is Int -> out.put(key, value)
                    is Long -> out.put(key, value)
                    is Double -> out.put(key, value)
                    is Boolean -> out.put(key, value)
                    is JsonObject -> out.put(key, value)
                    else -> throw IllegalArgumentException(
                        "Unsupported payload value for '$key': ${value::class.java.name}. " +
                            "Build a JsonObject explicitly rather than relying on reflection - what gets " +
                            "signed is these exact bytes, so an inferred conversion would be a silent risk."
                    )
                }
            }
            return out
        }
    }
}
