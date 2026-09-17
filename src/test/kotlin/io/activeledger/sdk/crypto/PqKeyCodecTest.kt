package io.activeledger.sdk.crypto

import com.google.gson.JsonParser
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// BouncyCastle's raw key getters omit the 1-byte type header: Falcon public
// keys come back 896 bytes rather than 897, private 1280 rather than 1281.
// Same bit packing - BC's decoder strips it. A port that does not re-add it
// produces keys the ledger rejects, and the failure presents as "Signature
// Incorrect" rather than anything about key length.
class PqKeyCodecTest {

    @Test
    fun `key type wire strings are exact`() {
        assertEquals("rsa", KeyType.RSA.wire)
        assertEquals("secp256k1", KeyType.SECP256K1.wire)
        assertEquals("ml-dsa-65", KeyType.ML_DSA_65.wire)
        assertEquals("falcon-512", KeyType.FALCON_512.wire)
    }

    @Test
    fun `fromWire round-trips every type`() {
        for (type in KeyType.entries) {
            assertEquals(type, KeyType.fromWire(type.wire))
        }
    }

    // Strict on purpose: the ledger compares these exactly, so accepting a
    // near-miss here only moves the failure somewhere less informative.
    @Test
    fun `fromWire rejects near-misses`() {
        for (bad in listOf("ml-dsa65", "ML-DSA-65", "mldsa65", "falcon512", "")) {
            val e = assertFailsWith<IllegalArgumentException> { KeyType.fromWire(bad) }
            assertTrue(e.message!!.contains(bad), "message should name the offending value")
        }
    }

    @Test
    fun `post-quantum flag is set for exactly the two PQ schemes`() {
        assertTrue(KeyType.ML_DSA_65.isPostQuantum)
        assertTrue(KeyType.FALCON_512.isPostQuantum)
        assertTrue(!KeyType.RSA.isPostQuantum)
        assertTrue(!KeyType.SECP256K1.isPostQuantum)
    }

    @Test
    fun `falcon public export is 897 bytes with a 0x09 header`() {
        val bc = ByteArray(896) { 0x11 }
        val exported = PqKeyCodec.exportFalconPublic(bc)
        assertEquals(897, exported.size)
        assertEquals(0x09.toByte(), exported[0])
    }

    @Test
    fun `falcon private export is 1281 bytes with a 0x59 header`() {
        val bc = ByteArray(1280) { 0x22 }
        val exported = PqKeyCodec.exportFalconPrivate(bc)
        assertEquals(1281, exported.size)
        assertEquals(0x59.toByte(), exported[0])
    }

    @Test
    fun `export preserves the body unchanged`() {
        val bc = ByteArray(896) { (it % 251).toByte() }
        val exported = PqKeyCodec.exportFalconPublic(bc)
        assertEquals(bc.toList(), exported.drop(1))
    }

    @Test
    fun `import strips the header and round-trips`() {
        val bc = ByteArray(896) { it.toByte() }
        assertEquals(bc.toList(), PqKeyCodec.importFalconPublic(PqKeyCodec.exportFalconPublic(bc)).toList())

        val bcPrv = ByteArray(1280) { it.toByte() }
        assertEquals(bcPrv.toList(), PqKeyCodec.importFalconPrivate(PqKeyCodec.exportFalconPrivate(bcPrv)).toList())
    }

    // Rejecting rather than silently accepting: a wrong-length key that
    // reaches the ledger fails as "Signature Incorrect".
    @Test
    fun `import rejects a wrong-length key`() {
        assertFailsWith<IllegalArgumentException> { PqKeyCodec.importFalconPublic(ByteArray(896)) }
        assertFailsWith<IllegalArgumentException> { PqKeyCodec.importFalconPrivate(ByteArray(1280)) }
    }

    // The most likely mistake gets its own message, because "wrong length"
    // alone would not tell a port author what they actually did.
    @Test
    fun `a stripped BouncyCastle key is diagnosed specifically`() {
        val e = assertFailsWith<IllegalArgumentException> { PqKeyCodec.importFalconPublic(ByteArray(896)) }
        assertTrue(
            e.message!!.contains("header byte stripped"),
            "should name the BouncyCastle stripping, was: ${e.message}"
        )
    }

    @Test
    fun `import rejects a wrong header byte`() {
        val bad = ByteArray(897).also { it[0] = 0x59 }
        val e = assertFailsWith<IllegalArgumentException> { PqKeyCodec.importFalconPublic(bad) }
        assertTrue(e.message!!.contains("0x09"), "should name the expected header, was: ${e.message}")
    }

    @Test
    fun `export rejects an already-headered key`() {
        assertFailsWith<IllegalArgumentException> { PqKeyCodec.exportFalconPublic(ByteArray(897)) }
    }

    // The check that actually matters: the published vectors are the form
    // the ledger accepts, so the constants here must agree with them.
    @Test
    fun `published falcon vectors carry the headers this codec expects`() {
        val json = javaClass.getResourceAsStream("/pq-vectors.json")!!.bufferedReader().readText()
        val vectors = JsonParser.parseString(json).asJsonObject.getAsJsonArray("vectors")
        var seen = 0
        for (element in vectors) {
            val v = element.asJsonObject
            if (v.get("type").asString != "falcon-512") continue
            seen++
            val pub = Base64.getDecoder().decode(v.get("publicKey").asString)
            val prv = Base64.getDecoder().decode(v.get("privateKey").asString)
            assertEquals(PqKeyCodec.FALCON_PUBLIC_BYTES, pub.size)
            assertEquals(PqKeyCodec.FALCON_PRIVATE_BYTES, prv.size)
            assertEquals(PqKeyCodec.FALCON_PUBLIC_HEADER, pub[0])
            assertEquals(PqKeyCodec.FALCON_PRIVATE_HEADER, prv[0])
            // And the codec must accept them without complaint.
            assertEquals(896, PqKeyCodec.importFalconPublic(pub).size)
            assertEquals(1280, PqKeyCodec.importFalconPrivate(prv).size)
        }
        assertTrue(seen > 0, "no falcon-512 vectors found - the published file lost them")
    }
}
