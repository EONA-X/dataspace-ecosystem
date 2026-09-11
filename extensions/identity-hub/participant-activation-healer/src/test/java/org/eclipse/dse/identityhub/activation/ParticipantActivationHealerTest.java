package org.eclipse.dse.identityhub.activation;

import org.eclipse.edc.identityhub.spi.participantcontext.ParticipantContextService;
import org.eclipse.edc.identityhub.spi.participantcontext.events.ParticipantContextCreated;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantContext;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantContextState;
import org.eclipse.edc.identityhub.spi.participantcontext.model.ParticipantManifest;
import org.eclipse.edc.spi.event.EventEnvelope;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.result.ServiceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ParticipantActivationHealerTest {

    private static final String PARTICIPANT_CONTEXT_ID = "participant-1";

    private final Monitor monitor = mock(Monitor.class);
    private final ParticipantContextService participantContextService = mock(ParticipantContextService.class);
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    // 2 near-instant retries instead of the real 3/8/20/45s schedule, so the test doesn't wait ~76s.
    private final ParticipantActivationHealer healer =
            new ParticipantActivationHealer(monitor, participantContextService, executor, List.of(0, 0));

    @AfterEach
    void tearDown() {
        healer.stop();
    }

    @Test
    void on_inactiveManifest_shouldNotCheckAnything() {
        healer.on(eventFor(PARTICIPANT_CONTEXT_ID, false));

        verifyNoInteractions(participantContextService);
    }

    @Test
    void on_activeManifest_alreadyActivated_shouldNotForceReactivate() {
        var context = mock(ParticipantContext.class);
        when(context.getStateAsEnum()).thenReturn(ParticipantContextState.ACTIVATED);
        when(participantContextService.getParticipantContext(PARTICIPANT_CONTEXT_ID)).thenReturn(ServiceResult.success(context));

        healer.on(eventFor(PARTICIPANT_CONTEXT_ID, true));

        verify(participantContextService, timeout(2000)).getParticipantContext(PARTICIPANT_CONTEXT_ID);
        verify(participantContextService, never()).updateParticipant(any(), any());
    }

    @Test
    void on_activeManifest_deactivatedAfterCreation_shouldNotForceReactivate() {
        // Regression test: a participant deliberately deactivated right after creation must not get
        // silently re-activated by the healer just because it isn't ACTIVATED.
        var context = mock(ParticipantContext.class);
        when(context.getStateAsEnum()).thenReturn(ParticipantContextState.DEACTIVATED);
        when(participantContextService.getParticipantContext(PARTICIPANT_CONTEXT_ID)).thenReturn(ServiceResult.success(context));

        healer.on(eventFor(PARTICIPANT_CONTEXT_ID, true));

        verify(participantContextService, timeout(2000)).getParticipantContext(PARTICIPANT_CONTEXT_ID);
        verify(participantContextService, never()).updateParticipant(any(), any());
    }

    @Test
    void on_activeManifest_stuckInCreated_shouldForceActivateOnEveryAttemptThenGiveUp() {
        var context = mock(ParticipantContext.class);
        when(context.getStateAsEnum()).thenReturn(ParticipantContextState.CREATED);
        when(participantContextService.getParticipantContext(PARTICIPANT_CONTEXT_ID)).thenReturn(ServiceResult.success(context));
        when(participantContextService.updateParticipant(eq(PARTICIPANT_CONTEXT_ID), any())).thenReturn(ServiceResult.success());

        healer.on(eventFor(PARTICIPANT_CONTEXT_ID, true));

        // retryDelaysSeconds = List.of(0, 0) -> 2 attempts, both still CREATED, both force-activate, then gives up.
        verify(participantContextService, timeout(2000).times(2)).updateParticipant(eq(PARTICIPANT_CONTEXT_ID), any());
        verify(monitor, timeout(2000)).severe(anyString());
    }

    @Test
    void on_activeManifest_stuckInCreated_shouldCallActivateOnTheParticipant() {
        var context = mock(ParticipantContext.class);
        when(context.getStateAsEnum()).thenReturn(ParticipantContextState.CREATED);
        when(participantContextService.getParticipantContext(PARTICIPANT_CONTEXT_ID)).thenReturn(ServiceResult.success(context));
        when(participantContextService.updateParticipant(eq(PARTICIPANT_CONTEXT_ID), any())).thenReturn(ServiceResult.success());

        healer.on(eventFor(PARTICIPANT_CONTEXT_ID, true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ParticipantContext>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(participantContextService, timeout(2000).atLeastOnce()).updateParticipant(eq(PARTICIPANT_CONTEXT_ID), captor.capture());

        captor.getValue().accept(context);

        verify(context).activate();
    }

    @Test
    void on_getParticipantContextFails_shouldNotThrowAndNotRetry() {
        when(participantContextService.getParticipantContext(PARTICIPANT_CONTEXT_ID)).thenReturn(ServiceResult.notFound("not found"));

        healer.on(eventFor(PARTICIPANT_CONTEXT_ID, true));

        verify(participantContextService, timeout(2000)).getParticipantContext(PARTICIPANT_CONTEXT_ID);
        verify(participantContextService, never()).updateParticipant(any(), any());
    }

    private EventEnvelope<ParticipantContextCreated> eventFor(String participantContextId, boolean active) {
        var manifest = ParticipantManifest.Builder.newInstance().active(active).build();
        var event = ParticipantContextCreated.Builder.newInstance()
                .participantContextId(participantContextId)
                .manifest(manifest)
                .build();
        return EventEnvelope.Builder.newInstance()
                .at(System.currentTimeMillis())
                .payload(event)
                .build();
    }
}
