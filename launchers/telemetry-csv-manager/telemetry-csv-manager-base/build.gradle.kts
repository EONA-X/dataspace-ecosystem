plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:telemetry-csv-manager:telemetry-csv-manager-api"))

    // Single fixed-identity runtime: same HashicorpVault privateEntries() gap as control-plane/
    // data-plane, see extensions/common/participant-context-single-vault-seed's javadoc. No bom
    // here to pull participant-context-config-core transitively (control-plane/issuer-service get
    // it that way), so add it explicitly, same as data-plane-base.
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))
    runtimeOnly(libs.edc.participant.context.config.core)

    runtimeOnly(libs.bundles.connector)
}

edcBuild {
    publish.set(false)
}