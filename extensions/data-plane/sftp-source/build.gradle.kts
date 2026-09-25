plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.dpf)
    implementation(libs.edc.spi.core)
    implementation(libs.mina.sshd.core)
    implementation(libs.mina.sshd.sftp)

    testImplementation(libs.edc.core.junit)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit.jupiter)
}
