package org.eclipse.edc.dse.dataplane.billing;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.eclipse.dse.edc.spi.telemetryagent.DataConsumptionRecord;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStore;
import org.eclipse.edc.spi.telemetry.Telemetry;
import org.eclipse.edc.web.spi.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.eclipse.edc.dse.dataplane.billing.DataConsumptionMetricsPublisher.CONTRACT_ID_HEADER;
import static org.eclipse.edc.dse.dataplane.billing.DataConsumptionMetricsPublisher.TRACE_PARENT_HEADER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataConsumptionMetricsPublisherTest {

    private static final String PARTICIPANT_ID = "did:web:" + UUID.randomUUID();

    private final ContainerRequestContext requestContext = mock();
    private final TelemetryRecordStore telemetryRecordStore = mock();
    private final Telemetry telemetry = mock();
    private final ContainerResponseContext responseContext = mock();

    private final DataConsumptionMetricsPublisher publisher = new DataConsumptionMetricsPublisher(telemetryRecordStore, mock(), telemetry, PARTICIPANT_ID);

    @Test
    void filter_noEntity_publishesImmediatelyWithZeroSize() {
        var contractId = "contract-123";
        var traceContext = new HashMap<String, String>();
        traceContext.put(TRACE_PARENT_HEADER, "12345");
        when(responseContext.getEntity()).thenReturn(null);
        when(responseContext.getStatus()).thenReturn(400);
        when(requestContext.getHeaderString(CONTRACT_ID_HEADER)).thenReturn(contractId);
        when(telemetry.getCurrentTraceContext()).thenReturn(traceContext);

        publisher.filter(requestContext, responseContext);

        var recordCaptor = ArgumentCaptor.forClass(DataConsumptionRecord.class);
        verify(telemetryRecordStore, times(1)).save(recordCaptor.capture());
        var record = recordCaptor.getValue();

        assertThat(record.getContractId()).isEqualTo(contractId);
        assertThat(record.getResponseSize()).isEqualTo(0);
        assertThat(record.getTraceContext()).isEqualTo(traceContext);
        assertThat(record.getParticipantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(record.getResponseStatusCode()).isEqualTo(400);
    }

    @Test
    void filter_withEntity_doesNotPublishYet() {
        var traceContext = new HashMap<String, String>();
        traceContext.put(TRACE_PARENT_HEADER, "12345");
        when(responseContext.getEntity()).thenReturn("response-entity");
        when(responseContext.getStatus()).thenReturn(200);
        when(requestContext.getHeaderString(CONTRACT_ID_HEADER)).thenReturn("contract-123");
        when(telemetry.getCurrentTraceContext()).thenReturn(traceContext);

        publisher.filter(requestContext, responseContext);

        // real size only known once JAX-RS actually serializes the body -- see aroundWriteTo
        verify(telemetryRecordStore, never()).save(any());
    }

    @Test
    void filter_withEntity_storesContextForAroundWriteTo() {
        var contractId = "contract-123";
        var traceContext = new HashMap<String, String>();
        traceContext.put(TRACE_PARENT_HEADER, "12345");
        when(responseContext.getEntity()).thenReturn("response-entity");
        when(responseContext.getStatus()).thenReturn(200);
        when(requestContext.getHeaderString(CONTRACT_ID_HEADER)).thenReturn(contractId);
        when(telemetry.getCurrentTraceContext()).thenReturn(traceContext);

        publisher.filter(requestContext, responseContext);

        verify(requestContext).setProperty(eq("billing.contractId"), eq(contractId));
        verify(requestContext).setProperty(eq("billing.traceContext"), eq(traceContext));
        verify(requestContext).setProperty(eq("billing.status"), eq(200));
    }

    @Test
    void filter_missingContractId_throws() {
        when(responseContext.getEntity()).thenReturn("response-entity");
        when(responseContext.getStatus()).thenReturn(200);
        when(requestContext.getHeaderString(CONTRACT_ID_HEADER)).thenReturn(null);

        var exception = assertThrows(InvalidRequestException.class, () -> publisher.filter(requestContext, responseContext));

        assertEquals("Missing '%s' header in request".formatted(CONTRACT_ID_HEADER), exception.getMessage());
        verify(telemetryRecordStore, never()).save(any());
    }

    @Test
    void aroundWriteTo_publishesRecordWithActualByteCountWritten() throws IOException {
        var contractId = "contract-123";
        var traceContext = new HashMap<String, String>();
        traceContext.put(TRACE_PARENT_HEADER, "12345");
        var payload = "response-entity".getBytes();
        StreamingOutput streamingOutput = out -> out.write(payload);

        var record = invokeAroundWriteTo(contractId, 200, traceContext, streamingOutput);

        assertThat(record.getContractId()).isEqualTo(contractId);
        assertThat(record.getResponseSize()).isEqualTo(payload.length);
        assertThat(record.getTraceContext()).isEqualTo(traceContext);
        assertThat(record.getParticipantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(record.getResponseStatusCode()).isEqualTo(200);
    }

    @Test
    void aroundWriteTo_responseSizeMatchesArbitraryPayloadSize() throws IOException {
        var payloadSize = ThreadLocalRandom.current().nextInt(1, 10_000_000);
        StreamingOutput streamingOutput = out -> out.write(new byte[payloadSize]);

        var record = invokeAroundWriteTo("contract-123", 200, new HashMap<>(), streamingOutput);

        assertThat(record.getResponseSize()).isEqualTo(payloadSize);
    }

    /**
     * Directly drives aroundWriteTo(), independent of filter(): stubs the properties it reads
     * and simulates JAX-RS resolving the entity to {@code streamingOutput} then writing it
     * through the OutputStream the publisher installs.
     */
    private DataConsumptionRecord invokeAroundWriteTo(String contractId, int status, HashMap<String, String> traceContext, StreamingOutput streamingOutput) throws IOException {
        WriterInterceptorContext writerContext = mock();
        when(writerContext.getProperty("billing.contractId")).thenReturn(contractId);
        when(writerContext.getProperty("billing.traceContext")).thenReturn(traceContext);
        when(writerContext.getProperty("billing.status")).thenReturn(status);
        when(writerContext.getEntity()).thenReturn(streamingOutput);
        when(writerContext.getOutputStream()).thenReturn(OutputStream.nullOutputStream());

        var outputCaptor = ArgumentCaptor.forClass(OutputStream.class);
        doAnswer(inv -> null).when(writerContext).setOutputStream(outputCaptor.capture());
        doAnswer(inv -> {
            ((StreamingOutput) writerContext.getEntity()).write(outputCaptor.getValue());
            return null;
        }).when(writerContext).proceed();

        publisher.aroundWriteTo(writerContext);

        var recordCaptor = ArgumentCaptor.forClass(DataConsumptionRecord.class);
        verify(telemetryRecordStore, times(1)).save(recordCaptor.capture());
        return recordCaptor.getValue();
    }
}
