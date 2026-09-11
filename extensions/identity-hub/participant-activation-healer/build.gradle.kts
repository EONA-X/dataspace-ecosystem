plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.edc.identityhub.spi.core)
    implementation(libs.edc.identityhub.spi.participantcontext)
    testImplementation(libs.edc.core.junit)
}
