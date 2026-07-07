# Kannotation

A Kotlin Multiplatform playground project, plus **TypeString** — a small KSP-based
codegen library it hosts for generating obfuscation-safe, analytics-friendly type
names for sealed hierarchies.

## TypeString

### What it's for

Business logic across the app lives in domain usecases, each returning its own
sealed class result type. For analytics logging, we need a stable, human-readable
string identifying *which* subtype of the result was returned (e.g. `"Success"`,
`"NetworkError"`).

The obvious options don't work well:

- **`toString()`** on a data class includes constructor properties (error messages,
  payloads, ...), producing unbounded distinct values instead of a fixed small set.
- **`::class.simpleName`** relies on JVM reflection metadata. In Android release
  builds, R8/ProGuard renames classes during minification (`a`, `b`, `c`, ...) unless
  explicit `-keepnames` rules are added per class — easy to miss, and silently wrong
  in production. iOS has no equivalent minification step, so the same code behaves
  differently per platform.

**TypeString** solves this with a KSP `SymbolProcessor` that scans for
`@GenerateTypeString`-annotated sealed classes/interfaces and generates a
`val <SealedBase>.typeString: String` extension property backed by a `when` block
built from **compile-time string literals**. Because the strings are baked into
generated source before R8 ever runs, they're immune to renaming regardless of
`-keepnames` configuration, and behave identically on Android and iOS.

It's distributed as two modules:

- [`typestring-annotations`](./typestring-annotations) — multiplatform, zero-dependency
  module containing the single `@GenerateTypeString` annotation (`SOURCE` retention —
  no runtime footprint, no reflection).
- [`typestring-processor`](./typestring-processor) — JVM-only KSP `SymbolProcessor`
  that does the actual codegen.

Nested sealed hierarchies are supported: the processor recurses to true leaves and
names them with a dotted path built from the chain of sealed ancestor names (e.g.
`"Failure.Network"`), so identically-named leaves in different branches (e.g.
`Failure.Unknown` vs `Success.Unknown`) stay distinguishable. See
[docs/initial_prds.md](./docs/initial_prds.md) and [docs/plan_v1_1.md](./docs/plan_v1_1.md)
for the full design rationale, including failure modes (non-sealed hierarchies, a
non-sealed `abstract` class breaking the chain, a max-nesting-depth guard, etc).

### Setup

TypeString publishes two artifacts:

- `io.kshitij.typestring:typestring-annotations` — Kotlin Multiplatform (targets:
  `androidTarget`, `iosArm64`, `iosSimulatorArm64`, `iosX64`), the `@GenerateTypeString`
  annotation consumers apply to their sealed classes.
- `io.kshitij.typestring:typestring-processor` — JVM-only KSP `SymbolProcessor`
  that does the codegen at compile time.

