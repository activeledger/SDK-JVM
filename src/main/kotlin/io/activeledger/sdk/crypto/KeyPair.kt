package io.activeledger.sdk.crypto

import org.bouncycastle.crypto.params.MLDSAKeyGenerationParameters
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import org.bouncycastle.crypto.params.ParametersWithRandom
import org.bouncycastle.crypto.generators.MLDSAKeyPairGenerator
import org.bouncycastle.crypto.signers.MLDSASigner
import org.bouncycastle.pqc.crypto.falcon.FalconKeyGenerationParameters
import org.bouncycastle.pqc.crypto.falcon.FalconKeyPairGenerator
import org.bouncycastle.pqc.crypto.falcon.FalconParameters
import org.bouncycastle.pqc.crypto.falcon.FalconPrivateKeyParameters
import org.bouncycastle.pqc.crypto.falcon.FalconPublicKeyParameters
import org.bouncycastle.pqc.crypto.falcon.FalconSigner
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64

/**
 * A post-quantum key pair that interoperates with Activeledger.
 *
 * Keys are carried as base64 of the **raw algorithm bytes**, matching the
 * JavaScript SDK's on-disk format. The field is named `pkcs8pem` there, which
 * is a historical lie for everything except RSA, and it is kept because
 * changing it would break every exported key.
 *
 * Both key halves are held together. That is not a convenience: BouncyCastle
 * cannot build a Falcon private key without a public key, and the ledger's
 * 1281-byte private key does not contain one - so a private key alone is not
 * enough to sign with, and an API pretending otherwise would fail at the
 * worst moment.
 *
 * Signing is **hedged**, seeded from [SecureRandom], matching the reference
 * implementation. FIPS 204 permits a deterministic variant too; the
 * JavaScript SDK deliberately does not use it, so neither does this. Two
 * signatures over one message will differ, and both will verify.
 */
