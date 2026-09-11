package org.eclipse.dse.identityhub.activation;

import org.eclipse.edc.identityhub.spi.participantcontext.ParticipantContextService;
import org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextCreated;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantContext;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantContextState;
import org.eclipse.edc.spi.event.Event;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.event.EventSubscriber;
import org.eclipse.edc.spi.monitor.Monitor;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Works around an upstream IdentityHub 0.15.x race condition (0.15.1 confirmed not fixed): on
 * {@code ParticipantContextCreated}, {@code ParticipantContextEventCoordinator} adds the keypair before it
 * activates the participant. Adding the keypair synchronously fires {@code KeyPairAdded}, whose handler tries to
 * publish the DID document while the participant is still in {@code CREATED} state, fails, and swallows the
 * failure (logged as a warning only). Depending on timing, the subsequent activation step in the same compose
 * chain can lose the race too, leaving the participant stuck in {@code CREATED} forever with credential issuance
 * timing out downstream. This subscriber re-checks activation state after the fact and forces it through again,
 * for every participant regardless of how it was created (seed extension or REST API).
 */
class ParticipantActivationHealer implements EventSubscriber {

    private static final List<Integer> RETRY_DELAYS_SECONDS = List.of(3, 8, 20, 45);

    private final Monitor monitor;
    private final ParticipantContextService participantContextService;
    private final ScheduledExecutorService executor;
    private final List<Integer> retryDelaysSeconds;

    ParticipantActivationHealer(Monitor monitor, ParticipantContextService participantContextService) {
        this(monitor, participantContextService, Executors.newSingleThreadScheduledExecutor(), RETRY_DELAYS_SECONDS);
    }

    // Visible for tests: lets a test inject a short retry schedule instead of waiting on the real one (up to 76s).
    ParticipantActivationHealer(Monitor monitor, ParticipantContextService participantContextService,
                                 ScheduledExecutorService executor, List<Integer> retryDelaysSeconds) {
        this.monitor = monitor;
        this.participantContextService = participantContextService;
        this.executor = executor;
        this.retryDelaysSeconds = retryDelaysSeconds;
    }

    @Override
    public <E extends Event> void on(EventEnvelope<E> event) {
        if (event.getPayload() instanceof ParticipantContextCreated created && created.getManifest().isActive()) {
            scheduleCheck(created.getParticipantContextId(), 0);
        }
    }

    void stop() {
        executor.shutdown();
    }

    private void scheduleCheck(String participantContextId, int attempt) {
        if (attempt >= retryDelaysSeconds.size()) {
            monitor.severe("[ParticipantActivationHealer] Participant '%s' never reached ACTIVATED after %d attempts, giving up."
                    .formatted(participantContextId, attempt));
            return;
        }
        executor.schedule(() -> checkAndHeal(participantContextId, attempt), retryDelaysSeconds.get(attempt), TimeUnit.SECONDS);
    }

    private void checkAndHeal(String participantContextId, int attempt) {
        var result = participantContextService.getParticipantContext(participantContextId);
        if (result.failed()) {
            monitor.warning("[ParticipantActivationHealer] Could not look up participant '%s': %s"
                    .formatted(participantContextId, result.getFailureDetail()));
            return;
        }

        var state = result.getContent().getStateAsEnum();
        if (state != ParticipantContextState.CREATED) {
            // Either healed (ACTIVATED) or deliberately moved elsewhere (e.g. DEACTIVATED) since creation —
            // in both cases this is not the race this healer targets, so leave it alone.
            monitor.debug("[ParticipantActivationHealer] Participant '%s' is '%s', nothing to heal (attempt %d)."
                    .formatted(participantContextId, state, attempt));
            return;
        }

        monitor.warning("[ParticipantActivationHealer] Participant '%s' still 'CREATED' after creation, forcing activation again (attempt %d)."
                .formatted(participantContextId, attempt));
        participantContextService.updateParticipant(participantContextId, ParticipantContext::activate)
                .onFailure(f -> monitor.warning("[ParticipantActivationHealer] Re-activation of '%s' failed: %s".formatted(participantContextId, f.getFailureDetail())));

        scheduleCheck(participantContextId, attempt + 1);
    }
}
