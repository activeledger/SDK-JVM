package io.activeledger.sdk.crypto

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Seed and recovery-phrase derivation, against the published cross-language
 * vectors.
 *
 * Six other SDKs derive keys from the same seeds and phrases. A derivation
 * that drifts does not fail loudly - it produces a perfectly valid key for an
 * identity that is not the caller's, and the only symptom arrives much later
 * as 1220 "Signature Incorrect" from somewhere else entirely.
 */
class SeedTest {

    private val doc by lazy {
        val json = javaClass.getResourceAsStream("/seed-vectors.json")!!.bufferedReader().readText()
        JsonParser.parseString(json).asJsonObject
    }

    private fun seedVectors(type: String, valid: Boolean = true): List<JsonElement> =
        doc.getAsJsonArray("seedVectors").filter {
            val v = it.asJsonObject
            v["type"].asString == type &&
                (v["valid"]?.asBoolean ?: true) == valid
        }

    private fun phraseVectors(type: String): List<JsonElement> =
        doc.getAsJsonArray("phraseVectors").filter { it.asJsonObject["type"].asString == type }

    private fun JsonElement.str(name: String): String = asJsonObject[name].asString

    private fun JsonElement.compressed(): Boolean =
        asJsonObject["publicKeyForm"]?.asString?.let { it == "compressed" } ?: true

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private val types = listOf(
        "ml-dsa-65" to KeyType.ML_DSA_65,
        "falcon-512" to KeyType.FALCON_512,
        "secp256k1" to KeyType.SECP256K1,
    )

    /**
     * A file that silently lost a type would let everything below pass by
     * simply not running.
     */
    @Test
    fun `the vector file covers every key type`() {
        types.forEach { (wire, _) ->
            assertTrue(seedVectors(wire).isNotEmpty(), "no seed vectors for $wire")
            assertTrue(phraseVectors(wire).isNotEmpty(), "no phrase vectors for $wire")
        }
        assertTrue(seedVectors("secp256k1", valid = false).size >= 2)
    }

    @Test
    fun `fromSeed reproduces every published key`() {
        types.forEach { (wire, type) ->
            seedVectors(wire).forEach { v ->
                val key = KeyPair.fromSeed(type, hex(v.str("seed")), v.compressed())
                val where = "$wire/${v.str("seedName")}"

                assertEquals(v.str("publicKey"), key.exportPublic(), "$where public")
                assertEquals(v.str("privateKey"), key.exportPrivate(), "$where private")
                assertEquals(type, key.type, where)
            }
        }
    }

    @Test
    fun `phrase recovery reproduces every published key`() {
        types.forEach { (wire, type) ->
            phraseVectors(wire).forEach { v ->
                val key = if (v.str("scheme") == "legacy") {
                    KeyPair.fromLegacyPhrase(v.str("phrase"), v.compressed())
                } else {
                    KeyPair.fromPhrase(type, v.str("phrase"), v.str("passphrase"), v.compressed())
                }
                val where = "$wire/${v.str("phraseName")}/${v.str("scheme")}"

                assertEquals(v.str("publicKey"), key.exportPublic(), "$where public")
                assertEquals(v.str("privateKey"), key.exportPrivate(), "$where private")
            }
        }
    }

    /** Checked separately from the key so a failure says WHICH step drifted. */
    @Test
    fun `the derivation matches the published intermediates`() {
        types.forEach { (wire, type) ->
            phraseVectors(wire).filter { it.str("scheme") == "v1" }.forEach { v ->
                val bip39 = RecoveryPhrase.toSeed(v.str("phrase"), v.str("passphrase"))
                assertEquals(v.str("bip39Seed"), bip39.toHex(), "${v.str("phraseName")} bip39 seed")

                assertEquals(
                    v.str("derivedSeed"),
                    RecoveryPhrase.deriveSeed(type, bip39).toHex(),
                    "$wire/${v.str("phraseName")} derived seed",
                )
            }
        }
    }

    /**
     * An invalid scalar must be refused, never reduced. Reducing mod n
     * returns a perfectly functional key belonging to a different identity,
     * and nothing downstream ever reports a problem.
     */
    @Test
    fun `an invalid scalar is refused rather than reduced`() {
        val invalid = seedVectors("secp256k1", valid = false)
        assertTrue(invalid.size >= 2, "expected at least 2 invalid seed vectors")

        invalid.forEach { v ->
            val error = assertFailsWith<IllegalArgumentException> {
                KeyPair.fromSeed(KeyType.SECP256K1, hex(v.str("seed")))
            }
            assertContains(error.message!!, "[1, n-1]")
        }
    }

