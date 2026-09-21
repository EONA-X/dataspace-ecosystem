plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":core:telemetry-service-core"))
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:telemetry-service:telemetry-service-credential-api"))
    runtimeOnly(project(":extensions:common:policies"))

    // Single fixed-identity runtime: same HashicorpVault privateEntries() gap as control-plane/
    // data-plane, see extensions/common/participant-context-single-vault-seed's javadoc.
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))

    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.bundles.identity)
    runtimeOnly(libs.edc.identitytrust.core)
    runtimeOnly(libs.edc.identitytrust.issuersconfiguration)
    runtimeOnly(libs.edc.sql.jti.store)
    runtimeOnly(libs.edc.oauth2.oauth2client)
    runtimeOnly(libs.edc.dsp08.apiconfiguration)
    runtimeOnly(libs.edc.dsp)
}

edcBuild {
    publish.set(false)
}