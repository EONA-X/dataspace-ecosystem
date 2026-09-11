package org.eclipse.dse.identityhub.activation;

import org.eclipse.edc.identityhub.spi.participantcontext.ParticipantContextService;
import org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextCreated;
import org.eclipse.edc.runtime.metamodel.annotation.Extension;
import org.eclipse.edc.runtime.metamodel.annotation.Inject;
import org.eclipse.edc.spi.event.EventRouter;
import org.eclipse.edc.spi.system.ServiceExtension;
import org.eclipse.edc.spi.system.ServiceExtensionContext;

@Extension(ParticipantActivationHealerExtension.NAME)
public class ParticipantActivationHealerExtension implements ServiceExtension {

    public static final String NAME = "Participant Activation Healer Extension";

    @Inject
    private EventRouter eventRouter;

    @Inject
    private ParticipantContextService participantContextService;

    private ParticipantActivationHealer healer;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void initialize(ServiceExtensionContext context) {
        healer = new ParticipantActivationHealer(context.getMonitor().withPrefix("ParticipantActivationHealer"), participantContextService);
        eventRouter.register(ParticipantContextCreated.class, healer);
    }

    @Override
    public void shutdown() {
        healer.stop();
    }
}
