# Activeledger SDK for JVM, Java and Android

Kotlin SDK for [Activeledger](https://github.com/activeledger/activeledger), with post-quantum identity support.

One artefact serves Kotlin, Java and Android. Replaces `SDK-Kotlin`, `SDK-Java` and `SDK-Android`, none of which supported post-quantum identities.

**Requires Activeledger 4.7.0 or later** for `ml-dsa-65` and `falcon-512`.

## Install

```kotlin
dependencies {
    implementation("io.github.activeledger:activeledger-sdk:0.2.1")
}
```

Android: `minSdk 26`. The bytecode is checked against the Android 26 API surface at build time, so an API the floor lacks fails our build rather than your users' phones.

### BouncyCastle 1.84 or later is required

This is a hard floor, and getting it wrong fails at **runtime**, not at compile
time:

```
NoClassDefFoundError: org/bouncycastle/crypto/generators/MLDSAKeyPairGenerator
```

BouncyCastle moved ML-DSA out of `org.bouncycastle.pqc.crypto.mldsa` and into
`org.bouncycastle.crypto.generators`. This SDK uses the new location. Verified
by inspecting the published jars:

| Version | ML-DSA location |
|---|---|
| 1.78.1 and earlier | absent entirely |
| 1.81 – 1.83 | `pqc.crypto.mldsa` only — **will not work** |
| **1.84 and later** | `crypto.generators` — what this SDK imports |

Built and tested against **1.85.2**.

### Resolving from a GitHub release

A bare jar carries no metadata, so Gradle cannot see this SDK's own dependency
declarations and will silently give you whatever BouncyCastle is already on
your classpath — which is how the error above appears with no obvious cause.

Each release therefore attaches **`ivy.xml`** as well as the jar. A GitHub
release can only be consumed through an ivy repository, and
`IvyArtifactRepository.metadataSources` offers `gradleMetadata()`,
`ivyDescriptor()` and `artifact()` — **but not `mavenPom()`**, which is a
compile error rather than a silent fallback. So use the ivy descriptor:

```kotlin
repositories {
    ivy {
        url = uri("https://github.com/activeledger/SDK-JVM/releases/download")
        patternLayout {
            artifact("v[revision]/[artifact]-[revision](-[classifier])(.[ext])")
            ivy("v[revision]/ivy-[revision].xml")
        }
        metadataSources { gradleMetadata(); ivyDescriptor(); artifact() }
        content { includeGroup("io.github.activeledger") }
    }
    mavenCentral()   // for the transitive dependencies
}
```

Verified by resolving it, not by reading it. That brings the whole graph:

```
io.github.activeledger:activeledger-sdk:0.2.1
org.bouncycastle:bcprov-jdk18on:1.85.2
com.squareup.okhttp3:okhttp:4.12.0
com.google.code.gson:gson:2.11.0
org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
```

**`gradleMetadata()` is required, not optional.** The attached `ivy.xml` is a
stub: `ivy-publish` emits it with an empty `<configurations/>` and a marker
deferring to Gradle Module Metadata. Without `gradleMetadata()`, Gradle reads
the stub, finds dependencies referencing configurations it never declares, and
fails with `Cannot add dependency ... because this configuration doesn't
exist!`. The `.module` file is the real metadata.

**The group is `io.github.activeledger`.** With the wrong group in
`includeGroup`, the repository is simply never consulted and you get "not
found" rather than anything diagnostic.

A `.pom` is attached too, for anyone consuming from a Maven-layout mirror.
It is *not* readable from the ivy path above — ivy repositories have no
`mavenPom()` metadata source.

Maintainers cutting a release: see [RELEASING.md](RELEASING.md). Every asset
above is required, and a release is not done until a fresh project resolves
it.

### Runtime dependencies

Beyond BouncyCastle, this SDK also needs **okhttp 4.12.0**,
**kotlinx-coroutines-core 1.9.0** and **gson 2.11.0** at runtime. The
resolution above brings them. If you take the artifact-only path instead, you
must declare all four yourself, or `ActiveledgerClient` and `EventStream` fail
with `NoClassDefFoundError` in exactly the same way ML-DSA did.

Simplest alternative — declare BouncyCastle yourself and skip the metadata
entirely:

```kotlin
implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
```

None of this applies once the SDK is on Maven Central, which it is not yet.

## Key types

| Type | Wire string | Public key | Signature |
| --- | --- | --- | --- |
| ML-DSA-65 | `ml-dsa-65` | 1952 bytes | 3309 bytes |
| Falcon-512 | `falcon-512` | 897 bytes | 649-662 bytes, variable |
| secp256k1 | `secp256k1` | 33 or 65 bytes | ~70-72 bytes DER, variable |
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

## Seeds and recovery phrases

```kotlin
val key = KeyPair.fromSeed(KeyType.ML_DSA_65, seed)        // 32 bytes
val key = KeyPair.fromSeed(KeyType.FALCON_512, seed)       // 48 bytes
val key = KeyPair.fromPhrase(KeyType.ML_DSA_65, phrase)    // BIP-39
val key = KeyPair.fromPhrase(KeyType.SECP256K1, phrase, "passphrase")
```

The same seed gives the same identity in every Activeledger SDK, which is what
makes a seed the portable private-key format — it is how a private key moves
between languages. It matters most for PHP, whose ML-DSA-65 private key **is**
a 32-byte seed and which has no 4032-byte form at all.

A seed of the wrong length is **refused, not padded**: a padded seed is a
different identity, not a malformed one.

For `secp256k1` the seed **is** the private scalar, so it has to be a valid
one. A seed of zero, or one at or above the curve order, is refused rather
than reduced mod *n* — reducing produces a perfectly functional key belonging
to a different identity, and nothing downstream ever reports a problem.

The phrase is validated, wordlist **and** checksum. A mistyped phrase that is
not checked does not fail; it derives a valid key for an identity nobody owns,
and the only symptom is the ledger not recognising it.

`KeyPair.fromLegacyPhrase` recovers a phrase made by the older
`@activeledger/sdk-bip39` package — recovery only, never for new keys.

### The derivation

| Type | Seed from the BIP-39 seed `S` |
| --- | --- |
| `secp256k1` | `HMAC-SHA512("Bitcoin seed", S)[0..32]` |
| `ml-dsa-65` | `HKDF-SHA512(S, salt="", info="activeledger-seed-v1:ml-dsa-65", 32)` |
| `falcon-512` | `HKDF-SHA512(S, salt="", info="activeledger-seed-v1:falcon-512", 48)` |

`secp256k1` deliberately does not use HKDF: the JavaScript SDK shipped that
derivation before the post-quantum types existed, so phrases are already in
use, and changing it would hand those users a different key for a phrase that
used to work.

One phrase can back all three identity types at once, since each derives its
own seed.

BouncyCastle's PBKDF2 and HKDF are used rather than `javax.crypto`'s:
BouncyCastle is already a dependency, `PBKDF2WithHmacSHA512` is not guaranteed
present on every JRE, and the platform has no HKDF at all.

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
