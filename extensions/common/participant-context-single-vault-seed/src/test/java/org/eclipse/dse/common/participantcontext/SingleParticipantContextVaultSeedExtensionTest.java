package org.eclipse.dse.common.participantcontext;

import org.eclipse.edc.json.JacksonTypeManager;
import org.eclipse.edc.junit.extensions.DependencyInjectionExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(DependencyInjectionExtension.class)
class SingleParticipantContextVaultSeedExtensionTest {

    private static final String PARTICIPANT_ID = UUID.randomUUID().toString();
    private static final String VAULT_URL = "http://" + PARTICIPANT_ID + "-vault:8200";
    private static final String VAULT_TOKEN = UUID.randomUUID().toString();

    private final TypeManager typeManager = new JacksonTypeManager();

    @Nested
    class WithVaultConfigured {

        @BeforeEach
        void setup(ServiceExtensionContext context) {
            context.registerService(TypeManager.class, typeManager);
            when(context.getConfig()).thenReturn(ConfigFactory.fromMap(Map.of(
                    "edc.participant.id", PARTICIPANT_ID,
                    "edc.vault.hashicorp.url", VAULT_URL,
                    "edc.vault.hashicorp.token", VAULT_TOKEN)));
        }

        @Test
        void configStore_shouldSeedVaultConfig(SingleParticipantContextVaultSeedExtension ext, ServiceExtensionContext context) {
            var store = ext.participantContextConfigStore(context);
            var config = store.get(PARTICIPANT_ID);

            assertThat(config).isNotNull();
            var vaultConfigJson = config.getPrivateEntries().get("edc.vault.hashicorp.config");
            assertThat(vaultConfigJson).isNotNull().contains(VAULT_URL).contains(VAULT_TOKEN);
        }

        @Test
        void configStore_shouldReturnNull_forUnknownParticipant(SingleParticipantContextVaultSeedExtension ext, ServiceExtensionContext context) {
            var store = ext.participantContextConfigStore(context);

            assertThat(store.get("some-other-participant")).isNull();
        }

        @Test
        void participantContextSupplier_shouldResolveToConfiguredParticipant(SingleParticipantContextVaultSeedExtension ext, ServiceExtensionContext context) {
            ext.initialize(context);

            var result = ext.participantContextSupplier().get();

            assertThat(result.succeeded()).isTrue();
            assertThat(result.getContent().getParticipantContextId()).isEqualTo(PARTICIPANT_ID);
        }

        @Test
        void configStore_save_shouldThrowUnsupportedOperationException(SingleParticipantContextVaultSeedExtension ext, ServiceExtensionContext context) {
            var store = ext.participantContextConfigStore(context);
            var config = store.get(PARTICIPANT_ID);

            assertThatThrownBy(() -> store.save(config)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void participantIdentityResolver_shouldReturnConfiguredParticipantId(SingleParticipantContextVaultSeedExtension ext) {
            var resolver = ext.participantIdentityResolver();

            assertThat(resolver.getParticipantId("any-context", "any-protocol")).isEqualTo(PARTICIPANT_ID);
        }
    }

    @Nested
    class WithExplicitParticipantContextId {

        private final String contextId = "context-" + UUID.randomUUID();

        @BeforeEach
        void setup(ServiceExtensionContext context) {
            context.registerService(TypeManager.class, typeManager);
            when(context.getConfig()).thenReturn(ConfigFactory.fromMap(Map.of(
                    "edc.participant.id", PARTICIPANT_ID,
                    "edc.participant.context.id", contextId)));
        }

        @Test
        void participantContextSupplier_shouldResolveToExplicitContextId(SingleParticipantContextVaultSeedExtension ext) {
            var result = ext.participantContextSupplier().get();

            assertThat(result.succeeded()).isTrue();
            assertThat(result.getContent().getParticipantContextId()).isEqualTo(contextId);
        }

        @Test
        void configStore_shouldBeKeyedByExplicitContextId_notParticipantId(SingleParticipantContextVaultSeedExtension ext, ServiceExtensionContext context) {
            var store = ext.participantContextConfigStore(context);

            assertThat(store.get(contextId)).isNotNull();
            assertThat(store.get(PARTICIPANT_ID)).isNull();
        }
    }

    @Nested
    class WithoutVaultConfigured {

        @BeforeEach
        void setup(ServiceExtensionContext context) {
            context.registerService(TypeManager.class, typeManager);
            when(context.getConfig()).thenReturn(ConfigFactory.fromMap(Map.of("edc.participant.id", PARTICIPANT_ID)));
        }

        @Test
        void configStore_shouldNotSeedVaultConfig(SingleParticipantContextVaultSeedExtension ext, ServiceExtensionContext context) {
            var store = ext.participantContextConfigStore(context);
            var config = store.get(PARTICIPANT_ID);

            assertThat(config).isNotNull();
            assertThat(config.getPrivateEntries()).isEmpty();
        }
    }

}
