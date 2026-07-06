plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    implementation(libs.ksp.symbolProcessingApi)
    implementation(libs.kotlinpoet)
    implementation(libs.kotlinpoet.ksp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kctfork.core)
    testImplementation(libs.kctfork.ksp)
}
