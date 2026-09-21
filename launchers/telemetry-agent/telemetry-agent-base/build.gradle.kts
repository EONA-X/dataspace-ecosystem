plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":core:telemetry-agent-core"))
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:telemetry-agent:kafka-telemetry-record-publisher"))
    runtimeOnly(project(":extensions:common:policies"))

    // Single fixed-identity runtime: same HashicorpVault privateEntries() gap as control-plane/
    // data-plane, see extensions/common/participant-context-single-vault-seed's javadoc. No bom
    // here to pull participant-context-config-core transitively (control-plane/issuer-service get
    // it that way), so add it explicitly, same as data-plane-base.
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))
    runtimeOnly(libs.edc.participant.context.config.core)

    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.bundles.identity)
    runtimeOnly(libs.edc.identitytrust.core)
    runtimeOnly(libs.edc.oauth2.oauth2client)
}

edcBuild {
    publish.set(false)
}