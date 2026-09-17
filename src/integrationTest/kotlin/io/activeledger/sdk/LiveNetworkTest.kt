package io.activeledger.sdk

import com.google.gson.JsonParser
import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.crypto.KeyType
import io.activeledger.sdk.tx.Transaction
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs against a real 4-node Activeledger network.
 *
 * Everything else in this repository checks the SDK against a published file.
 * This checks it against a running ledger, which is the only thing that
 * actually decides whether a signature is acceptable. A port can pass every
 * unit test and still be rejected by a node - the `type` string, the `$sigs`
 * keying and the exact signed bytes are all invisible to a unit test.
 *
 * Start the network from the ledger checkout:
 *
 *     npm run test:network:serve
 *
 * then run with the base URL it prints:
 *
 *     AL_NODES=http://127.0.0.1:5510 ./gradlew integrationTest
 *
 * Skips rather than fails when AL_NODES is unset, so `./gradlew build` works
 * with no ledger present.
 */
class LiveNetworkTest {

    private val nodes: List<String> =
        System.getenv("AL_NODES")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    // Storage is a separate service on its own port - a node answers
    // "Forbidden" for a stream read. serve.ts prints both.
    private val storage: List<String> =
        System.getenv("AL_STORAGE")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    private fun ledger(index: Int = 0) = Activeledger(nodes[index])

    /**
     * Reads a document straight from a node's storage service.
     *
     * Deliberately here in the test and NOT in the SDK. Storage listens only
     * on the node's own host, so it is not something a client can reach - the
     * SDK reads state through a transaction's $r instead. This suite runs
     * against a local harness, where storage is reachable by definition, and
     * uses it to assert what the ledger actually recorded rather than what a
     * contract chose to hand back.
     */
    private fun storageRead(index: Int, id: String): String {
        val url = storage[index].trimEnd('/') + "/activeledger/" +
            java.net.URLEncoder.encode(id, "UTF-8")
        java.net.URL(url).openStream().use { return it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun requireNetwork() {
        assumeTrue(nodes.isNotEmpty(), "AL_NODES not set - start 'npm run test:network:serve' in the ledger repo")
    }

    private fun requireStorage() {
        requireNetwork()
        assumeTrue(storage.isNotEmpty(), "AL_STORAGE not set - stream reads need the storage endpoint")
    }

    // Unique per run. Namespaces are claimed permanently on the ledger, so a
    // fixed name passes the first time and fails every re-run against the
    // same network - which reads exactly like a regression and is not one.
    private fun namespaceFor(type: KeyType) =
        "jvm" + type.wire.replace("-", "") + System.nanoTime().toString().takeLast(8)

    private suspend fun registerNamespace(connection: Connection, identity: Identity, namespace: String) {
        val tx = Transaction.builder()
            .namespace("default")
            .contract("namespace")
            .input(identity.streamId, identity.keyPair, mapOf("namespace" to namespace))
            .build()
        val response = connection.submit(tx)
        assertTrue(response.committed, "namespace registration failed: $response")
    }

    @Test
    fun `ml-dsa identity onboards and is recorded correctly`() = runBlocking {
        requireStorage()
        onboardAndCheck(KeyType.ML_DSA_65, expectedPublicBytes = 1952)
    }

    @Test
    fun `falcon identity onboards and is recorded correctly`() = runBlocking {
        requireStorage()
        onboardAndCheck(KeyType.FALCON_512, expectedPublicBytes = 897)
    }

    private suspend fun onboardAndCheck(type: KeyType, expectedPublicBytes: Int) {
        val connection = ledger().connection
        val identity = connection.onboard(KeyPair.generate(type))
        assertTrue(identity.streamId.isNotEmpty(), "onboarding returned no stream id")

        // The meta is what the engine will verify against later, so the type
        // string and key length landing there correctly is the whole point.
        val meta = JsonParser.parseString(storageRead(0, "${identity.streamId}:stream")).asJsonObject
        val authority = meta.getAsJsonArray("authorities").first().asJsonObject
        assertEquals(type.wire, authority.get("type").asString, "authority type on the ledger")
        assertEquals(
            expectedPublicBytes,
            Base64.getDecoder().decode(authority.get("public").asString).size,
            "public key byte length on the ledger",
        )
    }

    @Test
    fun `a transaction signed by this SDK is accepted`() = runBlocking {
        requireNetwork()
        for (type in listOf(KeyType.ML_DSA_65, KeyType.FALCON_512)) {
            val connection = ledger().connection
            val identity = connection.onboard(KeyPair.generate(type))
            registerNamespace(connection, identity, namespaceFor(type))
        }
    }

    // The case that would pass against an implementation accepting anything.
    @Test
    fun `a tampered payload is rejected`() = runBlocking {
        requireNetwork()
        val connection = ledger().connection
        val identity = connection.onboard(KeyPair.generate(KeyType.ML_DSA_65))

        val honest = Transaction.builder()
            .namespace("default")
            .contract("namespace")
            .input(identity.streamId, identity.keyPair, mapOf("namespace" to "jvmtamper"))
            .build()

        // Same signature, different body - submitted by hand rather than
        // through the builder, which would re-sign it.
        val tampered = honest.toJson().replace("jvmtamper", "jvmstolen")
        val response = connection.submitRaw(tampered)
        assertTrue(!response.committed, "a tampered payload was accepted: $response")
    }

    @Test
    fun `an identity onboarded here is visible from every node`() = runBlocking {
        requireStorage()
        assumeTrue(nodes.size > 1, "only one node configured")

        val identity = ledger().connection.onboard(KeyPair.generate(KeyType.FALCON_512))

        // Consensus is a majority, so the origin's reply means most nodes
        // have committed - the rest may still be writing.
        val deadline = System.currentTimeMillis() + 15_000
        var seen: List<Boolean>
        while (true) {
            seen = nodes.indices.map { i ->
                runCatching { storageRead(i, "${identity.streamId}:stream").contains("falcon-512") }
                    .getOrDefault(false)
            }
            if (seen.all { it } || System.currentTimeMillis() > deadline) break
            Thread.sleep(500)
        }
        assertTrue(seen.all { it }, "identity not visible on every node: $seen")
    }
}
