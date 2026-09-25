package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.edr.EndpointDataReferenceServiceRegistry;
import org.eclipse.edc.connector.dataplane.spi.iam.DataPlaneAccessTokenService;
import org.eclipse.edc.connector.dataplane.spi.iam.PublicEndpointGeneratorService;
import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Setting;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

import java.time.Clock;

import static org.eclipse.edc.spi.system.ServiceExtensionContext.ANONYMOUS_PARTICIPANT;

@Extension(value = SftpSourceExtension.NAME)
public class SftpSourceExtension implements ServiceExtension {

    public static final String NAME = "DSE SFTP Source";

    @Setting(description = "Configures the participant id this runtime is operating on behalf of", key = "edc.participant.id", defaultValue = ANONYMOUS_PARTICIPANT)
    private String participantId;

    @Inject
    private PipelineService pipelineService;

    @Inject
    private Vault vault;

    @Inject
    private Clock clock;

    @Inject
    private DataPlaneAccessTokenService accessTokenService;

    @Inject
    private PublicEndpointGeneratorService endpointGenerator;

    @Inject
    private EndpointDataReferenceServiceRegistry endpointDataReferenceServiceRegistry;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        pipelineService.registerFactory(new SftpDataSourceFactory(context.getMonitor(), vault));

        SftpEndpointDataReferenceService edrService = new SftpEndpointDataReferenceService(accessTokenService, endpointGenerator, participantId, clock);
        endpointDataReferenceServiceRegistry.register(SftpDataAddress.SFTP_TYPE, edrService);
    }
}
