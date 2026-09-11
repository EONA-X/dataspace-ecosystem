package org.eclipse.dse.common.participantcontext;

import org.eclipse.edc.participantcontext.single.spi.SingleParticipantContextSupplier;
import org.eclipse.edc.participantcontext.spi.config.model.ParticipantContextConfiguration;
import org.eclipse.edc.participantcontext.spi.config.store.ParticipantContextConfigStore;
import org.eclipse.edc.participantcontext.spi.identity.ParticipantIdentityResolver;
import org.eclipse.edc.participantcontext.spi.types.ParticipantContext;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.types.TypeManager;

import java.util.Map;
import java.util.Optional;

import static org.eclipse.edc.spi.system.ServiceExtensionContext.ANONYMOUS_PARTICIPANT;

/**
 * Replaces upstream's {@code SingleParticipantContextDefaultServicesExtension} (module
 * {@code participant-context-single-core}, excluded from this runtime's dependencies).
 * <p>
 * Upstream's {@code SingleParticipantContextConfigStore} only ever populates the config's public
 * {@code entries()}, never {@code privateEntries()} — but {@code ParticipantContextConfig.getSensitiveString}
 * (used by HashicorpVault to resolve this participant's own vault-config partition) reads exclusively from
 * {@code privateEntries()}. That mismatch makes it impossible for a single-participant runtime (control-plane,
 * data-plane) to ever satisfy a per-participant vault-config lookup for its own participant id. This extension
 * provides the same 3 services, with the vault-config additionally seeded into privateEntries.
 */
@Extension(SingleParticipantContextVaultSeedExtension.NAME)
public class SingleParticipantContextVaultSeedExtension implements ServiceExtension {

    public static final String NAME = "Single Participant Context Vault Seed Extension";

    private static final String VAULT_CONFIG_URL_KEY = "vaultUrl";
    private static final String VAULT_CONFIG_SECRET_PATH_KEY = "secretPath";
    private static final String VAULT_CONFIG_SECRET_PATH = "/v1/secret";
    private static final String VAULT_CONFIG_HEALTH_CHECK_PATH_KEY = "healthCheckPath";
    private static final String VAULT_CONFIG_HEALTH_CHECK_PATH = "/v1/sys/health";
    private static final String VAULT_CONFIG_TTL_KEY = "ttl";
    private static final int VAULT_CONFIG_TTL = 300;
    private static final String VAULT_CONFIG_RENEW_BUFFER_KEY = "renewBuffer";
    private static final int VAULT_CONFIG_RENEW_BUFFER = 30;

    @Setting(description = "Configures the participant id this runtime is operating on behalf of", key = "edc.participant.id", defaultValue = ANONYMOUS_PARTICIPANT)
    private String participantId;

    @Setting(description = "Configures the participant context id for the single participant runtime", key = "edc.participant.context.id", required = false)
    private String participantContextId;

    @Setting(description = "Hashicorp Vault URL, reused to seed this runtime's own per-participant vault-config", key = "edc.vault.hashicorp.url", required = false)
    private String vaultUrl;

    @Setting(description = "Hashicorp Vault token, reused to seed this runtime's own per-participant vault-config", key = "edc.vault.hashicorp.token", required = false)
    private String vaultToken;

    @Inject
    private TypeManager typeManager;

    @Override
    public String name() {
        return NAME;
    }

    @Provider(isDefault = true)
    public SingleParticipantContextSupplier participantContextSupplier() {
        var participantContext = ParticipantContext.Builder.newInstance().participantContextId(contextId())
                .identity(participantId).build();
        return () -> ServiceResult.success(participantContext);
    }

    @Provider
    public ParticipantContextConfigStore participantContextConfigStore(ServiceExtensionContext context) {
        var builder = ParticipantContextConfiguration.Builder.newInstance()
                .participantContextId(contextId())
                .entries(context.getConfig().getEntries());
        vaultConfigJson().ifPresent(json -> builder.privateEntries(Map.of("edc.vault.hashicorp.config", json)));
        return new SingleParticipantContextConfigStore(builder.build());
    }

    @Provider(isDefault = true)
    public ParticipantIdentityResolver participantIdentityResolver() {
        return (context, protocol) -> participantId;
    }

    private String contextId() {
        return participantContextId != null ? participantContextId : participantId;
    }

    // Control-plane/data-plane runtimes here own a single dedicated Hashicorp Vault, so the same URL/token
    // used for the default vault client are reused as this runtime's own per-participant vault-config.
    private Optional<String> vaultConfigJson() {
        if (vaultUrl == null || vaultToken == null) {
            return Optional.empty();
        }
        Map<String, Object> config = Map.of(
                VAULT_CONFIG_URL_KEY, vaultUrl,
                VAULT_CONFIG_SECRET_PATH_KEY, VAULT_CONFIG_SECRET_PATH,
                VAULT_CONFIG_HEALTH_CHECK_PATH_KEY, VAULT_CONFIG_HEALTH_CHECK_PATH,
                VAULT_CONFIG_TTL_KEY, VAULT_CONFIG_TTL,
                VAULT_CONFIG_RENEW_BUFFER_KEY, VAULT_CONFIG_RENEW_BUFFER
        );
        Map<String, String> credentials = Map.of("token", vaultToken);
        Map<String, Object> settings = Map.of("config", config, "credentials", credentials);
        return Optional.of(typeManager.writeValueAsString(settings));
    }

    private static final class SingleParticipantContextConfigStore implements ParticipantContextConfigStore {

        private final ParticipantContextConfiguration config;

        private SingleParticipantContextConfigStore(ParticipantContextConfiguration config) {
            this.config = config;
        }

        @Override
        public void save(ParticipantContextConfiguration config) {
            throw new UnsupportedOperationException("SingleParticipantContextConfigStore is read-only");
        }

        @Override
        public ParticipantContextConfiguration get(String participantContextId) {
            return config.getParticipantContextId().equals(participantContextId) ? config : null;
        }
    }

}
