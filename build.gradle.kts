plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
    // Android compatibility is enforced at compile time, not claimed.
    // There is no Android SDK in this build and no device in CI, so the
    // only honest guarantee is that the bytecode cannot reference an API
    // that the minSdk floor lacks.
    id("ru.vyarus.animalsniffer") version "2.0.0"
}

group = "io.github.activeledger"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Pure Java, no JNI, no per-ABI .so - covers ml-dsa-65 and falcon-512 on
    // plain JVM and on Android from one dependency. This is why the three
    // legacy SDKs (Kotlin/Java/Android) collapse into one artefact: Spongy
    // Castle existed only because old Android lacked BouncyCastle.
    //
    // PINNED, not "latest". 1.86 exists. Everything this SDK relies on -
    // Falcon's header byte and length trimming, ML-DSA's TYPE_PURE default -
    // was verified against 1.85.2's source, and BouncyCastle has already
    // relocated ML-DSA between packages once. Upgrading is a task with
    // byte-level re-verification, not a version bump.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Test-only. Reading the published vector file is exactly what a JSON
    // library is for; the rule is that nothing writes the SIGNED bytes with
    // one, since those must match JSON.stringify exactly.
    testImplementation("com.google.code.gson:gson:2.11.0")

    // Android API 26 signature. minSdk 26 because the SDK handles ISO-8601
    // instants and java.time is API 26+; the alternative is core library
    // desugaring, a permanent build-config tax on every consumer of a
    // crypto library, to reach a fraction of a percent of devices.
    signature("com.toasttab.android:gummy-bears-api-26:0.15.0@signature")
}

animalsniffer {
    // Only the library itself. Tests may use anything the JVM offers.
    // Failures are fatal by default in this plugin, which is what we want.
    sourceSets = listOf(project.sourceSets.main.get())
}

// Verified to actually bite, rather than assumed: a ProcessHandle call in
// main/ fails the build with
//   [Undefined reference] >> ProcessHandle ProcessHandle.current()
// naming the symbol and line.
//
// Worth knowing what it does NOT reject. The gummy-bears API-26 signature
// includes APIs reachable through core library desugaring, so java.util.
// List.of() passes even though Android only gained it at API 30. That is
// correct for an app that enables desugaring - and an unstated assumption
// for a LIBRARY, whose consumer may not. So this check catches the fatal
// class (APIs with no backport at all) and not the desugarable one; prefer
// avoiding Java 9+ collection factories in main/ regardless of it passing.

// Deliberately NOT added: bcpkix, bctls, or any Security.insertProviderAt
// call. The SDK drives BouncyCastle's low-level org.bouncycastle.crypto.*
// engine classes directly, which sidesteps Android's own stub "BC" provider
// entirely - no provider registration, no name collision with the platform's
// cut-down copy.

kotlin {
    jvmToolchain(17)
    compilerOptions {
        // Target 11, not 17: the bytecode has to load on Android, and the
        // toolchain only describes what compiles it. Kotlin and Java must
        // agree or Gradle refuses the build outright.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "activeledger-sdk"
            pom {
                name.set("Activeledger SDK")
                description.set("Kotlin/Java SDK for Activeledger, with post-quantum identity support")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
