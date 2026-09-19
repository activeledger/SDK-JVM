// A real consumer, resolving the published coordinate exactly as a user
// would. This is deliberately NOT a copy of the SDK's own build: the point
// is to exercise the published metadata, which the SDK's build never touches.
plugins { `java-library` }

val sdkVersion: String by project

repositories {
    ivy {
        url = uri("https://github.com/activeledger/SDK-JVM/releases/download")
        patternLayout {
            artifact("v[revision]/[artifact]-[revision](-[classifier])(.[ext])")
            ivy("v[revision]/ivy-[revision].xml")
        }
        // gradleMetadata() FIRST and non-negotiable. ivy-publish emits a
        // stub ivy.xml with an empty <configurations/> that defers to Gradle
        // Module Metadata; on its own it fails to parse outright, because
        // its dependencies reference configurations it never declares.
        metadataSources { gradleMetadata(); ivyDescriptor(); artifact() }
        content { includeGroup("io.github.activeledger") }
    }
    mavenCentral()
}

dependencies {
    implementation("io.github.activeledger:activeledger-sdk:$sdkVersion")
}

tasks.register("verifyResolution") {
    doLast {
        val resolved = configurations.runtimeClasspath.get()
            .resolvedConfiguration.resolvedArtifacts
            .map { "${it.moduleVersion.id.group}:${it.moduleVersion.id.name}" }
            .toSet()

        // Every dependency the SDK declares at runtime must arrive. Missing
        // any of these is a NoClassDefFoundError in someone's application,
        // not a build failure here - which is exactly why it needs asserting.
        val required = listOf(
            "io.github.activeledger:activeledger-sdk",
            "org.bouncycastle:bcprov-jdk18on",
            "com.squareup.okhttp3:okhttp",
            "com.google.code.gson:gson",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
        )

        val missing = required.filterNot { it in resolved }
        resolved.sorted().forEach { println("  resolved: $it") }

        if (missing.isNotEmpty()) {
            throw GradleException("published metadata did not bring: ${missing.joinToString()}")
        }
    }
}
