#!/usr/bin/env bash
# Publishes the library to a throwaway local Maven repo, then compiles and
# runs a Kotlin and a Java consumer against that published artifact only.
# No network: Gradle runs --offline, the node is an in-process MockWebServer.
#
#   src/test/smoke/smoke.sh
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

version="$(sed -n 's/^version = "\(.*\)"/\1/p' "$repo/build.gradle.kts")"

# Unsigned: a throwaway local repo has no use for a signature, and a machine
# without the release key (CI) cannot make one.
(cd "$repo" && ./gradlew publishToMavenLocal --offline -q -PRELEASE_SIGNING_ENABLED=false \
  -Dmaven.repo.local="$work/m2")

mkdir -p "$work/consumer/src/main/kotlin" "$work/consumer/src/main/java"
cp "$here/consumer/Smoke.kt" "$work/consumer/src/main/kotlin/"
cp "$here/consumer/SmokeJava.java" "$work/consumer/src/main/java/"

cat > "$work/consumer/settings.gradle.kts" <<KTS
rootProject.name = "smoke-consumer"
KTS

cat > "$work/consumer/build.gradle.kts" <<KTS
plugins { kotlin("jvm") version "2.0.21" }
repositories {
    // The published artifact first and only for the SDK; everything it
    // depends on comes from the offline Gradle cache.
    maven { url = uri("$work/m2"); content { includeGroup("io.github.activeledger") } }
    mavenCentral()
}
dependencies {
    implementation("io.github.activeledger:activeledger:$version")
    implementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
kotlin { jvmToolchain(17) }
tasks.register<JavaExec>("runKotlin") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("SmokeKt")
}
tasks.register<JavaExec>("runJava") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("SmokeJava")
}
KTS

"$repo/gradlew" -p "$work/consumer" --offline -q runKotlin runJava
