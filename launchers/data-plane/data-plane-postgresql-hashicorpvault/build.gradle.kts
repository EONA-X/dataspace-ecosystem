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

    // Schema migrations. flyway-core is the engine; it applies whatever sets are on the classpath,
    // and nothing without them. migration-shared owns edc_lease, which belongs to no single store --
    // this runtime carries telemetry-store-sql, one of the nine schemas that declare it.
    // The 0.14.0 baseline (V20260801.2) owns table creation now that edc.sql.schema.autocreate is
    // false -- SqlSchemaBootstrapper no longer touches this database.
    runtimeOnly(project(":extensions:common:migration:flyway-core"))
    runtimeOnly(project(":extensions:common:migration:migration-shared"))
    runtimeOnly(project(":extensions:data-plane:data-plane-migration"))
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