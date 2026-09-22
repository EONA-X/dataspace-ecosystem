plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.edc.issuerservice.spi.holder)
    implementation(libs.edc.spi.transaction)
    api(libs.edc.federatedcatalog.spi.core)
    api(libs.edc.spi.identity.did)
    api(libs.edc.spi.dsp.http)
    api(libs.edc.spi.dsp2025)
}


