plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.edc.connector.participant.context.spi)
    implementation(libs.edc.participant.context.single.spi)
    implementation(libs.edc.participant.context.config.spi)

    testImplementation(libs.edc.core.junit)
    testImplementation(libs.edc.lib.json)
}
