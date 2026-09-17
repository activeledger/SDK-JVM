package io.activeledger.sdk.tx

import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.crypto.KeyType
import io.activeledger.sdk.json.CanonicalJson
import io.activeledger.sdk.json.JsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionTest {

    private val key = KeyPair.generate(KeyType.ML_DSA_65)

    // Onboarding is $selfsign: true with $sigs keyed by the $i LABEL, not by
    // a stream id - there is no stream yet. This is the most likely first
    // integration failure in a port, so it is pinned by shape here rather
    // than discovered against a live node.
    @Test
    fun `onboard transaction has selfsign and label-keyed sigs`() {
        val tx = Transaction.onboard(key)
        assertTrue(tx.selfSign)
        assertEquals(setOf("identity"), tx.sigs.keys)
        assertEquals(setOf("identity"), tx.body.getObject("\$i").keys)
    }

    // The type must always be present. The ledger defaults a missing type to
    // "rsa" and then attempts RSA verification against a base64 PQ blob,
    // which comes back as 1220 Signature Incorrect.
    @Test
    fun `onboard always carries an explicit type`() {
        val tx = Transaction.onboard(key)
        val input = tx.body.getObject("\$i").getObject("identity")
        assertEquals("ml-dsa-65", input.getString("type"))
        assertEquals(key.exportPublic(), input.getString("publicKey"))
    }

    @Test
    fun `onboard signature covers the canonical tx and nothing else`() {
        val tx = Transaction.onboard(key)
        val signature = Base64.getDecoder().decode(tx.sigs.getValue("identity"))
        assertTrue(key.verify(CanonicalJson.bytes(tx.body), signature))
        // And explicitly NOT the envelope.
        assertTrue(!key.verify(CanonicalJson.bytes(tx.envelope()), signature))
    }

    @Test
    fun `signedBytes are exactly the canonical form of the body`() {
        val tx = Transaction.onboard(key)
        assertEquals(CanonicalJson.stringify(tx.body), String(tx.signedBytes(), Charsets.UTF_8))
    }

    @Test
    fun `falcon onboarding carries its own type and key`() {
        val falcon = KeyPair.generate(KeyType.FALCON_512)
        val tx = Transaction.onboard(falcon)
        assertEquals("falcon-512", tx.body.getObject("\$i").getObject("identity").getString("type"))
        assertTrue(
            falcon.verify(tx.signedBytes(), Base64.getDecoder().decode(tx.sigs.getValue("identity")))
        )
    }

    @Test
    fun `a custom label is used for both the input and the signature`() {
        val tx = Transaction.onboard(key, label = "mylabel")
        assertEquals(setOf("mylabel"), tx.sigs.keys)
        assertEquals(setOf("mylabel"), tx.body.getObject("\$i").keys)
    }

    // Ties the transaction layer to the canonicalisation work: a multibyte
    // payload must produce the same bytes here as CanonicalJson does. This is
    // the case that catches a transaction layer quietly re-serialising
    // through another library.
    @Test
    fun `signing a non-ascii payload produces the canonical bytes`() {
        val tx = Transaction.builder()
            .namespace("default")
            .contract("demo")
            .input("streamA", key, mapOf("note" to "café 日本語"))
            .build()

        val serialised = String(tx.signedBytes(), Charsets.UTF_8)
        assertTrue(serialised.contains("café"), "non-ascii should be raw, was: $serialised")
        assertTrue(!serialised.contains("\\u00e9"), "non-ascii must not be escaped")
        assertTrue(key.verify(tx.signedBytes(), Base64.getDecoder().decode(tx.sigs.getValue("streamA"))))
    }

    @Test
    fun `builder preserves insertion order of inputs and payload`() {
        val tx = Transaction.builder()
            .namespace("default")
            .contract("demo")
            .input("streamA", key, linkedMapOf("zebra" to 1, "alpha" to 2))
            .build()
        val serialised = String(tx.signedBytes(), Charsets.UTF_8)
        assertTrue(
            serialised.indexOf("zebra") < serialised.indexOf("alpha"),
            "insertion order was not preserved: $serialised"
        )
    }

    @Test
    fun `builder emits entry only when set`() {
        val without = Transaction.builder().namespace("d").contract("c").input("s", key).build()
        assertTrue(!String(without.signedBytes(), Charsets.UTF_8).contains("\$entry"))

        val with = Transaction.builder().namespace("d").contract("c").entry("update").input("s", key).build()
        assertEquals("update", with.body.getString("\$entry"))
    }

    @Test
    fun `builder requires namespace, contract and a signer`() {
        assertFailsWith<IllegalStateException> { Transaction.builder().contract("c").input("s", key).build() }
        assertFailsWith<IllegalStateException> { Transaction.builder().namespace("d").input("s", key).build() }
        assertFailsWith<IllegalArgumentException> { Transaction.builder().namespace("d").contract("c").build() }
    }

    // Reflection-based conversion would be a silent risk: what gets signed is
    // these exact bytes, so an inferred number format or field order could
    // change the signature without anyone writing it.
    @Test
    fun `builder refuses payload types it cannot serialise predictably`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Transaction.builder().namespace("d").contract("c")
                .input("s", key, mapOf("when" to java.util.Date()))
                .build()
        }
        assertTrue(e.message!!.contains("Unsupported payload value"))
    }

    @Test
    fun `multiple inputs each get their own signature`() {
        val second = KeyPair.generate(KeyType.FALCON_512)
        val tx = Transaction.builder()
            .namespace("default")
            .contract("demo")
            .input("streamA", key)
            .input("streamB", second)
            .build()

        assertEquals(listOf("streamA", "streamB"), tx.sigs.keys.toList())
        assertTrue(key.verify(tx.signedBytes(), Base64.getDecoder().decode(tx.sigs.getValue("streamA"))))
        assertTrue(second.verify(tx.signedBytes(), Base64.getDecoder().decode(tx.sigs.getValue("streamB"))))
    }

    @Test
    fun `envelope carries tx, sigs and selfsign in a submittable shape`() {
        val tx = Transaction.onboard(key)
        val json = tx.toJson()
        assertTrue(json.contains("\"\$tx\""))
        assertTrue(json.contains("\"\$sigs\""))
        assertTrue(json.contains("\"\$selfsign\":true"))

        val ordinary = Transaction.builder().namespace("d").contract("c").input("s", key).build()
        assertTrue(!ordinary.toJson().contains("selfsign"))
    }

    @Test
    fun `outputs are included only when present`() {
        val tx = Transaction.builder()
            .namespace("d").contract("c")
            .input("s", key)
            .output("target", mapOf("message" to "hi"))
            .build()
        assertEquals("hi", tx.body.getObject("\$o").getObject("target").getString("message"))
    }
}
