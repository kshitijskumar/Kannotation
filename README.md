# TypeString

A small Kotlin Multiplatform **KSP codegen** library that generates obfuscation-safe,
analytics-friendly type-name strings for sealed class/interface hierarchies.

Annotate a sealed hierarchy with `@GenerateTypeString`, and get a generated
`.typeString: String` extension property you can log to analytics — without relying on
`toString()` (unbounded, includes payload data) or `::class.simpleName` (broken by
R8/ProGuard renaming on Android release builds, and inconsistent with iOS which has no
equivalent minification step).

The generated strings are compile-time literals baked in before R8 ever runs, so
they're immune to renaming regardless of `-keepnames` config, and behave identically
on Android and iOS.

## Modules

| Artifact | Platform | Purpose |
|---|---|---|
| `io.kshitij.typestring:typestring-annotations` | KMP (`androidTarget`, `iosArm64`, `iosSimulatorArm64`, `iosX64`) | The `@GenerateTypeString` annotation (`SOURCE` retention — no runtime footprint) |
| `io.kshitij.typestring:typestring-processor` | JVM | The KSP `SymbolProcessor` that does the codegen |

Published as a plain Maven repo on this repo's `mvn-repo` branch, served via
`raw.githubusercontent.com` (no JitPack/Maven Central account involved).

## Setup

1. Add the repository and the KSP plugin:

   ```kotlin
   // settings.gradle.kts
   dependencyResolutionManagement {
       repositories {
           maven("https://raw.githubusercontent.com/kshitijskumar/Kannotation/mvn-repo/")
       }
   }
   ```

   ```kotlin
   // <your-module>/build.gradle.kts
   plugins {
       alias(libs.plugins.kotlinMultiplatform)
       alias(libs.plugins.ksp)
   }
   ```

2. Add the dependencies. Because the processor runs against the `commonMain`
   metadata compilation (so the generated property is usable from common code), the
   generated sources dir must be added to `commonMain`, and `ksp*`/`compile*` tasks
   must depend on the metadata KSP task:

   ```kotlin
   kotlin {
       sourceSets {
           commonMain {
               dependencies {
                   implementation("io.kshitij.typestring:typestring-annotations:0.3.0")
               }
               kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
           }
       }
   }

   dependencies {
       add("kspCommonMainMetadata", "io.kshitij.typestring:typestring-processor:0.3.0")
   }

   tasks.matching { it.name != "kspCommonMainKotlinMetadata" && it.name.startsWith("ksp") }
       .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
   tasks.matching { it.name.startsWith("compile") }
       .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
   ```

   See [`sharedLogic/build.gradle.kts`](./sharedLogic/build.gradle.kts) for a working
   example (using in-repo project references instead of published coordinates).

## Usage

Annotate a sealed class or interface:

```kotlin
import io.kshitij.typestring.GenerateTypeString

@GenerateTypeString
sealed class SampleResult {
    data class Success(val data: String) : SampleResult()
    data class NetworkError(val code: Int) : SampleResult()
    object Loading : SampleResult()
}
```

The processor generates a `typeString` extension property:

```kotlin
val SampleResult.typeString: String
    get() = when (this) {
        is SampleResult.Success -> "Success"
        is SampleResult.NetworkError -> "NetworkError"
        is SampleResult.Loading -> "Loading"
    }
```

Use it like any extension property: `result.typeString == "Success"`.

Nested sealed hierarchies are supported — leaves get a dotted path built from their
sealed ancestor chain, so identically-named leaves in different branches
(`Failure.Unknown` vs `Success.Unknown`) stay distinguishable:

```kotlin
@GenerateTypeString
sealed class SampleNestedResult {
    object Loading : SampleNestedResult()
    sealed class Failure : SampleNestedResult() {
        object Network : Failure()
        object Unknown : Failure()
    }
    sealed class Success : SampleNestedResult() {
        data class WithData(val payload: String) : Success()
        object Unknown : Success()
    }
}
// -> "Loading", "Failure.Network", "Failure.Unknown", "Success.WithData", "Success.Unknown"
```

More runnable examples live in
[`sharedLogic/src/commonMain/kotlin/io/kshitij/project/SampleResult.kt`](./sharedLogic/src/commonMain/kotlin/io/kshitij/project/SampleResult.kt).

For the full design rationale (failure modes for non-sealed hierarchies, max-nesting
guard, etc.), see [docs/initial_prds.md](./docs/initial_prds.md) and
[docs/plan_v1_1.md](./docs/plan_v1_1.md).

## Testing

```
./gradlew :typestring-processor:build
```

Runs the processor's [kotlin-compile-testing](https://github.com/ZacSweers/kotlin-compile-testing)
(kctfork) suite, which compiles fixture sources through the real KSP processor and
asserts on generated output and error/warning messages.

## Releasing

Must be run from **macOS** (the iOS klibs need a Kotlin/Native toolchain):

1. Bump `typestring.version` in [`gradle.properties`](./gradle.properties) and commit
   (the publish script refuses to run with a dirty working tree).
2. Run `./scripts/publish-library.sh` — builds both artifacts, publishes into a
   scratch worktree of the `mvn-repo` branch so `maven-metadata.xml` merges with
   prior versions, then pushes.
3. Consumers pick up the new version by bumping their dependency coordinates — no
   further indexing step needed.

The `mvn-repo` branch must already exist and `origin` must be pushable; the script
creates the branch if missing but not the GitHub repo itself.

---

This repo also hosts the Kotlin Multiplatform sample app (`androidApp`, `iosApp`,
`sharedLogic`, `sharedUI`) used to exercise the library locally — not part of the
published artifacts.

## License

MIT — see [LICENSE](./LICENSE).
