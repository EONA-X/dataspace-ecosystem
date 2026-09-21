plugins {
    `java-library`
    id("application")
    alias(libs.plugins.shadow)
}

var distTar = tasks.getByName("distTar")
var distZip = tasks.getByName("distZip")

dependencies {
    runtimeOnly(project(":launchers:telemetry-agent:telemetry-agent-base"))
    runtimeOnly(project(":extensions:common:store:sql:telemetry-store-sql"))

    runtimeOnly(libs.bundles.coresql)
    // libs.bundles.coresql only carries sql-lease (statements/model), not the -core module that
    // @Provides SqlLeaseContextBuilderProvider -- control-plane/data-plane get it transitively via
    // their -sql.bom, telemetry-agent has no such bom.
    runtimeOnly(libs.edc.sql.lease.core)
    runtimeOnly(libs.edc.ext.vault.hashicorp)

    // Schema migrations. The telemetry-agent owns no table of its own -- its store is migrated by
    // data-plane-migration, not here -- but it writes edc_lease on every database it serves, so it
    // must carry migration-shared to block on that reshape the same way control-plane/data-plane/
    // identity-hub do, and telemetry-agent-migration to have a signal key on the barrier those three
    // wait for. See extensions/telemetry-agent/telemetry-agent-migration/README.md.
    runtimeOnly(project(":extensions:common:migration:flyway-core"))
    runtimeOnly(project(":extensions:common:migration:migration-shared"))
    runtimeOnly(project(":extensions:telemetry-agent:telemetry-agent-migration"))
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