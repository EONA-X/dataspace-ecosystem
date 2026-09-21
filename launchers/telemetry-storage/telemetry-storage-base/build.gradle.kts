plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:telemetry-storage:telemetry-storage-api"))
    runtimeOnly(project(":extensions:telemetry-storage:telemetry-storage-kafka-consumer"))

    // Single fixed-identity runtime: same HashicorpVault privateEntries() gap as control-plane/
    // data-plane, see extensions/common/participant-context-single-vault-seed's javadoc.
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))

    runtimeOnly(libs.bundles.connector)
}

edcBuild {
    publish.set(false)
}