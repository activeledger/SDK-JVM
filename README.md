# Activeledger SDK for JVM, Java and Android

Kotlin SDK for [Activeledger](https://github.com/activeledger/activeledger), with post-quantum identity support.

One artefact serves Kotlin, Java and Android. Replaces `SDK-Kotlin`, `SDK-Java` and `SDK-Android`, none of which supported post-quantum identities.

**Requires Activeledger 4.7.0 or later** for `ml-dsa-65` and `falcon-512`.

## Install

```kotlin
dependencies {
    implementation("io.github.activeledger:activeledger-sdk:0.1.1")
}
```

Android: `minSdk 26`. The bytecode is checked against the Android 26 API surface at build time, so an API the floor lacks fails our build rather than your users' phones.

## Key types

| Type | Wire string | Public key | Signature |
| --- | --- | --- | --- |
| ML-DSA-65 | `ml-dsa-65` | 1952 bytes | 3309 bytes |
| Falcon-512 | `falcon-512` | 897 bytes | 649-662 bytes, variable |
| secp256k1 | `secp256k1` | 33 or 65 bytes | ~70-72 bytes, variable |
| secp256k1 | `secp256k1` | 65 bytes | ~70-72 bytes DER |
| RSA | `rsa` | — | — |

Post-quantum keys are base64 of raw algorithm bytes. Falcon signature length **varies** — nothing may assume it fixed.

**secp256k1 is encoded completely differently**, and reusing the base64 path
for it produces material the ledger rejects as 1220 "Signature Incorrect"
while saying nothing else:

- **Keys are `0x`-prefixed hex, not base64.** The prefix is required rather
  than tolerated, because hex without it can decode as base64 into
  plausible-looking bytes of the wrong length.
- **Public keys have two valid lengths** — 33 compressed and 65 uncompressed.
  The ledger accepts both; `KeyPair.generate` produces compressed, and
  `KeyPair.generateSecp256k1(compressed = false)` the other.
- **Private scalars are left-padded to 32 bytes.** A leading zero byte occurs
  about once in 400 keys, and an unpadded key is a different value.
- **Signatures are SHA-256 → ECDSA → DER**, and DER length varies.

**Signing is deterministic (RFC 6979) and always low-S.** Emitting low-S is
not for the ledger, which accepts either; it is for everything else, because
`@noble/curves` rejects high-S unless told not to and libsecp256k1 rejects it
outright. Verification deliberately does **not** enforce low-S, because the
ledger verifies through OpenSSL and produces high-S freely — rejecting those
would be the same bug in the opposite direction.

Because signing is deterministic, this SDK's secp256k1 signatures are
byte-identical to `@noble/curves` for the same key and message, and the test
suite asserts exactly that against published reference bytes.

Use secp256k1 when you do not need post-quantum guarantees: it is roughly
**22x smaller** per transaction, works with hardware wallets and HSMs, and is
the only way to sign for an identity created before post-quantum support.

## Kotlin

```kotlin
import io.activeledger.sdk.Activeledger
import io.activeledger.sdk.crypto.KeyPair
import io.activeledger.sdk.crypto.KeyType
import io.activeledger.sdk.tx.Transaction

suspend fun main() {
    val ledger = Activeledger("http://localhost:5260")

    // Onboard a post-quantum identity
    val key = KeyPair.generate(KeyType.ML_DSA_65)
    val identity = ledger.connection.onboard(key)
    println("identity: ${identity.streamId}")

    // Send a transaction
    val tx = Transaction.builder()
        .namespace("mynamespace")
        .contract("mycontract")
        .input(identity.streamId, identity.keyPair, mapOf("message" to "hello"))
        .build()

    val response = ledger.connection.submit(tx)
    check(response.committed) { "rejected: ${response.errors}" }

    // Subscribe to events
    ledger.events.subscribe().collect { event ->
        println("${event.name}: ${event.data}")
    }
}
```

## Java

```java
import io.activeledger.sdk.Identity;
import io.activeledger.sdk.LedgerResponse;
import io.activeledger.sdk.crypto.KeyPair;
import io.activeledger.sdk.crypto.KeyType;
import io.activeledger.sdk.java.ActiveledgerClient;
import io.activeledger.sdk.tx.Transaction;

public class Example {
    public static void main(String[] args) {
        try (ActiveledgerClient client = new ActiveledgerClient("http://localhost:5260")) {

            KeyPair key = KeyPair.generate(KeyType.FALCON_512);
            Identity identity = client.onboard(key);
            System.out.println("identity: " + identity.getStreamId());

            Transaction tx = Transaction.builder()
                    .namespace("mynamespace")
                    .contract("mycontract")
                    .input(identity.getStreamId(), identity.getKeyPair())
                    .build();

            LedgerResponse response = client.submit(tx);
            if (!response.getCommitted()) {
                throw new IllegalStateException("rejected: " + response.getErrors());
            }

            try (var subscription = client.subscribe(event ->
                    System.out.println(event.getName() + ": " + event.getData()))) {
                Thread.sleep(10_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

Blocking calls, listener-based events. Same implementation underneath.

## Reading state

There is no separate read API, and no storage URL. A node's storage service
listens only on the node's own host, so it is not something a client can
reach — the JavaScript SDK does not touch it either.

State is read through a transaction. Name the streams in `$r`, and have the
contract hand values back with `returnToRemote`:

```kotlin
val tx = Transaction.builder()
    .namespace("mynamespace")
    .contract("mycontract")
    .input(identity.streamId, identity.keyPair)
    .readonly("target", someStreamId)   // arrives as $r
    .build()

val response = ledger.connection.submit(tx)
response.responses().forEach { println(it) }  // whatever returnToRemote sent
```

## Things that will bite you

**Always send the key type.** The ledger defaults a missing `type` to `"rsa"` and then attempts RSA verification against a base64 post-quantum blob. The SDK always sends it; if you build an envelope by hand, do the same.

**A rejected transaction is HTTP 200.** Check `response.committed`, not the status code.

**Errors are unhelpful by design.** A wrong type string, a wrong-length key, or bytes that differ by one escape all surface as **1220 "Signature Incorrect"** — never as "unknown algorithm" or "bad key length". The SDK validates key lengths and type strings up front so these fail here, with a message naming the problem, rather than at a node.

**Signing is hedged.** Two signatures over the same message differ, and both verify. This matches the reference implementation; FIPS 204 permits a deterministic variant, which is deliberately not used.

## Building

```bash
./gradlew build          # compile, unit tests, Android API check
./gradlew integrationTest # needs a live network, see below
```

Integration tests run against a real 4-node network. From an `activeledger` checkout:

```bash
npm run test:network:serve
```

then, with the URLs it prints:

```bash
AL_NODES=http://127.0.0.1:5510 AL_STORAGE=http://127.0.0.1:5509 ./gradlew integrationTest
```

Without `AL_NODES` they skip, so `./gradlew build` works with no ledger present.

## How correctness is established

Signatures cover the exact bytes of `JSON.stringify($tx)` as UTF-8 — no hash prefix, no length prefix, no key sorting. Four things a general-purpose JSON library gets wrong here (HTML escaping, non-ASCII escaping, key sorting, number formatting) all produce identical output on an ASCII-only integer payload, so this SDK serialises by hand and checks itself against published cross-language vectors generated by the JavaScript SDK, plus a live network.

## Licence

MIT
