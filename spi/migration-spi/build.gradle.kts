plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    // Only for the @ExtensionPoint annotation, which reaches this module transitively through
    // runtime-metamodel. The types below carry no EDC dependency of their own.
    api(libs.edc.spi.core)

    testImplementation(libs.assertj)
    testImplementation(libs.junit.jupiter.api)
}