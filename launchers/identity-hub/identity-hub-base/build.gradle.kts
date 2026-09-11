plugins {
    `java-library`
}

dependencies {
    runtimeOnly(project(":extensions:common:vc-revocation-patch"))
    runtimeOnly(project(":extensions:identity-hub:did-web-parser"))
    runtimeOnly(project(":extensions:identity-hub:identity-hub-iatp"))
    runtimeOnly(project(":extensions:identity-hub:superuser-seed"))
    runtimeOnly(project(":extensions:identity-hub:participant-activation-healer"))
    runtimeOnly(project(":extensions:common:metrics:custom-micrometer"))

    // Writable ParticipantContextConfigStore (in-memory, re-seeded on every boot by superuser-seed's
    // manifest vault-config property). identity-hub actually creates its participant via
    // ParticipantContextService.createParticipantContext(), which unconditionally calls
    // configService.save() — participant-context-single-vault-seed's store is read-only and throws
    // UnsupportedOperationException there. That extension is only valid for runtimes that never create
    // a participant context (control-plane/data-plane/issuer-service, pure Supplier pattern).
    runtimeOnly(libs.edc.participant.context.config.core)

    runtimeOnly(libs.bundles.connector)
    runtimeOnly(libs.edc.identityhub.local.statuslist.publisher)
    runtimeOnly(libs.edc.identityhub.bom) {
        exclude(group = "org.eclipse.edc", module = "identityhub-api-authentication")
        exclude(group = "org.eclipse.edc", module = "identityhub-api-authorization")
    }
}

edcBuild {
    publish.set(false)
}