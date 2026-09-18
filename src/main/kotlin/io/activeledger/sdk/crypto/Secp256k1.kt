package io.activeledger.sdk.crypto

import java.math.BigInteger
import java.security.SecureRandom
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.ECKeyPairGenerator
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECKeyGenerationParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator

/**
 * secp256k1, encoded the way Activeledger stores it.
 *
 * Public rather than internal because [isHighS] is genuinely useful to a
 * caller: it is how you check whether a signature that arrived from somewhere
 * else is in the canonical low-S form, and Kotlin's `internal` made it
 * unreachable from another module even though it compiles to a public JVM
 * method. The Python SDK exports its equivalent, and there is no reason for
 * this one to differ.
 *
 * Kept apart from [KeyPair]'s post-quantum paths because almost nothing is
 * shared. The post-quantum keys are raw bytes in base64; these are hex with an
 * `0x` prefix. The post-quantum signatures are fixed or near-fixed length raw
 * blobs; these are variable-length DER. Folding the two together invites the
 * one mistake that matters here - reusing a base64 path for a hex key, which
 * produces material the ledger rejects as 1220 "Signature Incorrect" while
 * saying nothing else.
 */
object Secp256k1 {

    /** 33 compressed, 65 uncompressed. The ledger accepts both. */
    const val PUBLIC_COMPRESSED_BYTES = 33
    const val PUBLIC_UNCOMPRESSED_BYTES = 65
    const val PRIVATE_BYTES = 32

    val domain: ECDomainParameters = SECNamedCurves.getByName("secp256k1").let {
        ECDomainParameters(it.curve, it.g, it.n, it.h, it.seed)
    }

    private val halfOrder: BigInteger = domain.n.shiftRight(1)

    /**
     * Generates a key pair, returning (public point, private scalar).
     *
     * Compressed by default: 33 bytes rather than 65, and this key is written
     * into a transaction and then stored on an identity stream permanently.
     */
    fun generate(compressed: Boolean = true): Pair<ByteArray, ByteArray> {
        val generator = ECKeyPairGenerator()
        generator.init(ECKeyGenerationParameters(domain, SecureRandom()))
        val pair = generator.generateKeyPair()

        return Pair(
            (pair.public as ECPublicKeyParameters).q.getEncoded(compressed),
            scalarBytes((pair.private as ECPrivateKeyParameters).d),
        )
    }

    /**
     * A private scalar as a fixed 32 bytes.
     *
     * Left-padded deliberately. A BigInteger drops leading zero bytes, which
     * happens to roughly one key in 400, and the shorter value is a different
     * scalar to anything that reads it strictly. The reference SDK pads for
     * exactly this reason.
     */
    fun scalarBytes(d: BigInteger): ByteArray {
        val raw = d.toByteArray()
        val stripped = if (raw.size > PRIVATE_BYTES && raw[0].toInt() == 0) raw.copyOfRange(1, raw.size) else raw
        if (stripped.size == PRIVATE_BYTES) return stripped

        require(stripped.size < PRIVATE_BYTES) { "scalar is ${stripped.size} bytes, too large for secp256k1" }
        return ByteArray(PRIVATE_BYTES).also { stripped.copyInto(it, PRIVATE_BYTES - stripped.size) }
    }

    /**
     * Signs with a deterministic k (RFC 6979) and normalises S to low.
     *
     * Both are deliberate.
     *
     * **Deterministic k** is not about security - it is about testability. A
     * deterministic signer produces the same bytes for the same key and
     * message in every correct implementation, so exact expected bytes can be
     * published as cross-language vectors. A random k reduces every test to
     * "is this a valid signature", which cannot detect the low-S problem
     * below at all.
     *
     * **Low S** is not for the ledger, which verifies through OpenSSL and
     * accepts either. It is for everything else: `@noble/curves` rejects
     * high-S unless explicitly told not to and is the reference for the
     * JavaScript side, and libsecp256k1 rejects it outright. A signer that
     * emits high-S roughly half the time fails against those verifiers
     * roughly half the time, which reads as flakiness rather than as a
     * signature format problem.
     */
    fun sign(message: ByteArray, privateScalar: ByteArray): ByteArray {
        val key = ECPrivateKeyParameters(BigInteger(1, privateScalar), domain)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, key)

