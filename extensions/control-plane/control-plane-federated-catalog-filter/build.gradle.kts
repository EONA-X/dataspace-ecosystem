plugins {
    `java-library`
    id(libs.plugins.swagger.get().pluginId)
}

dependencies {
    api(libs.edc.spi.asset)
    api(libs.edc.spi.core)
    api(libs.edc.spi.controlplane)
    implementation(libs.edc.lib.token)
    implementation(libs.edc.spi.identity.did)
    implementation(libs.edc.lib.http)
    api(project(":spi:common-spi"))
    api(project(":spi:federated-catalog-filter-spi"))

    testImplementation(libs.edc.core.junit)
    testImplementation(libs.edc.core.jersey)
    testImplementation(libs.mockserver.netty)
    testImplementation(libs.mockserver.client)
}