They're distributed as a plain Maven repo hosted on this repo's `mvn-repo` git
branch (see [Releasing](#releasing) below), served via raw.githubusercontent.com —
there's no JitPack/Maven Central account involved, so add it as a regular Maven
repository:

1. Add the repository and apply the KSP Gradle plugin alongside Kotlin Multiplatform:

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
       alias(libs.plugins.androidMultiplatformLibrary)
       alias(libs.plugins.ksp)
   }
   ```

2. Add `typestring-annotations` as a normal `commonMain` dependency, and
   `typestring-processor` as a KSP processor. Because the processor is run once
   against the `commonMain` metadata compilation (not once per target) so the
   generated `typeString` property is usable from common code, the generated
   sources directory needs to be added to `commonMain`'s source set, and every
   `ksp*`/`compile*` task needs to depend on the metadata KSP task:

   ```kotlin
   kotlin {
       sourceSets {
           commonMain {
               dependencies {
                   implementation("io.kshitij.typestring:typestring-annotations:0.1.0")
               }
               kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
           }
       }
   }

   dependencies {
       add("kspCommonMainMetadata", "io.kshitij.typestring:typestring-processor:0.1.0")
   }

   tasks.matching { it.name != "kspCommonMainKotlinMetadata" && it.name.startsWith("ksp") }
       .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
   tasks.matching { it.name.startsWith("compile") }
       .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
   ```

   See [`sharedLogic/build.gradle.kts`](./sharedLogic/build.gradle.kts) for a
   working example of this wiring (using `projects.typestringAnnotations` /
   `projects.typestringProcessor` in-repo module references instead of the
   published coordinates above, since this repo consumes its own modules directly).

### Usage

Annotate a sealed class or sealed interface with `@GenerateTypeString`:

```kotlin
import io.kshitij.typestring.GenerateTypeString

@GenerateTypeString
sealed class SampleResult {
    data class Success(val data: String) : SampleResult()
    data class NetworkError(val code: Int) : SampleResult()
    object Loading : SampleResult()
}
```

The processor generates `SampleResultTypeString.kt`:

```kotlin
val SampleResult.typeString: String
    get() = when (this) {
        is SampleResult.Success -> "Success"
        is SampleResult.NetworkError -> "NetworkError"
        is SampleResult.Loading -> "Loading"
    }
```

Call sites use it like any extension property, e.g. `result.typeString == "Success"`.

Nested sealed hierarchies get dotted paths:

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
```

generates:

```kotlin
val SampleNestedResult.typeString: String
    get() = when (this) {
        is SampleNestedResult.Loading -> "Loading"
        is SampleNestedResult.Failure.Network -> "Failure.Network"
        is SampleNestedResult.Failure.Unknown -> "Failure.Unknown"
        is SampleNestedResult.Success.WithData -> "Success.WithData"
        is SampleNestedResult.Success.Unknown -> "Success.Unknown"
    }
```

Note `Failure.Unknown` and `Success.Unknown` stay distinguishable, unlike a plain
`::class.simpleName`. More runnable examples live in
[`sharedLogic/src/commonMain/kotlin/io/kshitij/project/SampleResult.kt`](./sharedLogic/src/commonMain/kotlin/io/kshitij/project/SampleResult.kt).

### Testing

```
./gradlew :typestring-processor:build
```

Runs the processor's [kotlin-compile-testing](https://github.com/ZacSweers/kotlin-compile-testing)
(kctfork) test suite, which compiles small fixture sources through the real KSP
processor and asserts on the generated output and error/warning messages.

### Releasing

Publishing must be run from **macOS** — the `iosArm64`/`iosSimulatorArm64`/`iosX64`
klibs require a Kotlin/Native toolchain, which only builds on macOS.

1. Bump `typestring.version` in [`gradle.properties`](./gradle.properties).
2. Commit that change (the publish script refuses to run with a dirty working tree).
3. Run:

   ```
   ./scripts/publish-library.sh
   ```

   This builds `typestring-annotations` (android + iosArm64 + iosSimulatorArm64 + iosX64) and
   `typestring-processor` (jvm) via `maven-publish`, checks out the `mvn-repo` branch
   into a scratch git worktree, publishes straight into it so `maven-metadata.xml`
   merges with (rather than overwrites) prior versions, then commits and pushes that
   branch to `origin`. It's the same mechanism JitPack-style git-hosted Maven repos
   use, just run manually instead of on JitPack's (Linux-only, so it can't build iOS
   klibs) infrastructure.
4. Consumers pick up the new version by bumping the version in their dependency
   coordinates — there's no further indexing step, raw.githubusercontent.com serves
   the branch contents directly.

The first time this runs, the GitHub repo must already exist and `origin` must be
pushable (`git remote -v` to check) — the script creates the `mvn-repo` branch
itself if it doesn't exist yet, but it can't create the repo itself.

---

## Kotlin Multiplatform project layout

This is a Kotlin Multiplatform project targeting Android, iOS.

* [/iosApp](./iosApp/iosApp) contains an iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/sharedLogic](./sharedLogic/src) is for the code that will be shared between app targets in the project.
  The most important subfolder is [commonMain](./sharedLogic/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

* [/sharedUI](./sharedUI/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./sharedUI/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./sharedUI/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./sharedUI/src/jvmMain/kotlin)
    folder is the appropriate location.

### Running the apps

Use the run configurations provided by the run widget in your IDE's toolbar. You can also use these commands and options:

- Android app: `./gradlew :androidApp:assembleDebug`
- iOS app: open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Running tests

Use the run button in your IDE's editor gutter, or run tests using Gradle tasks:

- Android tests: `./gradlew :sharedUI:testAndroidHostTest :sharedLogic:testAndroidHostTest`
- iOS tests: `./gradlew :sharedLogic:iosSimulatorArm64Test`

---

## License

TypeString (`typestring-annotations`, `typestring-processor`) is licensed under the
[MIT License](./LICENSE).

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
