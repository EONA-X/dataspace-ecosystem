plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

var distTar = tasks.getByName("distTar")
var distZip = tasks.getByName("distZip")

dependencies {
    runtimeOnly(project(":launchers:issuer-service:issuer-service-base"))
    runtimeOnly(project(":extensions:issuer-service:membership-attestation-store-sql"))
    runtimeOnly(project(":extensions:issuer-service:domain-attestation-store-sql"))

    runtimeOnly(libs.edc.issuerservice.bom.sql)
    runtimeOnly(libs.edc.ext.vault.hashicorp)

    // Schema migrations. flyway-core is the engine; it applies whatever sets are on the classpath,
    // and nothing without them. migration-shared owns edc_lease, which belongs to no single store.
    // The 0.14.0 baseline (V20260801.4) owns table creation now that edc.sql.schema.autocreate is
    // false -- SqlSchemaBootstrapper no longer touches this database.
    //
    // This runtime takes three sets, not two: the identity-hub set is here as well, because five
    // store modules sit in both this runtime's SQL BOM and the identity-hub's, so either runtime may
    // be the first to boot against the tables they share.
    runtimeOnly(project(":extensions:common:migration:flyway-core"))
    runtimeOnly(project(":extensions:common:migration:migration-shared"))
    runtimeOnly(project(":extensions:issuer-service:issuer-service-migration"))
    runtimeOnly(project(":extensions:identity-hub:identity-hub-migration"))
}

application {
    mainClass.set("org.eclipse.edc.boot.system.runtime.BaseRuntime")
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    mergeServiceFiles()
    archiveFileName.set("${project.name}.jar")
    dependsOn(distTar, distZip)
    mustRunAfter(distTar, distZip)
}

edcBuild {
    publish.set(false)
}