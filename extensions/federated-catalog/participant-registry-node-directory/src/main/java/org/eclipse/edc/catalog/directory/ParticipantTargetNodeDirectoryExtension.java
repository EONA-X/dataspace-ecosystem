package org.eclipse.edc.catalog.directory;

import org.eclipse.edc.crawler.spi.TargetNodeDirectory;
import org.eclipse.edc.iam.did.spi.resolution.DidResolverRegistry;
import org.eclipse.edc.issuerservice.spi.holder.store.HolderStore;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.runtime.metamodel.annotation.Provider;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;
import org.eclipse.edc.transaction.spi.TransactionContext;

@Extension(value = ParticipantTargetNodeDirectoryExtension.NAME)
public class ParticipantTargetNodeDirectoryExtension implements ServiceExtension {

    public static final String NAME = "Participant Target Node Directory";

    @Inject
    private HolderStore holderStore;

    @Inject
    private TransactionContext transactionContext;

    @Inject
    private Monitor monitor;

    @Inject
    private DidResolverRegistry didResolverRegistry;

    @Override
    public String name() {
        return NAME;
    }

    @Provider
    public TargetNodeDirectory targetNodeDirectory(ServiceExtensionContext context) {
        return new ParticipantTargetNodeDirectory(holderStore, transactionContext, new ParticipantToTargetNodeResolver(didResolverRegistry), context.getSetting("edc.participant.id", ServiceExtensionContext.ANONYMOUS_PARTICIPANT), monitor);
    }

}
