# Releasing

**Publishing is not done when the artifact is uploaded. It is done when a
fresh project can resolve it.**

Every version ships on two channels, as the same coordinate,
`io.github.activeledger:activeledger`:

- **Maven Central**, which is what the README tells users to install from.
- **A GitHub release**, consumed through an ivy repository.

Each channel is verified on its own. One resolving says nothing about the
other.

That sentence is the whole document. Everything below is the procedure that
follows from it, and the four failures that produced it.

## Why this file exists

Version 0.2.1 took four attempts to publish. The code was right every time;
the failure was always in the last hop, and always invisible from this side:

1. The Gradle Module Metadata was generated but left in `build/publications`,
   so nothing was attached.
2. The POM was attached, but a GitHub release can only be consumed through an
   ivy repository, and `IvyArtifactRepository.metadataSources` has no
   `mavenPom()`. The dependency declarations were unreachable.
3. The ivy descriptor was attached under a name the consumer's
   `patternLayout` did not match.
4. The ivy descriptor was attached, served a clean 200, carried the right
   organisation and the right dependency list — and still failed to parse,
   because `ivy-publish` emits a stub with an empty `<configurations/>` that
   defers to a `.module` file that was not there.

Every one was green from the publisher's side. The GitHub API listed each
asset, each URL returned 200, each file contained what it should. The defect
lived in the join between artifact and consumer, which neither end can see
alone. Our own Gradle build cannot catch any of them by construction: it
resolves this SDK from `build/libs` and never once reads what we published.

## Cutting a release

The version lives in one place: `version` in `build.gradle.kts`. The Maven
Central coordinates, the ivy publication and the jar names all read it.
Bump it there, then commit.

### 1. Maven Central

```bash
./gradlew publishToMavenCentral
```

This uploads a signed deployment to the Central Portal and stops. Check it
at <https://central.sonatype.com/publishing/deployments>, then press
**Publish**. (`publishAndReleaseToMavenCentral` skips that manual step.)
Signing needs the `signingInMemoryKey*` and `mavenCentralUsername`/
`mavenCentralPassword` Gradle properties, normally set in
`~/.gradle/gradle.properties`, never in this repo.

A version on Central is permanent. It cannot be replaced or deleted, only
superseded by the next version, so check the POM (license, SCM URL,
dependencies) in the Portal before you press Publish.

"Published" in the Portal is not the same as resolvable. Sync to
`repo1.maven.org` takes anywhere from minutes to hours. Verify it the way a
consumer would:

```bash
gradle -p .github/consumer-check --no-daemon -PsdkVersion="$V" -Psource=central verifyResolution
```

### 2. GitHub release

Build and stage every asset:

```bash
V=$(grep -oP '^version = "\K[^"]+' build.gradle.kts)

./gradlew jar sourcesJar \
  generateMetadataFileForIvyPublication \
  generateDescriptorFileForIvyPublication \
  generatePomFileForMavenPublication

mkdir -p /tmp/release && cd /tmp/release
cp "$OLDPWD/build/libs/activeledger-$V.jar"             .
cp "$OLDPWD/build/libs/activeledger-$V-sources.jar"     .
cp "$OLDPWD/build/publications/ivy/module.json"         "activeledger-$V.module"
cp "$OLDPWD/build/publications/ivy/ivy.xml"             "ivy-$V.xml"
cp "$OLDPWD/build/publications/maven/pom-default.xml"   "activeledger-$V.pom"
```

The renames are not cosmetic. `module.json` and `pom-default.xml` are
Gradle's internal filenames; a consumer's `patternLayout` looks for the
coordinate-shaped names above, and failure 3 was exactly this.

All five assets are required:

| Asset | Why it must be attached |
| --- | --- |
| `activeledger-$V.jar` | The library. |
| `activeledger-$V.module` | Gradle Module Metadata. **The only file that carries the dependency graph on the ivy path.** Without it the ivy stub is unparseable — failure 4. |
| `ivy-$V.xml` | What `ivyDescriptor()` fetches. A stub that defers to the `.module`; useless alone, required alongside it. |
| `activeledger-$V.pom` | For Maven-layout mirrors only. Unreachable from a GitHub release — failure 2. |
| `activeledger-$V-sources.jar` | Sources, for consumers' IDEs. |

The ivy publication in `build.gradle.kts` exists only for this channel. The
Maven Central plugin does not need it, which is how it came to be deleted
once already. Without it, none of the ivy tasks above exist.

Then tag, create the release, and upload all five:

```bash
git tag "v$V" && git push origin "v$V"
gh release create "v$V" --title "v$V" --notes "..." /tmp/release/*
```

## Verifying

`.github/workflows/verify-release.yml` runs automatically when a GitHub
release is published. It checks the GitHub channel only, and does two
things. The second is the one that matters:

- **Downloads** every expected asset from the URL a consumer would use. The
  API listing is not sufficient on its own: an asset can report
  `state=uploaded` with a valid `browser_download_url` and still 404 for
  roughly fifteen seconds after upload.
- **Resolves** `io.github.activeledger:activeledger:$V` from a scratch
  project in `.github/consumer-check`, and asserts every declared runtime
  dependency actually arrives. That project is deliberately not a copy of
  this build. It is a consumer, and only a consumer can see these faults.
  In GitHub mode it excludes this group from Maven Central, so a version
  already on Central cannot make a broken release look fine.

Nothing checks Maven Central automatically, because its sync time is
unpredictable. Run the `-Psource=central` check from step 1 by hand.

To check either channel by hand:

```bash
cd .github/consumer-check
gradle --no-daemon -PsdkVersion="$V" verifyResolution                   # GitHub release
gradle --no-daemon -PsdkVersion="$V" -Psource=central verifyResolution  # Maven Central
```

A pass prints the resolved graph. Treat a release as unpublished on a
channel until that channel's check does.

## Before 1.0.0

Versions up to 0.4.0 were published only as GitHub releases, with the
artifact `activeledger-sdk`. From 1.0.0 the artifact is `activeledger` on
both channels. The 1.0.0 POM on Central says Apache 2.0; the SDK is MIT, as
`LICENSE` says, and later versions declare that correctly.

## Adding a runtime dependency

`.github/consumer-check/build.gradle.kts` lists every dependency that must
reach a consumer. Add yours there in the same commit. A dependency missing
from published metadata is a `NoClassDefFoundError` in someone else's
application, never a build failure here — which is precisely why it needs
asserting rather than assuming.
