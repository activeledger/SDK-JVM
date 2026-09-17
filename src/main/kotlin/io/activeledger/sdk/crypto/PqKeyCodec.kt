package io.activeledger.sdk.crypto

/**
 * Adds and strips the 1-byte Falcon key header that BouncyCastle omits.
 *
 * BouncyCastle's raw key getters return Falcon keys **without** their type
 * header: `FalconPublicKeyParameters` holds 896 bytes rather than 897, and
 * `FalconPrivateKeyParameters.getEncoded()` returns 1280 rather than 1281.
 * The bit packing is identical - BC's decoder simply strips the header byte.
 *
 * The ledger, and the JavaScript SDK it agrees with, use the full form. A
 * port that skips this produces keys that are "one byte off", which the
 * ledger reports as 1220 "Signature Incorrect" - a message about signatures,
 * for a problem entirely about key length. That mismatch is the single most
 * likely first bug in a JVM port, which is why it lives in its own file with
 * its own tests rather than being two lines inside the signer.
 *
 * Signatures are unaffected: a Falcon signature already carries its own
 * header byte (0x39 for Falcon-512), written by `FalconNIST.crypto_sign()`
 * and checked by `FalconSigner.verifySignature()`.
 */
object PqKeyCodec {

    /** Falcon-512 public key header. LOGN=9, encoded as 0x00 + LOGN. */
    const val FALCON_PUBLIC_HEADER: Byte = 0x09

    /** Falcon-512 private key header. 0x50 + LOGN. */
    const val FALCON_PRIVATE_HEADER: Byte = 0x59

    const val FALCON_PUBLIC_BYTES = 897
    const val FALCON_PRIVATE_BYTES = 1281

    private const val BC_PUBLIC_BYTES = FALCON_PUBLIC_BYTES - 1
    private const val BC_PRIVATE_BYTES = FALCON_PRIVATE_BYTES - 1

    /** Takes BouncyCastle's 896 bytes, returns the ledger's 897. */
    fun exportFalconPublic(bcBytes: ByteArray): ByteArray =
        withHeader(bcBytes, BC_PUBLIC_BYTES, FALCON_PUBLIC_HEADER, "public")

    /** Takes BouncyCastle's 1280 bytes, returns the ledger's 1281. */
    fun exportFalconPrivate(bcBytes: ByteArray): ByteArray =
        withHeader(bcBytes, BC_PRIVATE_BYTES, FALCON_PRIVATE_HEADER, "private")

    /** Takes the ledger's 897 bytes, returns BouncyCastle's 896. */
    fun importFalconPublic(bytes: ByteArray): ByteArray =
        withoutHeader(bytes, FALCON_PUBLIC_BYTES, FALCON_PUBLIC_HEADER, "public")

    /** Takes the ledger's 1281 bytes, returns BouncyCastle's 1280. */
    fun importFalconPrivate(bytes: ByteArray): ByteArray =
        withoutHeader(bytes, FALCON_PRIVATE_BYTES, FALCON_PRIVATE_HEADER, "private")

    private fun withHeader(bytes: ByteArray, expected: Int, header: Byte, what: String): ByteArray {
        require(bytes.size == expected) {
            "BouncyCastle falcon-512 $what key should be $expected bytes, got ${bytes.size}"
        }
        val out = ByteArray(expected + 1)
        out[0] = header
        bytes.copyInto(out, 1)
        return out
    }

    private fun withoutHeader(bytes: ByteArray, expected: Int, header: Byte, what: String): ByteArray {
        // Rejecting rather than quietly accepting. A wrong-length key that
        // reaches the ledger fails as "Signature Incorrect", which sends the
        // caller hunting the wrong thing entirely - so it fails here, with
        // the numbers in the message.
        require(bytes.size == expected) {
            "falcon-512 $what key should be $expected bytes, got ${bytes.size}" +
                if (bytes.size == expected - 1) {
                    " - this looks like a BouncyCastle key with its header byte stripped"
                } else {
                    ""
                }
        }
        require(bytes[0] == header) {
            "falcon-512 $what key should start with 0x%02x, got 0x%02x".format(header, bytes[0])
        }
        return bytes.copyOfRange(1, bytes.size)
    }
}
