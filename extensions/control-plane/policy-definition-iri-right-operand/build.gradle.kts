plugins {
    `java-library`
}

dependencies {
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.jsonld)
    implementation(libs.edc.spi.web)
    implementation(libs.jakarta.rsApi)
    implementation(libs.jakarta.annotationApi)
    implementation(libs.jakartaJson)
    // Only to name the controllers the interceptor is bound to; the runtime already ships it.
    compileOnly(libs.edc.api.policy.definition)

    testImplementation(libs.edc.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.edc.api.policy.definition)
    testImplementation(libs.edc.lib.validator)
    testImplementation(libs.edc.lib.transform)
    testImplementation(libs.edc.spi.control.plane.transform)
    testImplementation(libs.jersey.server)
    testImplementation(libs.jersey.hk2)
    testImplementation(libs.jersey.jsonp)
}
