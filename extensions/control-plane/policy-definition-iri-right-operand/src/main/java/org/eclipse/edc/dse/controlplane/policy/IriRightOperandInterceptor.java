package org.eclipse.edc.dse.controlplane.policy;

import jakarta.annotation.Priority;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Applies {@link IriRightOperandNormalizer} to a policy-definition request body.
 * <p>
 * EDC's {@code JerseyJsonLdInterceptor} is registered without a priority ({@link Priorities#USER}) and
 * replaces the body with its expanded form before proceeding. Reader interceptors run in ascending
 * priority order, so at {@code USER + 100} this one receives the expanded body, before the controller
 * validates and transforms it.
 */
@Priority(Priorities.USER + 100)
public class IriRightOperandInterceptor implements ReaderInterceptor {

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException, WebApplicationException {
        if (JsonObject.class.equals(context.getType())) {
            var bytes = context.getInputStream().readAllBytes();
            if (bytes.length > 0) {
                JsonObject expanded;
                try (var reader = Json.createReader(new ByteArrayInputStream(bytes))) {
                    expanded = reader.readObject();
                }
                var normalized = IriRightOperandNormalizer.normalize(expanded);
                if (normalized != expanded) {
                    var out = new ByteArrayOutputStream();
                    try (var writer = Json.createWriter(out)) {
                        writer.writeObject(normalized);
                    }
                    bytes = out.toByteArray();
                }
            }
            context.setInputStream(new ByteArrayInputStream(bytes));
        }
        return context.proceed();
    }
}
