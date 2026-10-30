package io.activeledger.sdk.tx

import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.crypto.KeyType
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionOrderTest {

    private val key = KeyPair.generate(KeyType.ML_DSA_65)

    // The signature covers the key order written, so where $entry and $r
    // land is part of the contract, not cosmetic.
    @Test
    fun `body keys are written entry, namespace, contract, i, o, r`() {
        val tx = Transaction.builder()
            .readonly("bal", "stream-9")
            .output("out", mapOf("v" to 1))
            .entry("update")
            .contract("c")
            .namespace("d")
            .input("s", key)
            .build()
        assertEquals(
            listOf("\$entry", "\$namespace", "\$contract", "\$i", "\$o", "\$r"),
            tx.body.keys.toList(),
        )
        assertTrue(
            tx.toJson().startsWith("{\"\$tx\":{\"\$entry\":\"update\",\"\$namespace\":\"d\",\"\$contract\":\"c\",\"\$i\":"),
            tx.toJson(),
        )
    }

    @Test
    fun `r follows i when there are no outputs`() {
        val tx = Transaction.builder().namespace("d").contract("c").input("s", key).readonly("bal", "stream-9").build()
        assertEquals(listOf("\$namespace", "\$contract", "\$i", "\$r"), tx.body.keys.toList())
    }

    @Test
    fun `r keeps its labels in the order they were added`() {
        val tx = Transaction.builder().namespace("d").contract("c").input("s", key)
            .readonly("zeta", "z").readonly("alpha", "a").readonly("mid", "m")
            .build()
        assertEquals(listOf("zeta", "alpha", "mid"), tx.body.getObject("\$r").keys.toList())
    }

    @Test
    fun `the signature verifies over a body that has both entry and r`() {
        val tx = Transaction.builder().namespace("d").contract("c").entry("e").input("s", key)
            .readonly("bal", "stream-9").build()
        val signature = Base64.getDecoder().decode(tx.sigs.getValue("s"))
        assertTrue(key.verify(tx.signedBytes(), signature))
        // Changing only what $r points at must invalidate it.
        val other = Transaction.builder().namespace("d").contract("c").entry("e").input("s", key)
            .readonly("bal", "stream-10").build()
        assertTrue(!key.verify(other.signedBytes(), signature))
    }
}
