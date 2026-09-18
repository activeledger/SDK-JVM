package io.activeledger.sdk.crypto

import com.google.gson.JsonParser
import java.math.BigInteger
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * secp256k1 conformance against the published cross-language vectors.
 *
 * Its encoding has nothing in common with the post-quantum schemes, and every
 * test here exists because reusing the base64 path produces material the
 * ledger rejects as 1220 "Signature Incorrect" while saying nothing else.
 */
class Secp256k1Test {

    private data class Vector(
        val name: String,
        val form: String,
        val message: ByteArray,
        val publicKey: String,
        val privateKey: String,
        val signature: ByteArray,
        val deterministicSignature: String,
    )

    private val vectors: List<Vector> by lazy {
        val json = javaClass.getResourceAsStream("/pq-vectors.json")!!.bufferedReader().readText()
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("vectors")
            .filter { it.asJsonObject.get("type").asString == "secp256k1" }
            .map {
                val v = it.asJsonObject
                Vector(
                    name = v.get("messageName").asString,
                    form = v.get("publicKeyForm").asString,
                    message = v.get("message").asString.toByteArray(Charsets.UTF_8),
                    publicKey = v.get("publicKey").asString,
                    privateKey = v.get("privateKey").asString,
                    signature = Base64.getDecoder().decode(v.get("signature").asString),
                    deterministicSignature = v.get("deterministicSignature").asString,
                )
            }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `both public key forms are present in the vectors`() {
        // The ledger accepts either, so a port that only ever sees one never
        // learns to read the other.
        assertTrue(vectors.any { it.form == "compressed" })
        assertTrue(vectors.any { it.form == "uncompressed" })
        assertTrue(vectors.size >= 12, "expected at least 12, found ${vectors.size}")
    }

    @Test
    fun `verifies every published signature`() {
        for (v in vectors) {
            val key = KeyPair.fromPublic(KeyType.SECP256K1, v.publicKey)
            assertTrue(
                key.verify(v.message, v.signature),
                "failed to verify published secp256k1/${v.name}/${v.form}",
            )
        }
    }

    /**
     * High-S signatures must still verify.
     *
     * The ledger verifies through OpenSSL, which neither normalises nor
     * requires low-S, so it produces high-S signatures freely. A verifier
     * that enforced low-S - which @noble/curves and libsecp256k1 both do by
     * default - would reject roughly half of everything the ledger makes, and
     * the half that succeeded would make it look intermittent.
     */
    @Test
    fun `high-S signatures from elsewhere still verify`() {
        val highS = vectors.filter { Secp256k1.isHighS(it.signature) }

        assertTrue(
            highS.isNotEmpty(),
            "the published vectors no longer contain a high-S signature, so this test proves nothing",
        )

        for (v in highS) {
            val key = KeyPair.fromPublic(KeyType.SECP256K1, v.publicKey)
            assertTrue(
                key.verify(v.message, v.signature),
                "rejected a high-S signature (${v.name}/${v.form}) - low-S is being enforced on verify",
            )
        }
    }

    /**
     * The strongest test here: the exact bytes, not merely a valid signature.
     *
     * These expected values come from @noble/curves, an entirely separate
     * implementation. Agreeing byte for byte means agreeing on RFC 6979's k,
     * on low-S normalisation and on DER encoding at once - none of which a
     * verify-round-trip test can see. Only possible because ECDSA signing is
     * deterministic; the post-quantum schemes are hedged and never can be.
     */
    @Test
    fun `signatures are byte-identical to the reference implementation`() {
        for (v in vectors) {
            val key = KeyPair.fromKeys(KeyType.SECP256K1, v.publicKey, v.privateKey)
            val mine = b64(key.sign(v.message))

            assertEquals(
                v.deterministicSignature,
                mine,
                "${v.name}/${v.form}: signature differs from the reference. " +
                    "If r matches and only s differs, low-S normalisation is the cause.",
            )
        }
    }

    @Test
    fun `signing is deterministic`() {
        val v = vectors.first()
        val key = KeyPair.fromKeys(KeyType.SECP256K1, v.publicKey, v.privateKey)

        assertEquals(b64(key.sign(v.message)), b64(key.sign(v.message)))
        assertNotEquals(b64(key.sign(v.message)), b64(key.sign(vectors[1].message)))
    }

    @Test
    fun `every signature emitted is low-S`() {
        val key = KeyPair.generate(KeyType.SECP256K1)

        repeat(200) {
            val signature = key.sign("message $it".toByteArray(Charsets.UTF_8))
            assertFalse(Secp256k1.isHighS(signature), "signature $it was high-S")
        }
    }

    @Test
    fun `signatures made here verify with the reference public key`() {
        for (v in vectors) {
            val signer = KeyPair.fromKeys(KeyType.SECP256K1, v.publicKey, v.privateKey)
            val verifier = KeyPair.fromPublic(KeyType.SECP256K1, v.publicKey)

            assertTrue(
                verifier.verify(v.message, signer.sign(v.message)),
                "reference key rejected a signature made here (${v.name}/${v.form})",
            )
        }
    }

    @Test
    fun `round-trips published keys exactly`() {
        for (v in vectors) {
            val key = KeyPair.fromKeys(KeyType.SECP256K1, v.publicKey, v.privateKey)
            assertEquals(v.publicKey, key.exportPublic())
            assertEquals(v.privateKey, key.exportPrivate())
        }
    }

    @Test
    fun `tampered message does not verify`() {
        for (v in vectors) {
            val key = KeyPair.fromPublic(KeyType.SECP256K1, v.publicKey)
            assertFalse(key.verify(v.message + ' '.code.toByte(), v.signature))
        }
    }

    @Test
    fun `generated keys use the ledger's encoding`() {
        val key = KeyPair.generate(KeyType.SECP256K1)

        assertTrue(key.exportPublic().startsWith("0x"))
        assertTrue(key.exportPrivate().startsWith("0x"))
        // Compressed by default: 33 bytes, so "0x" plus 66 hex characters.
        assertEquals(68, key.exportPublic().length)
        assertEquals(66, key.exportPrivate().length)
        assertTrue(key.exportPublic().substring(2, 4) in setOf("02", "03"))
    }

    @Test
    fun `uncompressed generation is available`() {
        val key = KeyPair.generateSecp256k1(compressed = false)

        assertEquals(132, key.exportPublic().length)
        assertTrue(key.exportPublic().startsWith("0x04"))
        assertTrue(key.verify(byteArrayOf(1), key.sign(byteArrayOf(1))))
    }

    /**
     * The private scalar must be left-padded to 32 bytes.
     *
     * A BigInteger drops leading zero bytes, which happens to roughly one key
     * in 400, and the shorter value is a different scalar to anything reading
     * it strictly. This generates enough keys to be likely to hit one.
     */
    @Test
    fun `private keys are always left-padded to 32 bytes`() {
        repeat(1500) {
            assertEquals(66, KeyPair.generate(KeyType.SECP256K1).exportPrivate().length)
        }
    }

    @Test
    fun `a scalar with leading zero bytes round-trips`() {
        val scalar = ByteArray(32).also { it[31] = 0x2a }
        val hex = "0x" + scalar.joinToString("") { "%02x".format(it) }
        val generated = KeyPair.generate(KeyType.SECP256K1)

        val restored = KeyPair.fromKeys(KeyType.SECP256K1, generated.exportPublic(), hex)
        assertEquals(hex, restored.exportPrivate())
        assertEquals(66, restored.exportPrivate().length)
    }

    /**
     * The 0x prefix is part of what the ledger stores, not decoration.
     */
    @Test
    fun `a key without the hex prefix is refused with an explanation`() {
        val valid = KeyPair.generate(KeyType.SECP256K1).exportPublic()

        val error = assertFailsWith<IllegalArgumentException> {
            KeyPair.fromPublic(KeyType.SECP256K1, valid.substring(2))
        }
        assertTrue(error.message!!.contains("0x"), error.message!!)
    }

    @Test
    fun `wrong length public key is rejected with both valid lengths`() {
        val error = assertFailsWith<IllegalArgumentException> {
            KeyPair.fromPublic(KeyType.SECP256K1, "0x" + "aa".repeat(20))
        }
        assertTrue(error.message!!.contains("33"), error.message!!)
        assertTrue(error.message!!.contains("65"), error.message!!)
    }

    /** A length and a point prefix that disagree means the forms got mixed. */
    @Test
    fun `a prefix that contradicts the length is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            KeyPair.fromPublic(KeyType.SECP256K1, "0x04" + "aa".repeat(32))
        }
        assertTrue(error.message!!.contains("0x04"), error.message!!)
    }

    @Test
    fun `non-hex is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            KeyPair.fromPublic(KeyType.SECP256K1, "0xzzzz")
        }
    }

    @Test
    fun `verify-only key pair refuses to sign`() {
        val key = KeyPair.fromPublic(KeyType.SECP256K1, vectors.first().publicKey)

        assertFalse(key.canSign)
        assertFailsWith<IllegalStateException> { key.sign(byteArrayOf(1)) }
        assertFailsWith<IllegalStateException> { key.exportPrivate() }
    }

    @Test
    fun `malformed signature returns false rather than throwing`() {
        val v = vectors.first()
        val key = KeyPair.fromPublic(KeyType.SECP256K1, v.publicKey)

        assertFalse(key.verify(v.message, ByteArray(0)))
        assertFalse(key.verify(v.message, ByteArray(10)))
        // A raw r||s pair rather than DER.
        assertFalse(key.verify(v.message, ByteArray(64)))
    }

    /**
     * The ledger routes `bitcoin` and `ethereum` to identical secp256k1
     * verification, so an existing identity may carry either.
     */
    @Test
    fun `bitcoin and ethereum parse as secp256k1 but are never emitted`() {
        assertEquals(KeyType.SECP256K1, KeyType.fromWire("bitcoin"))
        assertEquals(KeyType.SECP256K1, KeyType.fromWire("ethereum"))
        assertEquals("secp256k1", KeyType.fromWire("bitcoin").wire)
        assertEquals("secp256k1", KeyType.fromWire("ethereum").wire)
    }

    /** The size argument, asserted so it cannot quietly stop being true. */
    @Test
    fun `keys and signatures are vastly smaller than post-quantum`() {
        val ec = KeyPair.generate(KeyType.SECP256K1)
        val pq = KeyPair.generate(KeyType.ML_DSA_65)
        val message = "size check".toByteArray(Charsets.UTF_8)

        assertTrue(
            ec.exportPublic().length * 20 < pq.exportPublic().length,
            "secp256k1 public key should be at least 20x smaller",
        )
        assertTrue(
            b64(ec.sign(message)).length * 20 < b64(pq.sign(message)).length,
            "secp256k1 signature should be at least 20x smaller",
        )
    }
}
