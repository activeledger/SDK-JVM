package io.activeledger.sdk.java;

import io.activeledger.sdk.crypto.KeyPair;
import io.activeledger.sdk.crypto.KeyType;
import io.activeledger.sdk.json.CanonicalJson;
import io.activeledger.sdk.json.JsonObject;
import io.activeledger.sdk.tx.Transaction;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deliberately Java, not Kotlin.
 *
 * A facade tested only from Kotlin proves nothing about the experience it
 * exists to provide. Nullability annotations, @JvmStatic, default arguments
 * and suspend-function bridging all look fine from Kotlin and are unusable or
 * ugly from Java, and that only shows up when the caller really is Java.
 */
class JavaFacadeTest {

    @Test
    void keysGenerateAndSignFromJava() {
        KeyPair key = KeyPair.generate(KeyType.ML_DSA_65);
        byte[] message = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] signature = key.sign(message);
        assertTrue(key.verify(message, signature));
        assertEquals(3309, signature.length);
    }

    @Test
    void falconKeysWorkFromJava() {
        KeyPair key = KeyPair.generate(KeyType.FALCON_512);
        byte[] message = "hello".getBytes(StandardCharsets.UTF_8);
        assertTrue(key.verify(message, key.sign(message)));
        assertEquals(897, Base64.getDecoder().decode(key.exportPublic()).length);
    }

    @Test
    void keyTypeStaticsAreReachableFromJava() {
        // @JvmStatic - without it this would read KeyType.Companion.fromWire.
        assertEquals(KeyType.ML_DSA_65, KeyType.fromWire("ml-dsa-65"));
        assertEquals("falcon-512", KeyType.FALCON_512.getWire());
    }

    @Test
    void onboardTransactionBuildsFromJava() {
        KeyPair key = KeyPair.generate(KeyType.ML_DSA_65);
        // @JvmOverloads - the label argument has a default in Kotlin, and
        // without it Java would have to supply it on every call.
        Transaction tx = Transaction.onboard(key);
        assertTrue(tx.getSelfSign());
        assertTrue(tx.getSigs().containsKey("identity"));
        assertNotNull(tx.toJson());
    }

    @Test
    void transactionBuilderChainsFromJava() {
        KeyPair key = KeyPair.generate(KeyType.FALCON_512);
        Transaction tx = Transaction.builder()
                .namespace("default")
                .contract("demo")
                .input("streamA", key)
                .build();
        assertTrue(tx.getSigs().containsKey("streamA"));
        byte[] signature = Base64.getDecoder().decode(tx.getSigs().get("streamA"));
        assertTrue(key.verify(tx.signedBytes(), signature));
    }

    @Test
    void canonicalJsonIsUsableFromJava() {
        JsonObject o = new JsonObject().put("zebra", 1).put("alpha", 2);
        assertEquals("{\"zebra\":1,\"alpha\":2}", CanonicalJson.INSTANCE.stringify(o));
    }

    @Test
    void clientIsAutoCloseableFromJava() {
        // try-with-resources: the subscription scope must be cancellable
        // without a Kotlin CoroutineScope ever appearing in Java code.
        try (ActiveledgerClient client = new ActiveledgerClient("http://localhost:1")) {
            assertNotNull(client);
        }
    }
}
