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

    // Schema migrations. The migration sets themselves are added on the EDC 0.15.0 branch: they
    // migrate the schema to the 0.15 shape, which a 0.14 runtime cannot use. Without them this
    // extension registers nothing and is a no-op.
    //
    // This runtime takes three sets, not two: :extensions:common:migration:migration-shared,
    // :extensions:issuer-service:issuer-service-migration, and also
    // :extensions:identity-hub:identity-hub-migration -- five store modules sit in both this
    // runtime's SQL BOM and the identity-hub's, so either runtime may be the first to boot against
    // the tables they share.
    runtimeOnly(project(":extensions:common:migration:flyway-core"))
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