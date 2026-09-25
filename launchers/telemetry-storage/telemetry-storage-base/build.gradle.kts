plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:telemetry-storage:telemetry-storage-api"))
    runtimeOnly(project(":extensions:telemetry-storage:telemetry-storage-kafka-consumer"))

    // Single fixed-identity runtime: same HashicorpVault privateEntries() gap as control-plane/
    // data-plane, see extensions/common/participant-context-single-vault-seed's javadoc. No bom
    // here to pull participant-context-config-core transitively (control-plane/issuer-service get
    // it that way), so add it explicitly, same as data-plane-base.
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))
    runtimeOnly(libs.edc.participant.context.config.core)

    // kafka-clients logs via SLF4J, not the EDC Monitor -- without a binding its logs
    // (poll/fetch/rebalance/auth errors) silently go nowhere, making Kafka-level issues
    // undiagnosable. jdk14 binding to route through the same java.util.logging config
    // already used by the rest of the JVM (see launcher Dockerfile).
    runtimeOnly(libs.slf4j.jdk14)

    runtimeOnly(libs.bundles.connector)
}

edcBuild {
    publish.set(false)
}