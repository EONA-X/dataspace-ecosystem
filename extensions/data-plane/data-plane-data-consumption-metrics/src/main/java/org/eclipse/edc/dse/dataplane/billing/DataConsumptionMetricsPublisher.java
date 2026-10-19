package org.eclipse.edc.dse.dataplane.billing;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.eclipse.dse.edc.spi.telemetryagent.DataConsumptionRecord;
import org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStore;
import org.eclipse.edc.spi.monitor.Monitor;
import org.eclipse.edc.spi.telemetry.Telemetry;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;

public class DataConsumptionMetricsPublisher implements ContainerResponseFilter, WriterInterceptor {

    protected static final String CONTRACT_ID_HEADER = "Contract-Id";
    protected static final String TRACE_PARENT_HEADER = "traceparent";

    private static final String CONTRACT_ID_PROPERTY = "billing.contractId";
    private static final String TRACE_CONTEXT_PROPERTY = "billing.traceContext";
    private static final String STATUS_PROPERTY = "billing.status";

    private final TelemetryRecordStore telemetryRecordStore;
    private final Monitor monitor;
    private final Telemetry telemetry;
    private final String ownDid;

    DataConsumptionMetricsPublisher(TelemetryRecordStore telemetryRecordStore, Monitor monitor, Telemetry telemetry, String ownDid) {
        this.telemetryRecordStore = telemetryRecordStore;
        this.monitor = monitor;
        this.telemetry = telemetry;
        this.ownDid = ownDid;
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        var contractIdOpt = Optional.ofNullable(requestContext.getHeaderString(CONTRACT_ID_HEADER));
        if (contractIdOpt.isEmpty()) {
            // Nothing to bill: a request rejected before a contract was ever resolved (missing/
            // invalid auth, bad input, ...) never carried a Contract-Id in the first place.
            // This filter runs on every response including those, so throwing here would
            // clobber an already-built error response (e.g. a clean 401) with a 500 — the
            // request's own handler already reported whatever went wrong.
            monitor.debug("[BillingConsumptionMetrics] no '%s' header on this response (status=%d) — skipping billing"
                    .formatted(CONTRACT_ID_HEADER, responseContext.getStatus()));
            return;
        }
        var contractId = contractIdOpt.get();
        var traceContext = telemetry.getCurrentTraceContext();
        var status = responseContext.getStatus();

        if (responseContext.getEntity() == null) {
            publish(contractId, 0L, status, traceContext);
            return;
        }

        // real body size is only known once JAX-RS actually serializes it -- see aroundWriteTo
        requestContext.setProperty(CONTRACT_ID_PROPERTY, contractId);
        requestContext.setProperty(TRACE_CONTEXT_PROPERTY, traceContext);
        requestContext.setProperty(STATUS_PROPERTY, status);
    }

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        var countingOutput = new CountingOutputStream(context.getOutputStream());
        context.setOutputStream(countingOutput);
        try {
            context.proceed();
        } finally {
            var contractId = (String) context.getProperty(CONTRACT_ID_PROPERTY);
            // filter() only sets these properties once it has resolved a Contract-Id (see its own
            // early return) -- a null here means it never did, so there's nothing to bill.
            if (contractId != null) {
                @SuppressWarnings("unchecked")
                var traceContext = (Map<String, String>) context.getProperty(TRACE_CONTEXT_PROPERTY);
                var status = (int) context.getProperty(STATUS_PROPERTY);
                publish(contractId, countingOutput.getCount(), status, traceContext);
            }
        }
    }

    private void publish(String contractId, long responseSize, int status, Map<String, String> traceContext) {
        logPublication(contractId, responseSize, status, traceContext.get(TRACE_PARENT_HEADER));

        var record = DataConsumptionRecord.Builder.newInstance()
                .contractId(contractId)
                .responseSize(responseSize)
                .responseStatusCode(status)
                .participantId(ownDid)
                .traceContext(traceContext)
                .timestamp(System.currentTimeMillis())
                .build();

        telemetryRecordStore.save(record);
    }

    private void logPublication(String contractId, long responseSize, int status, String traceParent) {
        monitor.debug("[BillingConsumptionMetrics] contractId=%s status=%d responseSize=%d bytes traceparent=%s"
                .formatted(contractId, status, responseSize, traceParent));
    }

    private static final class CountingOutputStream extends FilterOutputStream {

        private long count;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long getCount() {
            return count;
        }
    }

}
