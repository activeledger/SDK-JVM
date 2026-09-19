# Releasing

**Publishing is not done when the artifact is uploaded. It is done when a
fresh project can resolve it.**

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

Set the version in `build.gradle.kts`, then build and stage every asset:

```bash
V=$(grep -oP '^version = "\K[^"]+' build.gradle.kts)

./gradlew jar sourcesJar \
  generateMetadataFileForIvyPublication \
  generateDescriptorFileForIvyPublication \
  generatePomFileForMavenPublication

mkdir -p /tmp/release && cd /tmp/release
cp "$OLDPWD/build/libs/activeledger-sdk-$V.jar"          .
cp "$OLDPWD/build/libs/activeledger-sdk-$V-sources.jar"  .
cp "$OLDPWD/build/publications/ivy/module.json"          "activeledger-sdk-$V.module"
cp "$OLDPWD/build/publications/ivy/ivy.xml"              "ivy-$V.xml"
cp "$OLDPWD/build/publications/maven/pom-default.xml"    "activeledger-sdk-$V.pom"
```

The renames are not cosmetic. `module.json` and `pom-default.xml` are
Gradle's internal filenames; a consumer's `patternLayout` looks for the
coordinate-shaped names above, and failure 3 was exactly this.

All five assets are required:

| Asset | Why it must be attached |
| --- | --- |
| `activeledger-sdk-$V.jar` | The library. |
| `activeledger-sdk-$V.module` | Gradle Module Metadata. **The only file that carries the dependency graph on the ivy path.** Without it the ivy stub is unparseable — failure 4. |
| `ivy-$V.xml` | What `ivyDescriptor()` fetches. A stub that defers to the `.module`; useless alone, required alongside it. |
| `activeledger-sdk-$V.pom` | For Maven-layout mirrors only. Unreachable from a GitHub release — failure 2. |
| `activeledger-sdk-$V-sources.jar` | Sources, for consumers' IDEs. |

Then tag, create the release, and upload all five:

```bash
git tag "v$V" && git push origin "v$V"
gh release create "v$V" --title "v$V" --notes "..." /tmp/release/*
```

## Verifying

`.github/workflows/verify-release.yml` runs automatically on publish. It does
two things, and the second is the one that matters:

- **Downloads** every expected asset from the URL a consumer would use. The
  API listing is not sufficient on its own: an asset can report
  `state=uploaded` with a valid `browser_download_url` and still 404 for
  roughly fifteen seconds after upload.
- **Resolves** `io.github.activeledger:activeledger-sdk:$V` from a scratch
  project in `.github/consumer-check`, and asserts every declared runtime
  dependency actually arrives. That project is deliberately not a copy of
  this build — it is a consumer, and only a consumer can see these faults.

To check a release by hand, or before cutting one:

```bash
cd .github/consumer-check
gradle --no-daemon -PsdkVersion="$V" verifyResolution
```

A pass prints the resolved graph. Treat a release as unpublished until it
does.

## Adding a runtime dependency

`.github/consumer-check/build.gradle.kts` lists every dependency that must
reach a consumer. Add yours there in the same commit. A dependency missing
from published metadata is a `NoClassDefFoundError` in someone else's
application, never a build failure here — which is precisely why it needs
asserting rather than assuming.
