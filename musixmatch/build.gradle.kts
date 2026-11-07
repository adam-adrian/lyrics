plugins {
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)

    // Resolve the sibling module in both standalone (:musixmatch) and app (:lyrics:musixmatch) builds.
    testImplementation(project(project.path.substringBeforeLast(':') + ":betterlyrics"))
    testImplementation(libs.junit)
    testImplementation(libs.ktor.client.mock)
}
