package io.activeledger.sdk.java;

import io.activeledger.sdk.Identity;
import io.activeledger.sdk.LedgerResponse;
import io.activeledger.sdk.crypto.KeyPair;
import io.activeledger.sdk.crypto.KeyType;
import io.activeledger.sdk.tx.Transaction;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blocking wrapper, driven from Java against a local mock node: the
 * suspend functions underneath must come out as plain blocking calls.
 */
class ActiveledgerClientTest {

    private MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    private ActiveledgerClient client() {
        return new ActiveledgerClient(server.url("/").toString());
    }

    @Test
    void submitRawBlocksAndReturnsTheParsedResponse() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"$summary\":{\"total\":1,\"vote\":1,\"commit\":1},\"$streams\":{\"new\":[{\"id\":\"s1\"}]}}"));
        try (ActiveledgerClient client = client()) {
            LedgerResponse response = client.submitRaw("{\"a\":1}");
            assertEquals(1, response.getNewStreams().size());
            assertEquals("s1", response.getNewStreams().get(0));
            assertTrue(response.getCommitted());
        }
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("{\"a\":1}", request.getBody().readUtf8());
    }

    @Test
    void submitSendsTheTransactionAndReportsLedgerErrors() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"$summary\":{\"errors\":[\"1220\"]}}"));
        KeyPair key = KeyPair.generate(KeyType.SECP256K1);
        Transaction tx = Transaction.onboard(key);
        try (ActiveledgerClient client = client()) {
            LedgerResponse response = client.submit(tx);
            assertFalse(response.getCommitted());
            assertEquals("1220", response.getErrors().get(0));
        }
        assertEquals(tx.toJson(), server.takeRequest().getBody().readUtf8());
    }

    @Test
    void onboardReturnsTheIdentityForTheKeyGiven() {
        server.enqueue(new MockResponse().setBody("{\"$streams\":{\"new\":[{\"id\":\"abc\"}]}}"));
        KeyPair key = KeyPair.generate(KeyType.ML_DSA_65);
        try (ActiveledgerClient client = client()) {
            Identity identity = client.onboard(key);
            assertEquals("abc", identity.getStreamId());
            assertSame(key, identity.getKeyPair());
        }
    }

    @Test
    void onboardFailureIsAnExceptionInJava() {
        server.enqueue(new MockResponse().setBody("{\"$summary\":{\"errors\":[\"nope\"]}}"));
        try (ActiveledgerClient client = client()) {
            IllegalStateException e = assertThrows(
                    IllegalStateException.class, () -> client.onboard(KeyPair.generate(KeyType.SECP256K1)));
            assertTrue(e.getMessage().contains("Onboard failed"));
        }
    }

    @Test
    void closeIsIdempotent() {
        ActiveledgerClient client = client();
        client.close();
        client.close();
    }
}
