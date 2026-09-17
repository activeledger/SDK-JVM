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

    /** Base64 of the raw public key bytes, as the ledger expects them. */
    fun exportPublic(): String = Base64.getEncoder().encodeToString(publicBytes)

    /** Base64 of the raw private key bytes. Throws if this is verify-only. */
    fun exportPrivate(): String =
        Base64.getEncoder().encodeToString(
            privateBytes ?: throw IllegalStateException("This key pair has no private key - it was created for verification only")
        )

    val canSign: Boolean get() = privateBytes != null

    fun sign(message: ByteArray): ByteArray {
        val prv = privateBytes
            ?: throw IllegalStateException("This key pair has no private key - it was created for verification only")
        return when (type) {
            KeyType.ML_DSA_65 -> signMlDsa(message, prv)
            KeyType.FALCON_512 -> signFalcon(message, prv)
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
        private val PRIVATE_BYTES = mapOf(KeyType.ML_DSA_65 to 4032, KeyType.FALCON_512 to 1281)

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
            else -> throw UnsupportedOperationException("${type.wire} generation is not implemented yet")
        }

        /** A verify-only key pair. */
        @JvmStatic
        fun fromPublic(type: KeyType, publicKeyBase64: String): KeyPair {
            val pub = decode(publicKeyBase64, "public")
            checkLength(type, pub, PUBLIC_BYTES[type], "public")
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
        fun fromKeys(type: KeyType, publicKeyBase64: String, privateKeyBase64: String): KeyPair {
            val pub = decode(publicKeyBase64, "public")
            val prv = decode(privateKeyBase64, "private")
            checkLength(type, pub, PUBLIC_BYTES[type], "public")
            checkLength(type, prv, PRIVATE_BYTES[type], "private")
            return KeyPair(type, pub, prv)
        }

        private fun decode(value: String, what: String): ByteArray = try {
            Base64.getDecoder().decode(value)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("$what key is not valid base64", e)
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
