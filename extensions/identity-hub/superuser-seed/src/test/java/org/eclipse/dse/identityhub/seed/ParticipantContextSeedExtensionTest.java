package org.eclipse.dse.identityhub.seed;

import org.eclipse.edc.iam.did.spi.document.Service;
import org.eclipse.edc.identityhub.spi.participantcontext.ParticipantContextService;
import org.eclipse.edc.identityhub.spi.participantcontext.model.CreateParticipantContextResponse;
import org.eclipse.edc.identityhub.spi.participantcontext.model.KeyDescriptor;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantContext;
import org.eclipse.edc.json.JacksonTypeManager;
import org.eclipse.edc.junit.extensions.DependencyInjectionExtension;
import org.eclipse.edc.participantcontext.spi.config.service.ParticipantContextConfigService;
import org.eclipse.edc.spi.EdcException;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.ServiceResult;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.spi.system.configuration.ConfigFactory;
import org.eclipse.edc.spi.types.TypeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.eclipse.dse.identityhub.seed.ParticipantContextSeedExtension.PRIVATE_KEY_ALIAS_PROPERTY;
import static org.eclipse.dse.identityhub.seed.ParticipantContextSeedExtension.PUBLIC_KEY_ALIAS_PROPERTY;
import static org.eclipse.dse.identityhub.seed.ParticipantContextSeedExtension.SUPERUSER_SERVICES_PROPERTY;
import static org.eclipse.dse.identityhub.seed.ParticipantContextSeedExtension.VAULT_CONFIG_PROPERTY;
import static org.eclipse.dse.identityhub.seed.ParticipantContextSeedExtension.VAULT_TOKEN_PROPERTY;
import static org.eclipse.dse.identityhub.seed.ParticipantContextSeedExtension.VAULT_URL_PROPERTY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.assertArg;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(DependencyInjectionExtension.class)
class ParticipantContextSeedExtensionTest {

    private static final String PUBLIC_KEY_ALIAS = UUID.randomUUID().toString();
    private static final String PRIVATE_KEY_ALIAS = UUID.randomUUID().toString();
    private static final String PARTICIPANT_ID = UUID.randomUUID().toString();
    private static final String VAULT_URL = "http://" + PARTICIPANT_ID + "-vault:8200";
    private static final String VAULT_TOKEN = UUID.randomUUID().toString();

    private final TypeManager typeManager = new JacksonTypeManager();
    private final List<Service> services = List.of(createService(), createService());
    private final ParticipantContextService participantContextService = mock();
    private final ParticipantContextConfigService configService = mock();
    private final Vault vault = mock();
    private final Monitor monitor = mock();

    @BeforeEach
    void setup(ServiceExtensionContext context) {
        context.registerService(ParticipantContextService.class, participantContextService);
        context.registerService(ParticipantContextConfigService.class, configService);
        context.registerService(Vault.class, vault);
        context.registerService(Monitor.class, monitor);
        context.registerService(TypeManager.class, typeManager);
        when(configService.save(any())).thenReturn(ServiceResult.success());
        when(context.getConfig()).thenReturn(ConfigFactory.fromMap(Map.of(PUBLIC_KEY_ALIAS_PROPERTY, PUBLIC_KEY_ALIAS,
                PRIVATE_KEY_ALIAS_PROPERTY, PRIVATE_KEY_ALIAS,
                SUPERUSER_SERVICES_PROPERTY, typeManager.writeValueAsString(services),
                "edc.participant.id", PARTICIPANT_ID,
                VAULT_URL_PROPERTY, VAULT_URL,
                VAULT_TOKEN_PROPERTY, VAULT_TOKEN))
        );
    }

    @Test
    void superUserAlreadyExists_shouldReseedVaultConfigButNotRecreate(ParticipantContextSeedExtension ext, ServiceExtensionContext context) {
        when(participantContextService.getParticipantContext(PARTICIPANT_ID))
                .thenReturn(ServiceResult.success(mock()));

        ext.initialize(context);
        ext.start();

        verifyNoMoreInteractions(vault);
        verify(participantContextService, never()).createParticipantContext(any());
        verify(configService).save(assertArg(cfg -> {
            assertThat(cfg.getParticipantContextId()).isEqualTo(PARTICIPANT_ID);
            var vaultConfigJson = cfg.getPrivateEntries().get(VAULT_CONFIG_PROPERTY);
            assertThat(vaultConfigJson).isNotNull().contains(VAULT_URL).contains(VAULT_TOKEN);
        }));
    }

    @Test
    void createSuperUser(ParticipantContextSeedExtension ext, ServiceExtensionContext context) {
        var apiTokenAlias = UUID.randomUUID().toString();
        when(participantContextService.getParticipantContext(PARTICIPANT_ID))
                .thenReturn(ServiceResult.notFound("not found"))
                .thenReturn(ServiceResult.success(ParticipantContext.Builder.newInstance()
                        .participantContextId(PARTICIPANT_ID)
                        .apiTokenAlias(apiTokenAlias)
                        .build()));
        var publicKeyPem = UUID.randomUUID().toString();
        when(vault.resolveSecret(PUBLIC_KEY_ALIAS)).thenReturn(publicKeyPem);
        when(participantContextService.createParticipantContext(
                assertArg(participantManifest -> {
                    assertThat(participantManifest.getServiceEndpoints()).usingRecursiveComparison().ignoringCollectionOrder().isEqualTo(services);
                    assertThat(participantManifest.getKeys()).singleElement()
                            .extracting(KeyDescriptor::getPublicKeyPem).isEqualTo(publicKeyPem);
                }))
        ).thenReturn(ServiceResult.success(new CreateParticipantContextResponse("some-key", null, null)));

        ext.initialize(context);
        ext.start();

        verify(participantContextService, never()).deleteParticipantContext(any());
        verify(participantContextService).createParticipantContext(any());
    }

    @Test
    void createSuperUser_shouldSeedVaultConfig(ParticipantContextSeedExtension ext, ServiceExtensionContext context) {
        when(participantContextService.getParticipantContext(PARTICIPANT_ID))
                .thenReturn(ServiceResult.notFound("not found"));
        var publicKeyPem = UUID.randomUUID().toString();
        when(vault.resolveSecret(PUBLIC_KEY_ALIAS)).thenReturn(publicKeyPem);
        when(participantContextService.createParticipantContext(
                assertArg(participantManifest -> {
                    var vaultConfigJson = (String) participantManifest.getProperty(VAULT_CONFIG_PROPERTY);
                    assertThat(vaultConfigJson).isNotNull().contains(VAULT_URL).contains(VAULT_TOKEN);
                }))
        ).thenReturn(ServiceResult.success(new CreateParticipantContextResponse("some-key", null, null)));

        ext.initialize(context);
        ext.start();

        verify(participantContextService).createParticipantContext(any());
    }

    @Test
    void createParticipantFails_shouldThrow(ParticipantContextSeedExtension ext, ServiceExtensionContext context) {
        when(participantContextService.getParticipantContext(PARTICIPANT_ID))
                .thenReturn(ServiceResult.notFound("not found"));
        var publicKeyPem = UUID.randomUUID().toString();
        when(vault.resolveSecret(PUBLIC_KEY_ALIAS)).thenReturn(publicKeyPem);
        when(participantContextService.createParticipantContext(any())).thenReturn(ServiceResult.conflict("some error"));

        ext.initialize(context);
        assertThatExceptionOfType(EdcException.class).isThrownBy(ext::start)
                .withMessageContaining("some error");
    }

    private static Service createService() {
        return new Service(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

}