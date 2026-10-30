import io.activeledger.sdk.LedgerResponse;
import io.activeledger.sdk.crypto.KeyPair;
import io.activeledger.sdk.crypto.KeyType;
import io.activeledger.sdk.java.ActiveledgerClient;
import io.activeledger.sdk.tx.Transaction;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public class SmokeJava {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("SMOKE FAILED (java): " + what);
    }

    public static void main(String[] args) throws Exception {
        byte[] msg = "smoke".getBytes(StandardCharsets.UTF_8);
        KeyPair[] keys = {
            KeyPair.generate(KeyType.ML_DSA_65),
            KeyPair.generate(KeyType.FALCON_512),
            KeyPair.generate(KeyType.SECP256K1),
            KeyPair.generateSecp256k1(false),
        };
        for (KeyPair key : keys) {
            byte[] sig = key.sign(msg);
            check(key.verify(msg, sig), key.getType() + " verifies");
            check(!key.verify("other".getBytes(StandardCharsets.UTF_8), sig), key.getType() + " rejects other message");
            check(KeyPair.fromPublic(key.getType(), key.exportPublic()).verify(msg, sig), key.getType() + " public-only verifies");
        }
        try {
            KeyPair.generate(KeyType.RSA);
            check(false, "RSA generate must be unsupported");
        } catch (UnsupportedOperationException expected) {
            // expected
        }

        MockWebServer server = new MockWebServer();
        server.start();
        try (ActiveledgerClient client = new ActiveledgerClient(server.url("/").toString())) {
            server.enqueue(new MockResponse().setBody("{\"$streams\":{\"new\":[{\"id\":\"stream-1\"}]}}"));
            server.enqueue(new MockResponse().setBody("{\"$summary\":{\"errors\":[\"1220: Signature Incorrect\"]}}"));

            for (KeyPair key : keys) {
                Transaction tx = Transaction.builder().namespace("default").contract("c").entry("e")
                    .input("stream-1", key).readonly("r", "stream-2").build();
                byte[] sig = Base64.getDecoder().decode(tx.getSigs().get("stream-1"));
                check(key.verify(tx.signedBytes(), sig), key.getType() + " transaction signature verifies");
            }

            Transaction tx = Transaction.builder().namespace("default").contract("c")
                .input("stream-1", keys[0]).build();
            LedgerResponse ok = client.submit(tx);
            check(ok.getCommitted() && ok.getNewStreams().equals(java.util.List.of("stream-1")), "committed reply");
            check(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8().equals(tx.toJson()), "body is the signed envelope");

            LedgerResponse bad = client.submit(tx);
            check(!bad.getCommitted() && bad.getErrors().get(0).startsWith("1220"), "rejected-200 reply is not committed");
        } finally {
            server.shutdown();
        }
        System.out.println("SMOKE OK (java)");
    }
}
