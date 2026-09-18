package io.activeledger.sdk.crypto

/**
 * The key types Activeledger understands, and their exact wire strings.
 *
 * **These strings are the whole contract.** The ledger validates nothing else
 * about them: the value travels from `$tx.$i[label].type` through
 * `Stream.setAuthority(pubKey, type)`, which does no checking at all, is
 * stored verbatim on `meta.authorities[].type`, and comes back out into
 * `new ActiveCrypto.KeyPair(type, publicKey)` at verification time.
 *
 * Two consequences worth knowing before debugging anything:
 *
 * - A typo in the string, or a key of the wrong length, surfaces as **1220
 *   "Signature Incorrect"** or 1250, never as "unknown algorithm". A port's
 *   first integration bug almost always looks like this.
 * - **The ledger defaults a missing type to `"rsa"`.** Omitting it for a
 *   post-quantum key produces an RSA verification attempt against a base64
 *   blob. So the SDK always sends it explicitly and never relies on a
 *   default.
 *
 * Every public API in this SDK takes a [KeyType] rather than a String.
 * [fromWire] exists only for the boundary - reading a key file, a vector
 * file, a server response - so an unknown value fails there, with a message
 * naming it, instead of travelling to a node and coming back as 1220.
 */
enum class KeyType(val wire: String) {
    RSA("rsa"),
    SECP256K1("secp256k1"),
    ML_DSA_65("ml-dsa-65"),
    FALCON_512("falcon-512");

    /** True for the post-quantum schemes, whose keys are raw bytes in base64. */
    val isPostQuantum: Boolean get() = this == ML_DSA_65 || this == FALCON_512

    override fun toString(): String = wire

    companion object {
        private val byWire = entries.associateBy { it.wire }
        private val ALIASES = mapOf("bitcoin" to SECP256K1, "ethereum" to SECP256K1)

        /**
         * Converts a wire string, throwing on anything unrecognised.
         *
         * Deliberately strict and deliberately not case-insensitive: the
         * ledger compares these exactly, so accepting "ML-DSA-65" here would
         * only move the failure somewhere less informative.
         */
        @JvmStatic
        fun fromWire(value: String): KeyType =
            // The ledger routes 'bitcoin' and 'ethereum' to identical
            // secp256k1 verification, so an existing identity may carry
            // either. Accepted here and NEVER emitted: [wire] always returns
            // 'secp256k1', so three names for one scheme cannot spread.
            byWire[value] ?: ALIASES[value] ?: throw IllegalArgumentException(
                "Unknown key type '$value' - expected one of ${entries.joinToString(", ") { it.wire }}"
            )
    }
}
