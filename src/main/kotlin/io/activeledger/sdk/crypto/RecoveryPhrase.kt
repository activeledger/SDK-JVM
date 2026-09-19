package io.activeledger.sdk.crypto

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * BIP-39 recovery phrases, and the seed each key type derives from one.
 *
 * There are two layers, and conflating them is the mistake this object is
 * arranged to prevent. A phrase becomes a 64-byte BIP-39 seed; that seed
 * becomes the seed the chosen algorithm actually takes. They are different
 * lengths and different constructions, and [deriveSeed] is the step between.
 *
 * The derivation:
 * ```
 * BIP-39 seed S = PBKDF2-HMAC-SHA512(phrase, "mnemonic"+passphrase, 2048, 64)
 *
 * ml-dsa-65   HKDF-SHA512(S, salt="", info="activeledger-seed-v1:ml-dsa-65", 32)
 * falcon-512  HKDF-SHA512(S, salt="", info="activeledger-seed-v1:falcon-512", 48)
 * secp256k1   HMAC-SHA512("Bitcoin seed", S)[0..32]
 * ```
 *
 * secp256k1 does not use HKDF, and that is not an oversight. The JavaScript
 * SDK has shipped `restoreBIP39Key` with the construction above since before
 * the post-quantum types existed, so phrases are already in use. Changing it
 * would hand every one of those users a different key for a phrase that used
 * to work - not an error, just an identity that is no longer theirs. The
 * post-quantum types are new and carry no such debt, so they get the
 * construction with proper domain separation.
 *
 * Published, with cross-language vectors, in the JavaScript SDK's
 * `vectors/seed-vectors.json`.
 *
 * BouncyCastle's PBKDF2 and HKDF are used rather than javax.crypto's: it is
 * already a dependency, `PBKDF2WithHmacSHA512` is not guaranteed present on
 * every JRE, and there is no HKDF in the platform at all.
 */
object RecoveryPhrase {

    /** BIP-39's fixed iteration count. Changing it changes every identity. */
    private const val ITERATIONS = 2048

    /** A BIP-39 seed is always 64 bytes. */
    const val BIP39_SEED_BYTES = 64

