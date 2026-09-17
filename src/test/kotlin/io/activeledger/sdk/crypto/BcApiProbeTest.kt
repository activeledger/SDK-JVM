package io.activeledger.sdk.crypto

import com.google.gson.JsonParser
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import org.bouncycastle.crypto.signers.MLDSASigner
import org.bouncycastle.pqc.crypto.falcon.FalconParameters
import org.bouncycastle.pqc.crypto.falcon.FalconPrivateKeyParameters
import org.bouncycastle.pqc.crypto.falcon.FalconPublicKeyParameters
import org.bouncycastle.pqc.crypto.falcon.FalconSigner
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the BouncyCastle API shapes this SDK depends on, against real vectors.
 *
 * Not a test of our own code - a test of the assumptions underneath it. Every
 * one of these was read out of BC 1.85.2's source and could change under a
 * version bump, which is exactly why the dependency is pinned. If this file
 * starts failing after an upgrade, the upgrade is the thing to look at.
 *
 * The Falcon private key shape is the surprising one: BC wants f, g and F as
 * three separate arrays plus a public key, while the ledger's private key is
 * one 1281-byte blob containing none of the public key. That mismatch decides
 * the SDK's own API, so it is established here before anything is built on it.
 */
class BcApiProbeTest {

    private data class Vector(
        val type: String,
        val message: String,
        val publicKey: ByteArray,
        val privateKey: ByteArray,
        val signature: ByteArray,
    )

    private val vectors: List<Vector> by lazy {
        val json = javaClass.getResourceAsStream("/pq-vectors.json")!!.bufferedReader().readText()
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("vectors").map {
            val v = it.asJsonObject
            Vector(
                type = v.get("type").asString,
                message = v.get("message").asString,
                publicKey = Base64.getDecoder().decode(v.get("publicKey").asString),
                privateKey = Base64.getDecoder().decode(v.get("privateKey").asString),
                signature = Base64.getDecoder().decode(v.get("signature").asString),
            )
        }
    }

    // ---- ML-DSA -----------------------------------------------------------

    @Test
    fun `ml-dsa accepts the ledger's 4032-byte expanded key directly`() {
        val v = vectors.first { it.type == "ml-dsa-65" }
        val prv = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, v.privateKey)
        val signer = MLDSASigner().apply { init(true, prv) }
        signer.update(v.message.toByteArray(Charsets.UTF_8), 0, v.message.toByteArray(Charsets.UTF_8).size)
        val sig = signer.generateSignature()
        assertTrue(sig.size == 3309, "ml-dsa signature was ${sig.size} bytes")
    }

    // The property that matters: BC's default path produces signatures the
    // reference implementation's public key accepts. If BC were on a
    // different FIPS 204 mode (internal, or prehash) this fails.
    @Test
    fun `ml-dsa verifies the reference implementation's signatures`() {
        for (v in vectors.filter { it.type == "ml-dsa-65" }) {
            val pub = MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, v.publicKey)
            val verifier = MLDSASigner().apply { init(false, pub) }
            val msg = v.message.toByteArray(Charsets.UTF_8)
            verifier.update(msg, 0, msg.size)
            assertTrue(verifier.verifySignature(v.signature), "failed to verify a published ml-dsa signature")
        }
    }

    @Test
    fun `ml-dsa signatures made here verify against the reference public key`() {
        for (v in vectors.filter { it.type == "ml-dsa-65" }) {
            val msg = v.message.toByteArray(Charsets.UTF_8)
            val prv = MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, v.privateKey)
            val signer = MLDSASigner().apply { init(true, prv) }
            signer.update(msg, 0, msg.size)
            val mine = signer.generateSignature()

            val pub = MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, v.publicKey)
            val verifier = MLDSASigner().apply { init(false, pub) }
            verifier.update(msg, 0, msg.size)
            assertTrue(verifier.verifySignature(mine), "reference key rejected a signature made here")
        }
    }

    // ---- Falcon -----------------------------------------------------------

    // The ledger's 1281-byte private key is header || f || g || F with no
    // public key in it. BC wants the three polynomials separately AND the
    // public key. Sizes for falcon-512: f and g are 384 bytes each, F is 512.
    @Test
    fun `falcon private key splits into f, g and F at the documented sizes`() {
        val v = vectors.first { it.type == "falcon-512" }
        val body = PqKeyCodec.importFalconPrivate(v.privateKey)
        assertTrue(body.size == 1280, "expected 1280 body bytes, got ${body.size}")
        assertTrue(384 + 384 + 512 == body.size, "f + g + F should account for every byte")
    }

    @Test
    fun `falcon verifies the reference implementation's signatures`() {
        for (v in vectors.filter { it.type == "falcon-512" }) {
            val h = PqKeyCodec.importFalconPublic(v.publicKey)
            val pub = FalconPublicKeyParameters(FalconParameters.falcon_512, h)
            val verifier = FalconSigner().apply { init(false, pub) }
            assertTrue(
                verifier.verifySignature(v.message.toByteArray(Charsets.UTF_8), v.signature),
                "failed to verify a published falcon signature"
            )
        }
    }

    @Test
    fun `falcon signatures made here verify against the reference public key`() {
        for (v in vectors.filter { it.type == "falcon-512" }) {
            val body = PqKeyCodec.importFalconPrivate(v.privateKey)
            val h = PqKeyCodec.importFalconPublic(v.publicKey)
            val f = body.copyOfRange(0, 384)
            val g = body.copyOfRange(384, 768)
            val bigF = body.copyOfRange(768, 1280)

            val prv = FalconPrivateKeyParameters(FalconParameters.falcon_512, f, g, bigF, h)
            val signer = FalconSigner().apply { init(true, prv) }
            val mine = signer.generateSignature(v.message.toByteArray(Charsets.UTF_8))

            assertTrue(mine.size in 649..662, "falcon signature was ${mine.size} bytes")
            assertTrue(mine[0] == 0x39.toByte(), "falcon signature header was 0x%02x".format(mine[0]))

            val pub = FalconPublicKeyParameters(FalconParameters.falcon_512, h)
            val verifier = FalconSigner().apply { init(false, pub) }
            assertTrue(
                verifier.verifySignature(v.message.toByteArray(Charsets.UTF_8), mine),
                "reference key rejected a falcon signature made here"
            )
        }
    }

    // Round-trips BC's own encoding, confirming getEncoded() is f || g || F
    // and therefore that the split above is the right one.
    @Test
    fun `falcon getEncoded round-trips to the ledger's private key form`() {
        val v = vectors.first { it.type == "falcon-512" }
        val body = PqKeyCodec.importFalconPrivate(v.privateKey)
        val h = PqKeyCodec.importFalconPublic(v.publicKey)
        val prv = FalconPrivateKeyParameters(
            FalconParameters.falcon_512,
            body.copyOfRange(0, 384),
            body.copyOfRange(384, 768),
            body.copyOfRange(768, 1280),
            h,
        )
        assertTrue(prv.encoded.contentEquals(body), "getEncoded() did not reproduce f || g || F")
        assertTrue(
            PqKeyCodec.exportFalconPrivate(prv.encoded).contentEquals(v.privateKey),
            "re-export did not reproduce the ledger's private key bytes"
        )
    }
}
