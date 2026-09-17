package io.activeledger.sdk.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Checks the serialiser against the published cross-language vectors.
 *
 * `CanonicalJsonTest` asserts this implementation matches my reading of
 * `JSON.stringify`. This asserts it matches bytes a real ledger has actually
 * accepted, produced by the reference implementation. If the two ever
 * disagree, this file is right and the other one is wrong.
 *
 * Rebuilds each vector's `$tx` from scratch and requires byte-identical
 * output. There is deliberately no normalisation, no trimming and no
 * whitespace tolerance: the signature covers these exact bytes.
 */
class VectorCanonicalisationTest {

    private val vectors: Map<String, String> by lazy {
        val json = javaClass.getResourceAsStream("/pq-vectors.json")
            ?.bufferedReader()?.readText()
            ?: error("pq-vectors.json missing from test resources")
        // Gson, test-only. An earlier version hand-rolled a regex to avoid
        // "using a JSON library to test a JSON library", which was muddled
        // reasoning - and it blew the stack with catastrophic backtracking
        // on a 1MB file. Reading the vectors is what a parser is for; the
        // rule is that nothing WRITES signed bytes with one.
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        root.getAsJsonArray("vectors").associate { element ->
            val v = element.asJsonObject
            v.get("messageName").asString to v.get("message").asString
        }
    }

    private fun expect(name: String): String {
        val v = vectors[name]
        assertNotNull(v, "vector '$name' missing - the published file lost a case")
        return v
    }

    @Test
    fun `ascii case matches the reference bytes`() {
        assertEquals(expect("ascii"), CanonicalJson.stringify(JsonObject().put("greeting", "hello")))
    }

    // If this fails, the serialiser is escaping non-ASCII. That is the
    // Python ensure_ascii bug, in Kotlin.
    @Test
    fun `non-ascii case matches the reference bytes`() {
        assertEquals(
            expect("non-ascii"),
            CanonicalJson.stringify(JsonObject().put("greeting", "café 日本語 ☕"))
        )
    }

    // If this fails, the serialiser is HTML-escaping. That is Go's default.
    @Test
    fun `html case matches the reference bytes`() {
        assertEquals(
            expect("html"),
            CanonicalJson.stringify(
                JsonObject().put("expr", "a < b && c > d").put("amp", "Tom & Jerry")
            )
        )
    }

    // If this fails, 1.0 is being written as "1.0" rather than "1".
    @Test
    fun `float case matches the reference bytes`() {
        assertEquals(
            expect("float"),
            CanonicalJson.stringify(
                JsonObject().put("whole", 1.0).put("third", 0.1).put("negative", -2.5).put("zero", 0)
            )
        )
    }

    // If this fails, keys are being sorted. The ledger does not canonicalise
    // key order, so the signer must reproduce insertion order.
    @Test
    fun `ordering case matches the reference bytes, unsorted`() {
        assertEquals(
            expect("ordering"),
            CanonicalJson.stringify(JsonObject().put("zebra", 1).put("alpha", 2).put("middle", 3))
        )
    }

    // The nested, realistic shape - and the one a port sends first.
    @Test
    fun `onboard case matches the reference bytes`() {
        val reference = expect("onboard")
        // The public key is generated per vector, so lift it out of the
        // reference rather than hardcoding a key that would go stale on the
        // next regeneration.
        val publicKey = Regex("\"publicKey\":\"([^\"]+)\"").find(reference)!!.groupValues[1]
        // The type is lifted out too, not hardcoded. The published file
        // carries an onboard vector per scheme and `associate` keeps the
        // last, so hardcoding "ml-dsa-65" compared it against the falcon
        // reference and failed on a difference that was entirely the test's.
        val keyType = Regex("\"type\":\"([^\"]+)\"").find(reference)!!.groupValues[1]
        val tx = JsonObject()
            .put("\$namespace", "default")
            .put("\$contract", "onboard")
            .put(
                "\$i",
                JsonObject().put(
                    "identity",
                    JsonObject().put("type", keyType).put("publicKey", publicKey)
                )
            )
            .put("\$o", JsonObject())
        assertEquals(reference, CanonicalJson.stringify(tx))
    }

    @Test
    fun `utf8 byte length matches the reference for the multibyte case`() {
        val reference = expect("non-ascii")
        val mine = CanonicalJson.bytes(
            JsonObject().put("greeting", "café 日本語 ☕")
        )
        assertEquals(reference.toByteArray(Charsets.UTF_8).size, mine.size)
    }
}