    private val wordlist: List<String> by lazy {
        val stream = RecoveryPhrase::class.java.getResourceAsStream("/bip39-english.txt")
            ?: throw IllegalStateException("The BIP-39 wordlist is missing from this jar")

        stream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
            reader.readText().split(Regex("\\s+")).filter { it.isNotEmpty() }
        }.also {
            check(it.size == 2048) { "The BIP-39 wordlist should hold 2048 words, found ${it.size}" }
        }
    }

    private val wordIndex: Map<String, Int> by lazy {
        wordlist.withIndex().associate { (position, word) -> word to position }
    }

    /**
     * The seed length the given key type takes.
     *
     * A wrong length is refused, never padded: a padded seed is a different
     * identity, not a malformed one.
     */
    @JvmStatic
    fun seedSize(type: KeyType): Int = when (type) {
        KeyType.SECP256K1, KeyType.ML_DSA_65 -> 32
        KeyType.FALCON_512 -> 48
        else -> throw UnsupportedOperationException("${type.wire} keys cannot be derived from a seed")
    }

    /**
     * Checks a phrase and returns it normalised and single-spaced.
     *
     * The checksum is verified, not just word membership. A mistyped phrase
     * that is not checked does not fail: it derives a perfectly valid key for
     * an identity nobody owns, and the only symptom is the ledger not
     * recognising it.
     */
    @JvmStatic
    fun validate(phrase: String): String {
        val words = Normalizer.normalize(phrase, Normalizer.Form.NFKD)
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        // 12, 15, 18, 21 and 24 are the only valid lengths.
        require(words.size in 12..24 && words.size % 3 == 0) {
            "a BIP-39 phrase is 12, 15, 18, 21 or 24 words, got ${words.size}"
        }

        val bits = StringBuilder(words.size * 11)
        words.forEachIndexed { position, word ->
            val index = wordIndex[word]
                ?: throw IllegalArgumentException(
                    "word ${position + 1} (\"$word\") is not in the BIP-39 English wordlist"
                )
            bits.append(Integer.toBinaryString(index).padStart(11, '0'))
        }

        val all = bits.toString()
        val checksumBits = words.size / 3
        val entropyBits = all.length - checksumBits

        val entropy = ByteArray(entropyBits / 8) { byte ->
            all.substring(byte * 8, byte * 8 + 8).toInt(2).toByte()
        }

        val digest = SHA256Digest()
        val hash = ByteArray(digest.digestSize)
        digest.update(entropy, 0, entropy.size)
        digest.doFinal(hash, 0)

        val expected = buildString {
            for (bit in 0 until checksumBits) {
                append(if (hash[0].toInt() and (1 shl (7 - bit)) != 0) '1' else '0')
            }
        }

        require(all.substring(entropyBits) == expected) {
            "the BIP-39 checksum does not match - the phrase has a typo or the words are in " +
                "the wrong order. Deriving from it anyway would produce a valid key for an " +
                "identity nobody owns."
        }

        return words.joinToString(" ")
    }

    /** Turns a recovery phrase into its 64-byte BIP-39 seed. */
    @JvmStatic
    @JvmOverloads
    fun toSeed(phrase: String, passphrase: String = ""): ByteArray {
        val normalised = validate(phrase)

        val generator = PKCS5S2ParametersGenerator(SHA512Digest())
        generator.init(
            normalised.toByteArray(StandardCharsets.UTF_8),
            // BIP-39's salt: the passphrase is appended to the literal
            // "mnemonic", not passed separately.
            ("mnemonic" + Normalizer.normalize(passphrase, Normalizer.Form.NFKD))
                .toByteArray(StandardCharsets.UTF_8),
            ITERATIONS,
        )

        return (generator.generateDerivedMacParameters(BIP39_SEED_BYTES * 8) as KeyParameter).key
    }

    /** Turns a BIP-39 seed into the seed the given key type takes. */
    @JvmStatic
    fun deriveSeed(type: KeyType, bip39Seed: ByteArray): ByteArray {
        require(bip39Seed.size == BIP39_SEED_BYTES) {
            "a BIP-39 seed is $BIP39_SEED_BYTES bytes, got ${bip39Seed.size}"
        }

        val size = seedSize(type)

        if (type == KeyType.SECP256K1) {
            val mac = HMac(SHA512Digest())
            mac.init(KeyParameter("Bitcoin seed".toByteArray(StandardCharsets.UTF_8)))
            mac.update(bip39Seed, 0, bip39Seed.size)

            val full = ByteArray(mac.macSize)
            mac.doFinal(full, 0)
            return full.copyOf(32)
        }

        // A null salt means a block of zero bytes of the hash length, which
        // is what RFC 5869 specifies - checked byte for byte against node's
        // crypto.hkdfSync and PHP's hash_hkdf.
        val hkdf = HKDFBytesGenerator(SHA512Digest())
        hkdf.init(
            HKDFParameters(
                bip39Seed,
                null,
                "activeledger-seed-v1:${type.wire}".toByteArray(StandardCharsets.UTF_8),
            )
        )

        return ByteArray(size).also { hkdf.generateBytes(it, 0, size) }
    }

    /**
     * The legacy scheme: SHA256(phrase) used directly as a secp256k1 scalar.
     *
     * No key stretching, no domain separation, no passphrase. It exists so a
     * phrase made by `@activeledger/sdk-bip39` can be recovered, never so a
     * new key can be made with it.
     *
     * Deliberately does NOT validate the mnemonic: the original package
     * hashed the string as given and never consulted the wordlist, so
     * rejecting a phrase here that it accepted would make a recoverable
     * identity unrecoverable.
     */
    @JvmStatic
    fun legacySeed(phrase: String): ByteArray {
        val digest = SHA256Digest()
        val bytes = phrase.toByteArray(StandardCharsets.UTF_8)
        val hash = ByteArray(digest.digestSize)

        digest.update(bytes, 0, bytes.size)
        digest.doFinal(hash, 0)
        return hash
    }

    /** Whether a scalar is a usable secp256k1 private key. */
    internal fun isValidScalar(seed: ByteArray): Boolean {
        val d = BigInteger(1, seed)
        return d.signum() != 0 && d < Secp256k1.domain.n
    }
}
