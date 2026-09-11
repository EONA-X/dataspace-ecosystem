package org.eclipse.dse.identityhub.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.identityhub.spi.participantcontext.ParticipantContextService;
import org.eclipse.edc.identityhub.spi.participantcontext.model.CreateParticipantContextResponse;
import org.eclipse.edc.identityhub.spi.participantcontext.model.KeyDescriptor;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantManifest;
import org.eclipse.edc.participantcontext.spi.config.model.ParticipantContextConfiguration;
import org.eclipse.edc.participantcontext.spi.config.service.ParticipantContextConfigService;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.types.TypeManager;

import java.util.List;
import java.util.Map;

import static java.util.Optional.ofNullable;
import static org.eclipse.edc.identityhub.spi.authentication.ServicePrincipal.ROLE_ADMIN;

public class ParticipantContextSeedExtension implements ServiceExtension {

    public static final String NAME = "Root User Seeding";

    public static final String SUPERUSER_SERVICES_PROPERTY = "edc.ih.api.superuser.services";
    public static final String SUPERUSER_FORCE_RECREATE_PROPERTY = "edc.ih.api.superuser.force.recreate";
    public static final String PUBLIC_KEY_ALIAS_PROPERTY = "edc.iam.sts.publickey.alias";
    public static final String PRIVATE_KEY_ALIAS_PROPERTY = "edc.iam.sts.privatekey.alias";
    public static final String VAULT_CONFIG_PROPERTY = "edc.vault.hashicorp.config";
    public static final String VAULT_URL_PROPERTY = "edc.vault.hashicorp.url";
    public static final String VAULT_TOKEN_PROPERTY = "edc.vault.hashicorp.token";

    @Setting(description = "Service endpoints to be defined for the Super-User", key = SUPERUSER_SERVICES_PROPERTY, required = false, defaultValue = "[]")
    private String services;

    @Setting(description = "STS public key alias", key = PUBLIC_KEY_ALIAS_PROPERTY)
    private String publicKeyAlias;

    @Setting(description = "STS private key alias", key = PRIVATE_KEY_ALIAS_PROPERTY)
    private String privateKeyAlias;

    @Setting(description = "Force recreate participant context", key = SUPERUSER_FORCE_RECREATE_PROPERTY, required = false, defaultValue = "false")
    private String recreate;

    @Setting(description = "Hashicorp Vault URL, reused to seed this participant's own per-participant vault-config", key = VAULT_URL_PROPERTY, required = false)
    private String vaultUrl;

    @Setting(description = "Hashicorp Vault token, reused to seed this participant's own per-participant vault-config", key = VAULT_TOKEN_PROPERTY, required = false)
    private String vaultToken;

    @Inject
    private Monitor monitor;

    @Inject
    private ParticipantContextService participantContextService;

    @Inject
    private ParticipantContextConfigService configService;

    @Inject
    private Vault vault;

    @Inject
    private TypeManager typeManager;

    private String participantId;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        participantId = context.getSetting("edc.participant.id", ServiceExtensionContext.ANONYMOUS_PARTICIPANT);
    }

    @Override
    public void start() {
        if (Boolean.parseBoolean(recreate)) {
            participantContextService.deleteParticipantContext(participantId)
                    .orElseThrow(failure -> new EdcException(failure.getFailureDetail()));
            monitor.debug("Force deletion of participant context with ID '%s'".formatted(participantId));
        }

        // create super-user
        if (participantContextService.getParticipantContext(participantId).succeeded()) { // already exists
            monitor.debug("Super-user already exists with ID '%s', will not re-create".formatted(participantId));
            reseedVaultConfig();
            return;
        }

        ofNullable(vault.resolveSecret(publicKeyAlias))
                .map(this::createParticipant)
                .orElseThrow(() -> new EdcException("Failed to find public key with is '%s' in vault".formatted(publicKeyAlias)));
    }

    private CreateParticipantContextResponse createParticipant(String publicKeyPem) {
        var tr = new TypeReference<List<Service>>() {
        };
        var builder = ParticipantManifest.Builder.newInstance()
                .participantContextId(participantId)
                .did(participantId)
                .active(true)
                .key(KeyDescriptor.Builder.newInstance()
                        .keyId("%s#%s".formatted(participantId, "my-key"))
                        .privateKeyAlias(privateKeyAlias)
                        .publicKeyPem(publicKeyPem)
                        .build())
                .roles(List.of(ROLE_ADMIN));
        typeManager.readValue(services, tr).forEach(builder::serviceEndpoint);
        ofNullable(vaultConfigJson()).ifPresent(json -> builder.property(VAULT_CONFIG_PROPERTY, json));
        return participantContextService.createParticipantContext(builder.build())
                .orElseThrow(failure -> new EdcException(failure.getFailureDetail()));
    }

    // ParticipantContextConfigStore is in-memory only (no persistent SQL-backed variant at EDC 0.15.0), so
    // it's wiped on every pod restart. createParticipant() below only seeds vault-config via the manifest
    // at first creation — once the superuser already exists (persisted in participant_context), that path
    // is skipped on every later boot and vault-config lookups fail with "No configuration found for
    // participant context" until the participant is deleted and recreated. Force a re-seed here on every
    // boot regardless of whether creation ran.
    private void reseedVaultConfig() {
        ofNullable(vaultConfigJson()).ifPresent(json -> {
            var cfg = ParticipantContextConfiguration.Builder.newInstance()
                    .participantContextId(participantId)
                    .privateEntry(VAULT_CONFIG_PROPERTY, json)
                    .build();
            configService.save(cfg)
                    .onFailure(f -> monitor.warning("Failed to re-seed vault-config for '%s': %s".formatted(participantId, f.getFailureDetail())));
        });
    }

    // EDC 0.15.0 partitions vault secrets per participant context id; each identity-hub here already owns a
    // single dedicated vault, so the same URL/token used for the default vault client are reused as this
    // participant's per-participant vault-config instead of provisioning a second vault.
    private String vaultConfigJson() {
        if (vaultUrl == null || vaultToken == null) {
            return null;
        }
        var config = Map.of(
                "vaultUrl", vaultUrl,
                "secretPath", "/v1/secret",
                "healthCheckPath", "/v1/sys/health",
                "ttl", 300,
                "renewBuffer", 30
        );
        var credentials = Map.of("token", vaultToken);
        var settings = Map.of("config", config, "credentials", credentials);
        return typeManager.writeValueAsString(settings);
    }

}
