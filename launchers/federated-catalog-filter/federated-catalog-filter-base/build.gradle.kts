plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:federated-catalog:filter"))
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:common:policies"))
    runtimeOnly(project(":extensions:agreements"))
    runtimeOnly(project(":extensions:common:metrics:custom-micrometer"))
    runtimeOnly(project(":extensions:control-plane:asset-custom-property-subscriber"))

    // Identity Hub dependencies for token validation
    runtimeOnly(project(":extensions:identity-hub:did-web-parser"))
    runtimeOnly(project(":extensions:identity-hub:identity-hub-iatp"))
    api(libs.edc.spi.identity.did)
    runtimeOnly(libs.edc.controlplane.api.secrets)

    // Single fixed-identity runtime: same HashicorpVault privateEntries() gap as control-plane/
    // data-plane, see extensions/common/participant-context-single-vault-seed's javadoc.
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))

    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.edc.controlplane.bom) {
        exclude(group = "org.eclipse.edc", module = "participant-context-single-core")
    }
}

edcBuild {
    publish.set(false)
}