class KeyPair private constructor(
    val type: KeyType,
    private val publicBytes: ByteArray,
    private val privateBytes: ByteArray?,
) {

    /**
     * The public key exactly as the ledger stores it.
     *
     * Base64 for the post-quantum schemes, `0x`-prefixed hex for secp256k1.
     * The encoding is not a caller's choice: the ledger compares these
     * strings, and a secp256k1 key written as base64 is rejected as 1220.
     */
    fun exportPublic(): String = encode(publicBytes)

    /** The private key, in the same encoding. Throws if this is verify-only. */
    fun exportPrivate(): String =
        encode(
            privateBytes ?: throw IllegalStateException("This key pair has no private key - it was created for verification only")
        )

    private fun encode(bytes: ByteArray): String =
        if (type == KeyType.SECP256K1) Secp256k1.toHex(bytes)
        else Base64.getEncoder().encodeToString(bytes)

    val canSign: Boolean get() = privateBytes != null

    fun sign(message: ByteArray): ByteArray {
        val prv = privateBytes
            ?: throw IllegalStateException("This key pair has no private key - it was created for verification only")
        return when (type) {
            KeyType.ML_DSA_65 -> signMlDsa(message, prv)
            KeyType.FALCON_512 -> signFalcon(message, prv)
            KeyType.SECP256K1 -> Secp256k1.sign(message, prv)
            else -> throw UnsupportedOperationException("${type.wire} signing is not implemented yet")
        }
    }

    /**
     * Verifies a signature.
     *
     * Returns `false` rather than throwing for malformed input. A caller
     * should not have to distinguish "this signature is invalid" from "this
     * signature is the wrong shape" - both mean the same thing at the call
     * site, and making one of them an exception invites a bare catch that
     * swallows the other.
     */
    fun verify(message: ByteArray, signature: ByteArray): Boolean = try {
        when (type) {
            KeyType.ML_DSA_65 -> {
                val pub = MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, publicBytes)
                MLDSASigner().run {
                    init(false, pub)
                    update(message, 0, message.size)
                    verifySignature(signature)
                }
            }
            KeyType.FALCON_512 -> {
                val pub = FalconPublicKeyParameters(
                    FalconParameters.falcon_512,
                    PqKeyCodec.importFalconPublic(publicBytes),
                )
                FalconSigner().run {
                    init(false, pub)
                    verifySignature(message, signature)
                }
            }
            KeyType.SECP256K1 -> Secp256k1.verify(message, signature, publicBytes)
            else -> throw UnsupportedOperationException("${type.wire} verification is not implemented yet")
        }
    } catch (_: UnsupportedOperationException) {
        throw UnsupportedOperationException("${type.wire} verification is not implemented yet")
    } catch (_: Exception) {
        false
    }

    private fun signMlDsa(message: ByteArray, prv: ByteArray): ByteArray {
        // The ledger's 4032-byte expanded key is accepted directly.
        val key = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, prv)
        val signer = MLDSASigner()
        // ParametersWithRandom selects the hedged variant. A bare key would
        // give rnd = 0^32 and be deterministic - valid FIPS 204, but not what
        // the reference does, and not a posture to adopt silently.
        signer.init(true, ParametersWithRandom(key, SecureRandom()))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun signFalcon(message: ByteArray, prv: ByteArray): ByteArray {
        val body = PqKeyCodec.importFalconPrivate(prv)
        // BouncyCastle wants the three polynomials separately, and the public
        // key alongside, which the ledger's private key does not carry.
        val signer = FalconSigner()
        signer.init(
            true,
            FalconPrivateKeyParameters(
                FalconParameters.falcon_512,
                body.copyOfRange(0, FALCON_F_BYTES),
                body.copyOfRange(FALCON_F_BYTES, FALCON_F_BYTES + FALCON_G_BYTES),
                body.copyOfRange(FALCON_F_BYTES + FALCON_G_BYTES, body.size),
                PqKeyCodec.importFalconPublic(publicBytes),
            ),
        )
        return signer.generateSignature(message)
    }

    companion object {
        private const val FALCON_F_BYTES = 384
        private const val FALCON_G_BYTES = 384

        /** Raw byte lengths the ledger expects, per type. */
        private val PUBLIC_BYTES = mapOf(KeyType.ML_DSA_65 to 1952, KeyType.FALCON_512 to 897)
        private val PRIVATE_BYTES = mapOf(
            KeyType.ML_DSA_65 to 4032,
            KeyType.FALCON_512 to 1281,
            KeyType.SECP256K1 to Secp256k1.PRIVATE_BYTES,
        )

        @JvmStatic
        fun generate(type: KeyType): KeyPair = when (type) {
            KeyType.ML_DSA_65 -> {
                val gen = MLDSAKeyPairGenerator()
                gen.init(MLDSAKeyGenerationParameters(SecureRandom(), MLDSAParameters.ml_dsa_65))
                val pair = gen.generateKeyPair()
                KeyPair(
                    type,
                    (pair.public as MLDSAPublicKeyParameters).encoded,
                    (pair.private as MLDSAPrivateKeyParameters).encoded,
                )
            }
            KeyType.FALCON_512 -> {
                val gen = FalconKeyPairGenerator()
                gen.init(FalconKeyGenerationParameters(SecureRandom(), FalconParameters.falcon_512))
                val pair = gen.generateKeyPair()
                // Header bytes added back here, once, so nothing downstream
                // ever sees BouncyCastle's stripped form.
                KeyPair(
                    type,
                    PqKeyCodec.exportFalconPublic((pair.public as FalconPublicKeyParameters).h),
                    PqKeyCodec.exportFalconPrivate((pair.private as FalconPrivateKeyParameters).encoded),
                )
            }
            KeyType.SECP256K1 -> generateSecp256k1(compressed = true)
            else -> throw UnsupportedOperationException("${type.wire} generation is not implemented yet")
        }

        /**
         * Derives a key pair from the algorithm's own seed.
         *
         * No key derivation function is applied: the bytes given are the seed
         * the scheme itself takes - 32 for ml-dsa-65 and secp256k1, 48 for
         * falcon-512. A wrong length is refused rather than padded, because a
         * padded seed is a different identity, not a malformed one.
         *
         * This is how a private key moves between Activeledger SDKs. The PHP
         * SDK's ml-dsa-65 private key IS a 32-byte seed - its library
         * implements FIPS 204 key generation from a seed but not
         * skEncode/skDecode - so the 4032-byte encoding this SDK exports
         * cannot be loaded there. The seed can be, and gives an identical
         * public key.
         */
        @JvmStatic
        @JvmOverloads
        fun fromSeed(type: KeyType, seed: ByteArray, compressed: Boolean = true): KeyPair {
            val expected = RecoveryPhrase.seedSize(type)
            require(seed.size == expected) {
                "${type.wire} needs a $expected-byte seed, got ${seed.size}. It is refused " +
                    "rather than padded: a padded seed is a different identity, not a " +
                    "malformed one."
            }

            return when (type) {
                KeyType.ML_DSA_65 -> {
                    // BouncyCastle's seed constructor, rather than driving the
                    // generator with a fixed SecureRandom. Both give the same
                    // key - measured - but this one says what it means.
                    val prv = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, seed)
                    KeyPair(type, prv.publicKeyParameters.encoded, prv.encoded)
                }
                KeyType.FALCON_512 -> {
                    // Falcon has no seed constructor, so the generator is fed
                    // a SecureRandom yielding exactly these bytes. Verified
                    // byte for byte against @noble/post-quantum across the
                    // full key before being relied on.
                    val gen = FalconKeyPairGenerator()
                    gen.init(FalconKeyGenerationParameters(FixedRandom(seed), FalconParameters.falcon_512))
                    val pair = gen.generateKeyPair()
                    KeyPair(
                        type,
                        PqKeyCodec.exportFalconPublic((pair.public as FalconPublicKeyParameters).h),
                        PqKeyCodec.exportFalconPrivate((pair.private as FalconPrivateKeyParameters).encoded),
                    )
                }
                KeyType.SECP256K1 -> {
                    // The seed IS the scalar, so it has to be a valid one.
                    // Refused rather than reduced mod n: reducing produces a
                    // perfectly functional key belonging to a different
                    // identity, and nothing downstream ever reports a problem.
                    require(RecoveryPhrase.isValidScalar(seed)) {
                        "seed is not a valid secp256k1 private key - the scalar must be in [1, n-1]"
                    }

                    val d = BigInteger(1, seed)
                    KeyPair(
                        type,
                        Secp256k1.domain.g.multiply(d).normalize().getEncoded(compressed),
                        Secp256k1.scalarBytes(d),
                    )
                }
                else -> throw UnsupportedOperationException(
                    "${type.wire} cannot be derived from a seed"
                )
            }
        }

        /**
         * Derives a key pair from a BIP-39 recovery phrase.
         *
         * One phrase can back an ml-dsa-65, a falcon-512 and a secp256k1
         * identity at once: each type derives its own seed, so none of them
         * reveals the others.
         */
        @JvmStatic
        @JvmOverloads
        fun fromPhrase(
            type: KeyType,
            phrase: String,
            passphrase: String = "",
            compressed: Boolean = true,
        ): KeyPair = fromSeed(
            type,
            RecoveryPhrase.deriveSeed(type, RecoveryPhrase.toSeed(phrase, passphrase)),
            compressed,
        )

        /**
         * Recovers a secp256k1 key pair from a phrase made by
         * `@activeledger/sdk-bip39`.
         *
         * That scheme is SHA256(phrase) used directly as the scalar - no key
         * stretching, no domain separation, no passphrase. It exists so an
         * old phrase can be recovered, never so a new key can be made with it.
         */
        @JvmStatic
        @JvmOverloads
        fun fromLegacyPhrase(phrase: String, compressed: Boolean = true): KeyPair =
            fromSeed(KeyType.SECP256K1, RecoveryPhrase.legacySeed(phrase), compressed)

        /**
         * A SecureRandom yielding fixed bytes, so keygen is deterministic.
         *
         * Only for Falcon, which BouncyCastle offers no seed constructor for.
         * It cycles the seed rather than running out, because the generator
         * may ask for more bytes than the seed holds.
         */
        private class FixedRandom(private val seed: ByteArray) : SecureRandom() {
            private var position = 0

            override fun nextBytes(bytes: ByteArray) {
                for (i in bytes.indices) {
                    bytes[i] = seed[position++ % seed.size]
                }
            }
        }

        /** A verify-only key pair. */
        @JvmStatic
        fun fromPublic(type: KeyType, publicKey: String): KeyPair {
            val pub = decode(type, publicKey, "public")
            checkPublic(type, pub)
            return KeyPair(type, pub, null)
        }

        /**
         * A signing key pair.
         *
         * Takes both halves, because BouncyCastle cannot construct a Falcon
         * private key without a public key and the ledger's private key does
         * not contain one. The SDK's own key file carries both, so this costs
         * a caller nothing.
         */
        @JvmStatic
        fun fromKeys(type: KeyType, publicKey: String, privateKey: String): KeyPair {
            val pub = decode(type, publicKey, "public")
            val prv = decode(type, privateKey, "private")
            checkPublic(type, pub)
            checkLength(type, prv, PRIVATE_BYTES[type], "private")
            return KeyPair(type, pub, prv)
        }

        /**
         * secp256k1, with the public key form chosen explicitly.
         *
         * The ledger accepts both; compressed is smaller and is what
         * [generate] produces.
         */
        @JvmStatic
        @JvmOverloads
        fun generateSecp256k1(compressed: Boolean = true): KeyPair {
            val (pub, prv) = Secp256k1.generate(compressed)
            return KeyPair(KeyType.SECP256K1, pub, prv)
        }

        private fun decode(type: KeyType, value: String, what: String): ByteArray =
            if (type == KeyType.SECP256K1) Secp256k1.fromHex(value, what)
            else try {
                Base64.getDecoder().decode(value)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$what key is not valid base64", e)
            }

        /**
         * secp256k1 has two valid public key lengths, so it cannot use the
         * single-expected-length check the post-quantum schemes use.
         */
        private fun checkPublic(type: KeyType, bytes: ByteArray) {
            if (type == KeyType.SECP256K1) Secp256k1.checkPublic(bytes)
            else checkLength(type, bytes, PUBLIC_BYTES[type], "public")
        }

        private fun checkLength(type: KeyType, bytes: ByteArray, expected: Int?, what: String) {
            if (expected == null) return
            // Caught here rather than at a node. A wrong-length key reaching
            // the ledger comes back as 1220 "Signature Incorrect", which says
            // nothing about length and sends the caller hunting the signer.
            require(bytes.size == expected) {
                "${type.wire} $what key should be $expected bytes, got ${bytes.size}"
            }
        }
    }
}
