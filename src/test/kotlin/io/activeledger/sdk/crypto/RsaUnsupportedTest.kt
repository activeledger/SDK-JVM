package io.activeledger.sdk.crypto

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RSA is a recognised wire type (the ledger accepts it) but this SDK cannot
 * generate, sign with or verify with it. Each of those must fail loudly with a
 * message naming the type, never fall through to another scheme or return a
 * result that looks like a verdict.
 */
class RsaUnsupportedTest {

    private fun rsaKey(): KeyPair {
        val blob = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })
        return KeyPair.fromKeys(KeyType.RSA, blob, blob)
    }

    @Test
    fun `generate refuses rsa`() {
        val e = assertFailsWith<UnsupportedOperationException> { KeyPair.generate(KeyType.RSA) }
        assertTrue(e.message!!.contains("rsa"))
    }

    @Test
    fun `an imported rsa key keeps its type and exports what it was given`() {
        val key = rsaKey()
        assertEquals(KeyType.RSA, key.type)
        assertTrue(key.canSign)
        assertEquals(key.exportPublic(), key.exportPrivate())
    }

    @Test
    fun `signing with rsa throws rather than producing a signature`() {
        val e = assertFailsWith<UnsupportedOperationException> { rsaKey().sign(byteArrayOf(1)) }
        assertTrue(e.message!!.contains("rsa"))
    }

    // verify() returns false for malformed input; an unsupported scheme must
    // not be swallowed into that false.
    @Test
    fun `verifying with rsa throws rather than returning false`() {
        val e = assertFailsWith<UnsupportedOperationException> { rsaKey().verify(byteArrayOf(1), byteArrayOf(2)) }
        assertTrue(e.message!!.contains("rsa"))
    }

    @Test
    fun `a verify-only rsa key also throws on verify`() {
        val pub = Base64.getEncoder().encodeToString(ByteArray(32))
        assertFailsWith<UnsupportedOperationException> {
            KeyPair.fromPublic(KeyType.RSA, pub).verify(byteArrayOf(1), byteArrayOf(2))
        }
    }

    @Test
    fun `rsa keys cannot be derived from a seed`() {
        assertFailsWith<UnsupportedOperationException> { RecoveryPhrase.seedSize(KeyType.RSA) }
        assertFailsWith<UnsupportedOperationException> { KeyPair.fromSeed(KeyType.RSA, ByteArray(32)) }
        assertFailsWith<UnsupportedOperationException> { RecoveryPhrase.deriveSeed(KeyType.RSA, ByteArray(64)) }
    }

    @Test
    fun `rsa is not a recovery phrase target`() {
        val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        assertFailsWith<UnsupportedOperationException> { KeyPair.fromPhrase(KeyType.RSA, phrase) }
    }
}