    @Test
    fun `a seed of the wrong length is refused rather than padded`() {
        // Padding would produce a valid key for a different identity - the
        // same failure as reducing a scalar, by another route.
        types.forEach { (_, type) ->
            val expected = RecoveryPhrase.seedSize(type)

            listOf(0, 31, 33, 64).filter { it != expected }.forEach { length ->
                val error = assertFailsWith<IllegalArgumentException> {
                    KeyPair.fromSeed(type, ByteArray(length))
                }
                assertContains(error.message!!, "$expected-byte seed")
            }
        }
    }

    /**
     * Domain separation. Without it one phrase gives an ml-dsa-65 seed equal
     * to the secp256k1 scalar, so two identities share entropy.
     */
    @Test
    fun `each key type derives a different seed from one phrase`() {
        val bip39 = RecoveryPhrase.toSeed(doc.getAsJsonArray("phraseVectors")[0].str("phrase"))
        val seeds = types.map { (_, type) -> RecoveryPhrase.deriveSeed(type, bip39).toHex() }

        assertEquals(3, seeds.toSet().size, "two key types derive the same seed")
    }

    @Test
    fun `a passphrase changes the identity for every key type`() {
        val phrase = doc.getAsJsonArray("phraseVectors")[0].str("phrase")

        types.forEach { (wire, type) ->
            assertNotEquals(
                KeyPair.fromPhrase(type, phrase).exportPublic(),
                KeyPair.fromPhrase(type, phrase, "TREZOR").exportPublic(),
                "$wire ignored the passphrase",
            )
        }
    }

    @Test
    fun `a seed-derived key signs and verifies`() {
        types.forEach { (wire, type) ->
            val seed = ByteArray(RecoveryPhrase.seedSize(type)) { 0x11 }
            val key = KeyPair.fromSeed(type, seed)
            val message = "payload".toByteArray()

            assertTrue(key.verify(message, key.sign(message)), "$wire did not verify its own signature")
        }
    }

    @Test
    fun `the same seed always derives the same key`() {
        val seed = ByteArray(32) { 0x5a }

        assertEquals(
            KeyPair.fromSeed(KeyType.ML_DSA_65, seed).exportPublic(),
            KeyPair.fromSeed(KeyType.ML_DSA_65, seed).exportPublic(),
        )
    }

    // -- phrase validation ------------------------------------------------

    /**
     * An unchecked phrase is a silent failure, not a loud one: it derives a
     * perfectly valid key for an identity nobody owns.
     */
    @Test
    fun `a phrase with a bad checksum is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            RecoveryPhrase.toSeed("abandon ".repeat(11) + "abandon")
        }
        assertContains(error.message!!, "checksum")
    }

    @Test
    fun `a word outside the wordlist is named`() {
        val error = assertFailsWith<IllegalArgumentException> {
            RecoveryPhrase.toSeed("abandon ".repeat(11) + "zzzz")
        }
        assertContains(error.message!!, "zzzz")
        assertContains(error.message!!, "word 12")
    }

    @Test
    fun `a wrong word count is rejected`() {
        val error = assertFailsWith<IllegalArgumentException> {
            RecoveryPhrase.toSeed("abandon abandon abandon")
        }
        assertContains(error.message!!, "12, 15, 18, 21 or 24")
    }

    @Test
    fun `extra whitespace is tolerated not rejected`() {
        val phrase = doc.getAsJsonArray("phraseVectors")[0].str("phrase")

        assertEquals(
            KeyPair.fromPhrase(KeyType.ML_DSA_65, phrase).exportPublic(),
            KeyPair.fromPhrase(KeyType.ML_DSA_65, "  $phrase  ").exportPublic(),
        )
    }

    /**
     * The wordlist is a jar resource. A build that dropped it would fail only
     * at first use, which may be in someone else's application.
     */
    @Test
    fun `the wordlist ships in the jar`() {
        // validate() reaches the wordlist only after the length check, so a
        // valid-length phrase is needed to exercise the load at all.
        assertFailsWith<IllegalArgumentException> {
            RecoveryPhrase.toSeed("notaword ".repeat(11) + "notaword")
        }
    }
}
