package org.eclipse.edc.dse.dataplane.sftp;

import org.eclipse.edc.connector.dataplane.spi.pipeline.PipelineService;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.security.Vault;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

@Extension(value = SftpSourceExtension.NAME)
public class SftpSourceExtension implements ServiceExtension {

    public static final String NAME = "DSE SFTP Source";

    @Inject
    private PipelineService pipelineService;

    @Inject
    private Vault vault;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        pipelineService.registerFactory(new SftpDataSourceFactory(context.getMonitor(), vault));
    }
}
