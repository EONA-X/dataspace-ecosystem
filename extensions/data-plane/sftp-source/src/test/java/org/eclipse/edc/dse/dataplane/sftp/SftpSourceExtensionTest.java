package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceService;
import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceServiceRegistry;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAccessTokenService;
import org.eclipse.edc.connector.dataplane.spi.iam.PublicEndpointGeneratorService;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.junit.extensions.DependencyInjectionExtension;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(DependencyInjectionExtension.class)
class SftpSourceExtensionTest {

    private final PipelineService pipelineService = mock();
    private final EndpointDataReferenceServiceRegistry endpointDataReferenceServiceRegistry = mock();

    @BeforeEach
    void setup(ServiceExtensionContext context) {
        context.registerService(PipelineService.class, pipelineService);
        context.registerService(Vault.class, mock());
        context.registerService(DataPlaneAccessTokenService.class, mock());
        context.registerService(PublicEndpointGeneratorService.class, mock());
        context.registerService(EndpointDataReferenceServiceRegistry.class, endpointDataReferenceServiceRegistry);
    }

    @Test
    void initialize_shouldRegisterSourceFactoryAndEdrServiceForSftpType(SftpSourceExtension extension, ServiceExtensionContext context) {
        extension.initialize(context);

        verify(pipelineService).registerFactory(isA(SftpDataSourceFactory.class));
        verify(endpointDataReferenceServiceRegistry).register(eq(SftpDataAddress.SFTP_TYPE), any(EndpointDataReferenceService.class));
    }
}
