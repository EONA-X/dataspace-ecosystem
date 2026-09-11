plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:common:participant-context-single-vault-seed"))
    runtimeOnly(project(":extensions:issuer-service:membership-attestation-api"))
    runtimeOnly(project(":extensions:issuer-service:domain-attestation-api"))

    runtimeOnly(libs.edc.issuerservice.db.attestations)
    runtimeOnly(libs.edc.issuerservice.bom) {
        exclude(group = "org.eclipse.edc", module = "issuer-admin-api-authentication")
        exclude(group = "org.eclipse.edc", module = "identityhub-api-authorization")
    }
    runtimeOnly(libs.bundles.connector)
}

edcBuild {
    publish.set(false)
}