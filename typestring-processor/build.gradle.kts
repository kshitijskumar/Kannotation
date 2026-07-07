plugins {
    alias(libs.plugins.kotlinJvm)
    `maven-publish`
}

group = providers.gradleProperty("typestring.group").get()
version = providers.gradleProperty("typestring.version").get()

dependencies {
    implementation(libs.ksp.symbolProcessingApi)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

apply(from = rootProject.file("gradle/publishing.gradle.kts"))
