plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    implementation(project(":spi:migration-spi"))
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.transaction.datasource)

    testImplementation(libs.edc.core.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.mockito.junit.jupiter)
}