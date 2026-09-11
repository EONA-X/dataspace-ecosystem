plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":spi:migration-spi"))
    implementation(libs.edc.spi.core)
    implementation(libs.edc.spi.transaction.datasource)
    implementation(libs.edc.sql.bootstrapper)
    implementation(libs.edc.lib.sql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)

    testImplementation(libs.edc.core.junit)
    testImplementation(libs.assertj)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.postgres)
    testImplementation(libs.flyway.database.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgres)

    // No dependency on the migration modules: the tests discover their scripts from the source tree
    // and address them as Flyway filesystem: locations, so a module added later is covered without
    // anything being wired in here.

    // Present for their DDL resources only, so UpstreamSchemaFixtureTest can compare the schema this
    // EDC version creates against the fixture the migration tests start from.
    testImplementation(libs.edc.cp.transfer.process.store)
    testImplementation(libs.edc.sql.contract.negotiation)
    testImplementation(libs.edc.cp.asset.index.store)
    testImplementation(libs.edc.cp.policy.definition.store)
    testImplementation(libs.edc.cp.contract.definition.store)
    testImplementation(libs.edc.cp.policy.monitor.store)
    testImplementation(libs.edc.ext.sql.edrindex)
    testImplementation(libs.edc.dp.store)
    testImplementation(libs.edc.dp.instance.store)
    testImplementation(libs.edc.dp.accesstokendata.store)
    testImplementation(project(":extensions:common:store:sql:telemetry-store-sql"))
    testImplementation(libs.edc.ih.credentials.store)
    testImplementation(libs.edc.ih.did.store)
    testImplementation(libs.edc.ih.keypair.store)
    testImplementation(libs.edc.ih.participantcontext.store)
    testImplementation(libs.edc.ih.holder.credential.request.store)
    testImplementation(libs.edc.ih.holder.credential.offer.store)
    testImplementation(libs.edc.ih.sts.client.store)
    testImplementation(libs.edc.issuerservice.holder.store.sql)
    testImplementation(libs.edc.issuersvc.attestation.definition.store)
    testImplementation(libs.edc.issuersvc.credential.definition.store)
    testImplementation(libs.edc.issuersvc.issuance.process.store)
    testImplementation(libs.edc.sql.jti.store)
}

// The fixture that must match the DDL on the classpath is the one for the EDC version being built.
val edcVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs").findVersion("edc").orElseThrow().requiredVersion

tasks.withType<Test> {
    systemProperty("edcVersion", edcVersion)
}