        val (r, s) = signer.generateSignature(sha256(message)).let { it[0] to it[1] }
        return encodeDer(r, lowS(s))
    }

    /**
     * Verifies, accepting HIGH-S as well as low.
     *
     * The other half of the rule above, and the half a library default will
     * get wrong for you. The ledger produces high-S signatures freely, so
     * rejecting them would reject roughly half of everything it makes - an
     * intermittent failure that looks like anything but a configuration flag.
     */
    fun verify(message: ByteArray, signature: ByteArray, publicPoint: ByteArray): Boolean {
        val key = ECPublicKeyParameters(domain.curve.decodePoint(publicPoint), domain)
        val signer = ECDSASigner()
        signer.init(false, key)

        val (r, s) = decodeDer(signature)
        return signer.verifySignature(sha256(message), r, s)
    }

    /** Folds S into the lower half of the curve order. */
    fun lowS(s: BigInteger): BigInteger = if (s > halfOrder) domain.n.subtract(s) else s

    /**
     * Whether a DER signature's S is in the upper half of the curve order.
     *
     * Everything this SDK emits is low-S, so this is for signatures that
     * arrived from elsewhere. The ledger produces high-S freely and this SDK
     * verifies both, but a verifier written against `@noble/curves`,
     * libsecp256k1 or Rust's `k256` rejects high-S by default -- so this is
     * the check to make before handing a signature to one of those.
     */
    fun isHighS(signature: ByteArray): Boolean = decodeDer(signature).second > halfOrder

    private fun sha256(message: ByteArray): ByteArray {
        val digest = SHA256Digest()
        val out = ByteArray(digest.digestSize)
        digest.update(message, 0, message.size)
        digest.doFinal(out, 0)
        return out
    }

    private fun encodeDer(r: BigInteger, s: BigInteger): ByteArray =
        DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).getEncoded("DER")

    private fun decodeDer(signature: ByteArray): Pair<BigInteger, BigInteger> {
        val sequence = ASN1Sequence.getInstance(signature)
        require(sequence.size() == 2) {
            "DER signature has ${sequence.size()} components, expected 2"
        }
        return Pair(
            ASN1Integer.getInstance(sequence.getObjectAt(0)).positiveValue,
            ASN1Integer.getInstance(sequence.getObjectAt(1)).positiveValue,
        )
    }

    /** Encodes bytes the way the ledger stores secp256k1 keys. */
    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(prefix = "0x", separator = "") { "%02x".format(it) }

    /**
     * Decodes an `0x`-prefixed hex key.
     *
     * The prefix is required rather than tolerated: it is part of what the
     * ledger stores, and a hex string without it can decode as base64 into
     * plausible-looking bytes of the wrong length.
     */
    fun fromHex(value: String, what: String): ByteArray {
        require(value.startsWith("0x")) {
            "secp256k1 $what key must start with '0x' - that prefix is part of what the " +
                "ledger stores, not decoration. Post-quantum keys are base64; these are not."
        }

        val body = value.substring(2)
        require(body.length % 2 == 0) {
            "secp256k1 $what key has an odd number of hex digits (${body.length})"
        }

        return try {
            ByteArray(body.length / 2) { body.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("secp256k1 $what key is not valid hex", e)
        }
    }

    /**
     * Checks a public key's length and its SEC1 point prefix.
     *
     * Two valid lengths, and a prefix that has to agree with the one chosen -
     * a mismatch means the caller has mixed up the two forms somewhere.
     */
    fun checkPublic(bytes: ByteArray) {
        require(bytes.size == PUBLIC_COMPRESSED_BYTES || bytes.size == PUBLIC_UNCOMPRESSED_BYTES) {
            "secp256k1 public key is ${bytes.size} bytes, expected " +
                "$PUBLIC_COMPRESSED_BYTES (compressed) or $PUBLIC_UNCOMPRESSED_BYTES (uncompressed)"
        }

        val prefix = bytes[0].toInt() and 0xFF
        val ok = if (bytes.size == PUBLIC_COMPRESSED_BYTES) prefix == 0x02 || prefix == 0x03 else prefix == 0x04
        require(ok) {
            "secp256k1 public key starts with 0x%02x, which does not match its length of %d bytes "
                .format(prefix, bytes.size) + "(expected 0x02/0x03 for 33, 0x04 for 65)"
        }
    }
}
