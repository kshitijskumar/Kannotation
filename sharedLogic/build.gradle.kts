import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.ksp)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SharedLogic"
            isStatic = true
        }
    }
    
    androidLibrary {
       namespace = "io.kshitij.project.sharedLogic"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.typestringAnnotations)
            }
            kotlin.srcDir("build/generated/ksp/metadata/commonMain/kotlin")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", projects.typestringProcessor)
}

// Single-generation strategy (docs/plan_v1.md Future Optimisations point 1): the processor
// runs once against the commonMain metadata compilation instead of once per target, so the
// generated typeString extension is usable from commonMain itself, not just platform source
// sets. Every per-target compile must wait for that single KSP run to land its output in
// commonMain's source dir first, and the Kotlin Gradle plugin doesn't infer this ordering
// automatically since the srcDir is a plain path, not a task output reference.
tasks.matching { it.name != "kspCommonMainKotlinMetadata" && it.name.startsWith("ksp") }
    .configureEach { dependsOn("kspCommonMainKotlinMetadata") }
tasks.matching { it.name.startsWith("compile") }
    .configureEach { dependsOn("kspCommonMainKotlinMetadata") }