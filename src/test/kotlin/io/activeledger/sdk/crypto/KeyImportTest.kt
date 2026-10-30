package io.activeledger.sdk.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KeyImportTest {

    private val postQuantum = listOf(KeyType.ML_DSA_65, KeyType.FALCON_512)

    @Test
    fun `fromKeys round-trips an exported pair for every supported type`() {
        for (type in KeyType.entries.filter { it != KeyType.RSA }) {
            val original = KeyPair.generate(type)
            val restored = KeyPair.fromKeys(type, original.exportPublic(), original.exportPrivate())
            assertEquals(original.exportPublic(), restored.exportPublic(), type.wire)
            assertEquals(original.exportPrivate(), restored.exportPrivate(), type.wire)

            val message = "round trip".toByteArray()
            assertTrue(original.verify(message, restored.sign(message)), type.wire)
        }
    }

    @Test
    fun `fromPublic cannot sign and cannot export a private key`() {
        for (type in KeyType.entries.filter { it != KeyType.RSA }) {
            val pub = KeyPair.fromPublic(type, KeyPair.generate(type).exportPublic())
            assertTrue(!pub.canSign, type.wire)
            assertFailsWith<IllegalStateException> { pub.exportPrivate() }
            assertFailsWith<IllegalStateException> { pub.sign(byteArrayOf(1)) }
        }
    }

    @Test
    fun `a public-only key verifies what the full key signed`() {
        for (type in KeyType.entries.filter { it != KeyType.RSA }) {
            val full = KeyPair.generate(type)
            val message = "hello".toByteArray()
            val verifier = KeyPair.fromPublic(type, full.exportPublic())
            assertTrue(verifier.verify(message, full.sign(message)), type.wire)
            assertTrue(!verifier.verify("other".toByteArray(), full.sign(message)), type.wire)
        }
    }

    @Test
    fun `non-base64 post-quantum keys are refused naming which half`() {
        for (type in postQuantum) {
            val good = KeyPair.generate(type)
            val badPub = assertFailsWith<IllegalArgumentException> { KeyPair.fromPublic(type, "not base64!!") }
            assertTrue(badPub.message!!.contains("public key is not valid base64"))
            val badPrv = assertFailsWith<IllegalArgumentException> {
                KeyPair.fromKeys(type, good.exportPublic(), "not base64!!")
            }
            assertTrue(badPrv.message!!.contains("private key is not valid base64"))
        }
    }

    @Test
    fun `a wrong-length private key is refused with both lengths`() {
        val good = KeyPair.generate(KeyType.ML_DSA_65)
        val short = java.util.Base64.getEncoder().encodeToString(ByteArray(10))
        val e = assertFailsWith<IllegalArgumentException> {
            KeyPair.fromKeys(KeyType.ML_DSA_65, good.exportPublic(), short)
        }
        assertTrue(e.message!!.contains("4032") && e.message!!.contains("10"))
    }

    @Test
    fun `a wrong-length secp256k1 private key is refused`() {
        val good = KeyPair.generateSecp256k1()
        assertFailsWith<IllegalArgumentException> {
            KeyPair.fromKeys(KeyType.SECP256K1, good.exportPublic(), "0x" + "ab".repeat(31))
        }
    }

    @Test
    fun `swapping the halves is caught by the length check`() {
        val good = KeyPair.generate(KeyType.FALCON_512)
        assertFailsWith<IllegalArgumentException> {
            KeyPair.fromKeys(KeyType.FALCON_512, good.exportPrivate(), good.exportPublic())
        }
    }
}
