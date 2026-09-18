package io.activeledger.sdk.crypto

import com.google.gson.JsonParser
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Conformance against the vectors published by the ledger repository.
 *
 * This is what makes "done" an observation rather than an assertion: these
 * signatures were produced by the reference implementation and accepted by a
 * real network, so agreeing with them is agreeing with the thing that matters.
 *
 * Neither scheme is reproducible. The reference signs hedged - it passes
 * fresh entropy on every call so it does not depend on a global RNG - so two
 * signatures over one message differ, and this SDK matches that. What must
 * hold is that signatures cross in both directions.
 */
class VectorConformanceTest {

    private data class Vector(
        val type: KeyType,
        val name: String,
        val message: ByteArray,
        val publicKey: String,
        val privateKey: String,
        val signature: ByteArray,
    )

    private val vectors: List<Vector> by lazy {
        val json = javaClass.getResourceAsStream("/pq-vectors.json")!!.bufferedReader().readText()
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("vectors")
            // Post-quantum only. The same file now carries secp256k1, whose
            // key encoding and signature format differ in every respect;
            // Secp256k1Test covers those. Loading them here would fail this
            // class's length assertions.
            .filter { it.asJsonObject.get("type").asString in setOf("ml-dsa-65", "falcon-512") }
            .map {
            val v = it.asJsonObject
            Vector(
                type = KeyType.fromWire(v.get("type").asString),
                name = v.get("messageName").asString,
                message = v.get("message").asString.toByteArray(Charsets.UTF_8),
                publicKey = v.get("publicKey").asString,
                privateKey = v.get("privateKey").asString,
                signature = Base64.getDecoder().decode(v.get("signature").asString),
            )
        }
    }

    private fun decoded(b64: String) = Base64.getDecoder().decode(b64)

    @Test
    fun `the vector file actually has both schemes`() {
        // Guards against a file that silently lost a scheme, which would make
        // every test below pass while covering half of what it claims.
        assertTrue(vectors.any { it.type == KeyType.ML_DSA_65 })
        assertTrue(vectors.any { it.type == KeyType.FALCON_512 })
        assertTrue(vectors.size >= 12, "expected at least 12 vectors, found ${vectors.size}")
    }

    @Test
    fun `verifies every published signature`() {
        for (v in vectors) {
            val kp = KeyPair.fromPublic(v.type, v.publicKey)
            assertTrue(kp.verify(v.message, v.signature), "failed to verify published ${v.type} / ${v.name}")
        }
    }

    @Test
    fun `round-trips every published key without re-deriving it`() {
        for (v in vectors) {
            val kp = KeyPair.fromKeys(v.type, v.publicKey, v.privateKey)
            assertEquals(v.privateKey, kp.exportPrivate(), "private key did not round-trip for ${v.type}")
            assertEquals(v.publicKey, kp.exportPublic(), "public key did not round-trip for ${v.type}")
        }
    }

    @Test
    fun `signatures this SDK produces verify with the published public key`() {
        for (v in vectors) {
            val signer = KeyPair.fromKeys(v.type, v.publicKey, v.privateKey)
            val mine = signer.sign(v.message)
            assertTrue(
                KeyPair.fromPublic(v.type, v.publicKey).verify(v.message, mine),
                "reference public key rejected a ${v.type} signature made here (${v.name})"
            )
        }
    }

    // Deliberately not a byte-equality assertion. The reference is hedged, so
    // matching it means being unable to reproduce it - and a signer that DID
    // reproduce it would be deterministic, a different security posture
    // adopted by accident.
    @Test
    fun `signing is hedged, so two signatures over one message differ`() {
        for (v in vectors) {
            val kp = KeyPair.fromKeys(v.type, v.publicKey, v.privateKey)
            assertFalse(
                kp.sign(v.message).contentEquals(kp.sign(v.message)),
                "${v.type} produced identical signatures - this signer is deterministic, the reference is hedged"
            )
        }
    }

