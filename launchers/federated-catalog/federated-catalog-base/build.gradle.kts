plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:common:policies"))
    runtimeOnly(project(":extensions:common:metrics:custom-micrometer"))
    runtimeOnly(project(":extensions:federated-catalog:participant-registry-node-directory"))

    // Single fixed-identity runtime: same HashicorpVault privateEntries() gap as control-plane/
    // data-plane, see extensions/common/participant-context-single-vault-seed's javadoc.
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))

    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.edc.federatedcatalog.bom) {
        exclude(group = "org.eclipse.edc", module = "participant-context-single-core")
    }
}

edcBuild {
    publish.set(false)
}