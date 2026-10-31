import io.activeledger.sdk.Connection
import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.crypto.KeyType
import io.activeledger.sdk.tx.Transaction
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import java.util.concurrent.TimeUnit

private fun check(ok: Boolean, what: String) {
    if (!ok) throw AssertionError("SMOKE FAILED (kotlin): $what")
}

fun main() {
    val msg = "smoke".toByteArray()
    val keys = listOf(
        KeyType.ML_DSA_65 to KeyPair.generate(KeyType.ML_DSA_65),
        KeyType.FALCON_512 to KeyPair.generate(KeyType.FALCON_512),
        KeyType.SECP256K1 to KeyPair.generate(KeyType.SECP256K1),
        KeyType.SECP256K1 to KeyPair.generateSecp256k1(false),
    )
    for ((type, key) in keys) {
        check(key.type == type, "type $type")
        val sig = key.sign(msg)
        check(key.verify(msg, sig), "$type verifies its own signature")
        check(!key.verify("other".toByteArray(), sig), "$type rejects another message")
        val publicOnly = KeyPair.fromPublic(type, key.exportPublic())
        check(publicOnly.verify(msg, sig), "$type verifies from the exported public key")
    }
    try {
        KeyPair.generate(KeyType.RSA)
        check(false, "RSA generate must be unsupported")
    } catch (_: UnsupportedOperationException) {
    }

    val server = MockWebServer()
    server.start()
    try {
        server.enqueue(MockResponse().setBody("""{"${'$'}summary":{"total":1,"vote":1,"commit":1},"${'$'}streams":{"new":[{"id":"stream-1"}]}}"""))
        server.enqueue(MockResponse().setBody("""{"${'$'}summary":{"errors":["1220: Signature Incorrect"]}}"""))
        val connection = Connection(server.url("/").toString())

        for ((type, key) in keys.take(3)) {
            val tx = Transaction.builder().namespace("default").contract("c").entry("e")
                .input("stream-1", key, mapOf("n" to 1)).readonly("r", "stream-2").build()
            val sig = Base64.getDecoder().decode(tx.sigs.getValue("stream-1"))
            check(key.verify(tx.signedBytes(), sig), "$type transaction signature verifies")
        }

        val key = keys.first().second
        val tx = Transaction.builder().namespace("default").contract("c")
            .input("stream-1", key).build()
        val committed = runBlocking { connection.submit(tx) }
        check(committed.committed && committed.newStreams == listOf("stream-1"), "committed reply")
        check(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8() == tx.toJson(), "body is the signed envelope")

        val rejected = runBlocking { connection.submit(tx) }
        check(!rejected.committed && rejected.errors.single().startsWith("1220"), "rejected-200 reply is not committed")
    } finally {
        server.shutdown()
    }
    println("SMOKE OK (kotlin)")
}
