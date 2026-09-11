plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.identity.did)
    implementation(libs.edc.identityhub.spi.core)
    implementation(libs.edc.identityhub.spi.participantcontext)
    implementation(libs.edc.participant.context.config.spi)
    testImplementation(libs.edc.core.junit)

}