    @Test
    fun `signature lengths match the scheme`() {
        for (v in vectors) {
            val len = KeyPair.fromKeys(v.type, v.publicKey, v.privateKey).sign(v.message).size
            when (v.type) {
                KeyType.ML_DSA_65 -> assertEquals(3309, len, "ml-dsa signature length")
                KeyType.FALCON_512 -> assertTrue(len in 649..662, "falcon signature was $len bytes")
                else -> {}
            }
        }
    }

    @Test
    fun `a tampered message does not verify`() {
        for (v in vectors) {
            val kp = KeyPair.fromPublic(v.type, v.publicKey)
            assertFalse(kp.verify(v.message + ' '.code.toByte(), v.signature), "${v.type} accepted a tampered message")
        }
    }

    @Test
    fun `malformed input returns false rather than throwing`() {
        val v = vectors.first()
        val kp = KeyPair.fromPublic(v.type, v.publicKey)
        assertFalse(kp.verify(v.message, ByteArray(10)))
        assertFalse(kp.verify(v.message, ByteArray(0)))
        assertFalse(kp.verify(ByteArray(0), v.signature))
    }

    @Test
    fun `a signature of the other scheme returns false`() {
        val mldsa = vectors.first { it.type == KeyType.ML_DSA_65 }
        val falcon = vectors.first { it.type == KeyType.FALCON_512 }
        assertFalse(KeyPair.fromPublic(mldsa.type, mldsa.publicKey).verify(mldsa.message, falcon.signature))
        assertFalse(KeyPair.fromPublic(falcon.type, falcon.publicKey).verify(falcon.message, mldsa.signature))
    }

    @Test
    fun `generated keys have the documented lengths`() {
        assertEquals(1952, decoded(KeyPair.generate(KeyType.ML_DSA_65).exportPublic()).size)
        assertEquals(4032, decoded(KeyPair.generate(KeyType.ML_DSA_65).exportPrivate()).size)
        assertEquals(897, decoded(KeyPair.generate(KeyType.FALCON_512).exportPublic()).size)
        assertEquals(1281, decoded(KeyPair.generate(KeyType.FALCON_512).exportPrivate()).size)
    }

    // Generated Falcon keys must carry the header, or they are rejected by a
    // node with a message about signatures rather than about key length.
    @Test
    fun `generated falcon keys carry their header bytes`() {
        val kp = KeyPair.generate(KeyType.FALCON_512)
        assertEquals(PqKeyCodec.FALCON_PUBLIC_HEADER, decoded(kp.exportPublic())[0])
        assertEquals(PqKeyCodec.FALCON_PRIVATE_HEADER, decoded(kp.exportPrivate())[0])
    }

    @Test
    fun `a freshly generated key signs and verifies its own signature`() {
        for (type in listOf(KeyType.ML_DSA_65, KeyType.FALCON_512)) {
            val kp = KeyPair.generate(type)
            val msg = "round trip".toByteArray(Charsets.UTF_8)
            assertTrue(kp.verify(msg, kp.sign(msg)), "$type could not verify its own signature")
        }
    }

    @Test
    fun `a verify-only key pair refuses to sign`() {
        val v = vectors.first()
        val kp = KeyPair.fromPublic(v.type, v.publicKey)
        assertFalse(kp.canSign)
        assertFailsWith<IllegalStateException> { kp.sign(v.message) }
        assertFailsWith<IllegalStateException> { kp.exportPrivate() }
    }

    @Test
    fun `a wrong-length key is rejected at construction`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(100))
        val e = assertFailsWith<IllegalArgumentException> { KeyPair.fromPublic(KeyType.ML_DSA_65, short) }
        assertTrue(e.message!!.contains("1952"), "should name the expected length, was: ${e.message}")
    }

    // A BouncyCastle-shaped Falcon key - header stripped - must be caught
    // here, not at a node.
    @Test
    fun `a header-stripped falcon key is rejected at construction`() {
        val stripped = Base64.getEncoder().encodeToString(ByteArray(896))
        assertFailsWith<IllegalArgumentException> { KeyPair.fromPublic(KeyType.FALCON_512, stripped) }
    }
}
