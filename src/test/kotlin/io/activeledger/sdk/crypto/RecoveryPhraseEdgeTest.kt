package io.activeledger.sdk.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Recovery-phrase inputs the published vectors do not reach. */
class RecoveryPhraseEdgeTest {

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `every valid phrase length is accepted`() {
        // The BIP-39 phrases of all zero entropy, one per length.
        val phrases = mapOf(
            12 to "about", 15 to "address", 18 to "agent", 21 to "admit", 24 to "art",
        )
        for ((count, last) in phrases) {
            val phrase = "abandon ".repeat(count - 1) + last
            assertEquals(phrase, RecoveryPhrase.validate(phrase), "$count words")
        }
    }

    @Test
    fun `a length between the valid ones is rejected even when every word is real`() {
        for (count in listOf(11, 13, 14, 16, 25)) {
            assertFailsWith<IllegalArgumentException>("$count words") {
                RecoveryPhrase.validate("abandon ".repeat(count))
            }
        }
    }

    @Test
    fun `the passphrase is NFKD normalised before it salts the seed`() {
        val phrase = "abandon ".repeat(11) + "about"
        // The same text composed (U+00E9) and decomposed (e + U+0301): BIP-39
        // says they are one passphrase, and a port that skips normalisation
        // gives each user two identities depending on their keyboard.
        val composed = RecoveryPhrase.toSeed(phrase, "café")
        val decomposed = RecoveryPhrase.toSeed(phrase, "café")

        assertContentEquals(composed, decomposed)
    }

    @Test
    fun `a phrase typed with other whitespace gives the same seed`() {
        val phrase = "abandon ".repeat(11) + "about"

        assertContentEquals(
            RecoveryPhrase.toSeed(phrase),
            RecoveryPhrase.toSeed("  " + phrase.replace(" ", "\n\t ") + " "),
        )
    }

    @Test
    fun `a bip39 seed of the wrong size is refused for every type`() {
        for (type in listOf(KeyType.SECP256K1, KeyType.ML_DSA_65, KeyType.FALCON_512)) {
            for (size in listOf(0, 32, 63, 65)) {
                assertFailsWith<IllegalArgumentException>("${type.wire} $size bytes") {
                    RecoveryPhrase.deriveSeed(type, ByteArray(size))
                }
            }
        }
    }

    @Test
    fun `the derived seed has the size the key type takes`() {
        val bip39 = RecoveryPhrase.toSeed("abandon ".repeat(11) + "about")

        for (type in listOf(KeyType.SECP256K1, KeyType.ML_DSA_65, KeyType.FALCON_512)) {
            assertEquals(RecoveryPhrase.seedSize(type), RecoveryPhrase.deriveSeed(type, bip39).size, type.wire)
        }
    }

    @Test
    fun `the legacy seed is the sha256 of the text as given, with no validation`() {
        // Not a mnemonic at all: the original package hashed whatever it was
        // handed, and refusing it here would strand an identity.
        assertContentEquals(
            hex("a9c59b02da04a368cd10fcdf9f5864018664a67700f3eb844bdbf59b002e33d1"),
            RecoveryPhrase.legacySeed("not a mnemonic"),
        )
    }

    @Test
    fun `the legacy seed is sensitive to whitespace, unlike the validated scheme`() {
        assertTrue(!RecoveryPhrase.legacySeed("a b").contentEquals(RecoveryPhrase.legacySeed("a  b")))
    }
}
