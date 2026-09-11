plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

var distTar = tasks.getByName("distTar")
var distZip = tasks.getByName("distZip")

dependencies {
    runtimeOnly(project(":launchers:data-plane:data-plane-base"))

    runtimeOnly(project(":extensions:common:store:sql:telemetry-store-sql"))
    runtimeOnly(libs.edc.ext.sql.edrindex)
    runtimeOnly(libs.edc.dataplane.sql.bom)
    runtimeOnly(libs.edc.ext.vault.hashicorp)

    // Schema migrations. The migration sets themselves (:extensions:common:migration:migration-shared
    // and :extensions:data-plane:data-plane-migration) are added on the EDC 0.15.0 branch: they
    // migrate the schema to the 0.15 shape, which a 0.14 runtime cannot use. Without them this
    // extension registers nothing and is a no-op.
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